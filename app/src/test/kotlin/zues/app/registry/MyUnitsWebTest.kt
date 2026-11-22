package zues.app.registry

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.IssuerKeys
import zues.app.identity_org.Logins
import zues.app.identity_org.WhoAsks
import java.math.BigDecimal
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

private const val MINE_ISSUER = "https://id.example.test/realms/domuvai"
private const val MINE_AUDIENCE = "domuvai-api"

/**
 * GET /api/registry/me/units with sign-in configured, the repositories mocked and the real [MyUnits] and
 * [MyUnitsController] between them — so the date test, the share arithmetic and the party filter are the shipped ones.
 */
@WebMvcTest(MyUnitsController::class, properties = ["domuvai.auth.issuer-uri=$MINE_ISSUER", "domuvai.auth.audience=$MINE_AUDIENCE", "domuvai.auth.mode="])
@Import(WhoAsks::class, MyUnits::class)
class MyUnitsWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @TestConfiguration
    class Fixed {
        // 01:30 on 1 April in Sofia (UTC+3 once summer time has begun, on the last Sunday of March); still 31 March in UTC
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-03-31T22:30:00Z"), ZoneOffset.UTC)
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
    }

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var logins: Logins
    @MockitoBean lateinit var titles: TitleRepository
    @MockitoBean lateinit var units: PropertyUnitRepository
    @MockitoBean lateinit var entrances: EntranceRepository

    private val me = UUID.randomUUID()
    private val neighbour = UUID.randomUUID()
    private val entrance = Entrance(UUID.randomUUID(), UUID.randomUUID(), "Вх. А", "SELF_MANAGED")
    private val flat = PropertyUnit(UUID.randomUUID(), entrance.id, "ап. 5", "FLAT", null, BigDecimal("12.5000"), false)
    private val url = "/api/registry/me/units"

    private fun title(party: UUID, unit: PropertyUnit, from: String, to: String?, share: String = "1", role: String = "OWN") =
        Title(UUID.randomUUID(), unit.entranceId, unit.id, party, role, BigDecimal(share), LocalDate.parse(from), to?.let(LocalDate::parse))

    private fun given(vararg mine: Title, others: List<Title> = emptyList(), unit: PropertyUnit = flat) {
        whenever(logins.partyOf(MINE_ISSUER, "login-me")).thenReturn(me)
        whenever(titles.findByPartyId(me)).thenReturn(mine.toList())
        whenever(titles.findByPartyId(neighbour)).thenReturn(others)
        whenever(units.findAllById(any<Iterable<UUID>>())).thenReturn(listOf(unit))
        whenever(entrances.findAllById(any<Iterable<UUID>>())).thenReturn(listOf(entrance))
    }

    private fun MockHttpServletRequestBuilder.by(subject: String = "login-me") =
        header(HttpHeaders.AUTHORIZATION, "Bearer ${signedMine(issuer, subject)}")

    @Test
    fun `PM-ORG-011 a title that ended the day before is not listed and one in force is`() {
        given(title(me, flat, "2020-01-01", "2026-05-09"), title(me, flat, "2026-05-10", null, role = "USR"))
        mvc.perform(get(url).param("on", "2026-05-10").by()).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].titleRole").value("USR"))
        mvc.perform(get(url).param("on", "2026-05-08").by()).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].titleRole").value("OWN"))
    }

    @Test
    fun `PM-ORG-011 on the day of a sale the buyer lists the unit and the seller does not`() {
        val sale = "2026-05-14"
        val seller = title(neighbour, flat, "2020-01-01", sale)
        val buyer = title(me, flat, sale, null)
        given(buyer, others = listOf(seller))
        mvc.perform(get(url).param("on", sale).by()).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].unitId").value(flat.id.toString()))
        // the seller, asking the same day, holds nothing
        whenever(logins.partyOf(MINE_ISSUER, "login-seller")).thenReturn(neighbour)
        whenever(titles.findByPartyId(neighbour)).thenReturn(listOf(seller))
        mvc.perform(get(url).param("on", sale).by("login-seller")).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `PM-ORG-011 a title that starts after the date asked about is not listed`() {
        given(title(me, flat, "2026-05-15", null))
        mvc.perform(get(url).param("on", "2026-05-14").by()).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `PM-ORG-005 two co-owners each see their own share and the two halves sum to the unit's ideal parts`() {
        given(title(me, flat, "2020-01-01", null, "0.5"), others = listOf(title(neighbour, flat, "2020-01-01", null, "0.5")))
        whenever(logins.partyOf(MINE_ISSUER, "login-neighbour")).thenReturn(neighbour)
        fun parts(login: String): BigDecimal {
            val body = mvc.perform(get(url).param("on", "2026-05-14").by(login)).andExpect(status().isOk)
                .andExpect(jsonPath("$[0].share").value("0.5")).andReturn().response.contentAsString
            return BigDecimal(com.fasterxml.jackson.databind.ObjectMapper().readTree(body)[0]["idealParts"].asText())
        }
        assertThat(parts("login-me")).isEqualByComparingTo("6.25")
        assertThat(parts("login-me") + parts("login-neighbour")).isEqualByComparingTo(flat.idealPartsPct)
        mvc.perform(get(url).param("on", "2026-05-14").by()).andExpect(jsonPath("$[0].idealParts").value("6.25")).andExpect(jsonPath("$[0].idealPartsSource").value("DECLARED"))
    }

    @Test
    fun `PM-ORG-005 a unit held by two titles of one role is listed once with the shares summed`() {
        given(title(me, flat, "2020-01-01", null, "0.25"), title(me, flat, "2021-01-01", null, "0.25"))
        mvc.perform(get(url).param("on", "2026-05-14").by()).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].share").value("0.5")).andExpect(jsonPath("$[0].idealParts").value("6.25"))
    }

    @Test
    fun `PM-SEC-001 a signed-in login tied to no party gets an empty list and the books are not opened`() {
        whenever(logins.partyOf(MINE_ISSUER, "login-nobody")).thenReturn(null)
        mvc.perform(get(url).param("on", "2026-05-14").by("login-nobody")).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
        verifyNoInteractions(titles, units, entrances)
    }

    @Test
    fun `PM-SEC-001 with no token or one this api does not accept nothing is read - a 401`() {
        whenever(logins.partyOf(any(), any())).thenReturn(me)
        mvc.perform(get(url)).andExpect(status().isUnauthorized)
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer ${signedMine(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(), "login-me")}"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(titles, units, entrances)
    }

    @Test
    fun `PM-SEC-002 the answer holds only the asker's titles and no co-owner's id, name or share`() {
        given(title(me, flat, "2020-01-01", null, "0.5"), others = listOf(title(neighbour, flat, "2020-01-01", null, "0.5")))
        val body = mvc.perform(get(url).param("on", "2026-05-14").by()).andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(body).doesNotContain(neighbour.toString()).doesNotContain(me.toString()).doesNotContain("partyId")
        assertThat(com.fasterxml.jackson.databind.ObjectMapper().readTree(body).size()).isEqualTo(1)
        org.mockito.kotlin.verify(titles, org.mockito.kotlin.never()).findByPartyId(neighbour)
        org.mockito.kotlin.verify(titles, org.mockito.kotlin.never()).findByEntranceId(any())
    }

    @Test
    fun `PM-SEC-002 a party id or unit id passed as a parameter changes nothing`() {
        given(title(me, flat, "2020-01-01", null), others = listOf(title(neighbour, flat, "2020-01-01", null, role = "USR")))
        val other = UUID.randomUUID()
        mvc.perform(
            get(url).param("on", "2026-05-14").param("partyId", neighbour.toString()).param("party", neighbour.toString())
                .param("unitId", other.toString()).param("entranceId", other.toString()).by(),
        ).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].titleRole").value("OWN"))
        org.mockito.kotlin.verify(titles, org.mockito.kotlin.never()).findByPartyId(neighbour)
    }

    @Test
    fun `PM-BOOK-011 a row carries no identity number, address or name`() {
        given(title(me, flat, "2020-01-01", null))
        val body = mvc.perform(get(url).param("on", "2026-05-14").by()).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].entranceLabel").value("Вх. А")).andExpect(jsonPath("$[0].designation").value("ап. 5"))
            .andExpect(jsonPath("$[0].unitType").value("FLAT")).andReturn().response.contentAsString
        val keys = com.fasterxml.jackson.databind.ObjectMapper().readTree(body)[0].fieldNames().asSequence().toSet()
        assertThat(keys).containsExactlyInAnyOrder("entranceId", "entranceLabel", "unitId", "designation", "unitType", "titleRole", "share", "idealParts", "idealPartsSource")
    }

    @Test
    fun `PM-SYS-004 with no date given the read uses the Sofia calendar day, not the UTC one`() {
        // the clock stands at 22:30Z on 31 March = 01:30 on 1 April in Sofia; a title starting 1 April is listed
        given(title(me, flat, "2026-04-01", null))
        mvc.perform(get(url).by()).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `an on that is not a date is a 400`() {
        given(title(me, flat, "2020-01-01", null))
        mvc.perform(get(url).param("on", "14.05.2026").by()).andExpect(status().isBadRequest)
    }

    @Test
    fun `rows are sorted by entrance label, then designation`() {
        val second = PropertyUnit(UUID.randomUUID(), entrance.id, "ап. 10", "FLAT", null, BigDecimal("10.0000"), false)
        given(title(me, second, "2020-01-01", null), title(me, flat, "2020-01-01", null))
        whenever(units.findAllById(any<Iterable<UUID>>())).thenReturn(listOf(second, flat))
        mvc.perform(get(url).param("on", "2026-05-14").by()).andExpect(jsonPath("$[0].designation").value("ап. 10")).andExpect(jsonPath("$[1].designation").value("ап. 5"))
    }
}

/** Sign-in switched off: nobody is asking, so a 401 and nothing read (PM-SEC-001). */
@WebMvcTest(MyUnitsController::class)
@Import(WhoAsks::class, MyUnits::class)
class MyUnitsSignInOffWebTest {
    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var logins: Logins
    @MockitoBean lateinit var titles: TitleRepository
    @MockitoBean lateinit var units: PropertyUnitRepository
    @MockitoBean lateinit var entrances: EntranceRepository
    @MockitoBean lateinit var clock: Clock

    @Test
    fun `PM-SEC-001 nobody asking gets 401`() {
        mvc.perform(get("/api/registry/me/units")).andExpect(status().isUnauthorized)
        verifyNoInteractions(titles, units, entrances, logins)
    }
}

private fun signedMine(by: KeyPair, subject: String): String =
    SignedJWT(
        JWSHeader(JWSAlgorithm.RS256),
        JWTClaimsSet.Builder().subject(subject).issuer(MINE_ISSUER).audience(MINE_AUDIENCE).expirationTime(Date.from(Instant.now().plusSeconds(300))).build(),
    ).apply { sign(RSASSASigner(by.private)) }.serialize()
