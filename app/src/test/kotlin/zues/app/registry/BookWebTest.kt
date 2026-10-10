package zues.app.registry

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.IssuerKeys
import zues.app.identity_org.Logins
import zues.app.identity_org.WhoAsks
import zues.app.policy.Asking
import zues.app.policy.Login
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

private const val ISSUER = "https://id.example.test/realms/domuvai"
private const val AUDIENCE = "domuvai-api"

/** A token as the issuer would mint it for [subject], with whatever else [more] puts in it. */
private fun signed(by: KeyPair, subject: String, more: JWTClaimsSet.Builder.() -> Unit = {}): String =
    SignedJWT(
        JWSHeader(JWSAlgorithm.RS256),
        JWTClaimsSet.Builder().subject(subject).issuer(ISSUER).audience(AUDIENCE)
            .expirationTime(Date.from(Instant.now().plusSeconds(300))).apply(more).build(),   // [more] may put another issuer, audience or expiry in their place
    ).apply { sign(RSASSASigner(by.private)) }.serialize()

/**
 * The book endpoints with sign-in configured and the services mocked — no database. The test stands in for the issuer
 * with a key of its own, so the application's decoder reads the tokens and the real [WhoAsks] says who is asking.
 * Proves the reader the service is handed is the party the api's record ties the token's login to — never a
 * parameter, never a claim (PM-BOOK-007) — that a reader the policy refuses gets a 403 (PM-BOOK-006), that with no
 * date the book is read as of today in Sofia, and that a malformed request is a 400.
 */
@WebMvcTest(BookController::class, properties = ["domuvai.auth.issuer-uri=$ISSUER", "domuvai.auth.audience=$AUDIENCE", "domuvai.auth.mode="])
@Import(WhoAsks::class)
class BookWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @TestConfiguration
    class Fixed {
        // 00:30 on 1 June in Sofia (UTC+3 in summer); still 31 May in UTC
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC)
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
    }

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var access: BookAccessService
    @MockitoBean lateinit var retention: BookRetentionService
    @MockitoBean lateinit var logins: Logins

    private val entranceId = UUID.randomUUID()
    private val url = "/api/registry/entrances/$entranceId/book"
    private val manager = UUID.randomUUID()
    private val theManager = Asking(Login(ISSUER, "login-manager"), manager)

    private fun MockHttpServletRequestBuilder.by(subject: String, more: JWTClaimsSet.Builder.() -> Unit = {}) =
        header(HttpHeaders.AUTHORIZATION, "Bearer ${signed(issuer, subject, more)}")

    private fun readBook() = get(url).param("purpose", "годишен отчет").by("login-manager")

    private fun aBook(on: LocalDate = LocalDate.parse("2026-06-01")) = CondominiumBook(
        entranceId, on,
        listOf(BookUnitEntry(UUID.randomUUID(), "ап. 1", "72.50", "60.0000", listOf(BookParty("Иван Петров", "OWN", "1")), 2, emptyList(), complete = true)),
        complete = true,
    )

    @Test
    fun `PM-BOOK-001 the book is read back as the electronic record, to the reader the sign-in names`() {
        whenever(logins.partyOf(ISSUER, "login-manager")).thenReturn(manager)
        whenever(access.read(eq(entranceId), any(), eq(theManager), eq("годишен отчет"))).thenReturn(BookAnswer.Served(aBook()))
        mvc.perform(readBook())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.complete").value(true))
            .andExpect(jsonPath("$.units[0].designation").value("ап. 1"))
            .andExpect(jsonPath("$.units[0].complete").value(true))
    }

    @Test
    fun `the reader is the party the api's record ties the login to — an actor parameter or a claim naming the manager changes nothing`() {
        whenever(logins.partyOf(ISSUER, "login-manager")).thenReturn(manager)
        val ownerA = UUID.randomUUID()
        whenever(logins.partyOf(ISSUER, "login-owner-a")).thenReturn(ownerA)
        val theOwner = Asking(Login(ISSUER, "login-owner-a"), ownerA)
        whenever(access.read(eq(entranceId), any(), eq(theManager), any())).thenReturn(BookAnswer.Served(aBook()))
        whenever(access.read(eq(entranceId), any(), eq(theOwner), any())).thenReturn(BookAnswer.Refused("PM-BOOK-006"))
        whenever(access.export(entranceId, theOwner, "проверка")).thenReturn(BookAnswer.Refused("PM-BOOK-006"))

        // owner A, saying in every way a caller can that they are the manager
        val pretending = get(url).param("purpose", "проверка").param("actor", manager.toString()).param("party", manager.toString()).param("partyId", manager.toString())
            .by("login-owner-a") { claim("actor", manager.toString()).claim("partyId", manager.toString()).claim("party_id", manager.toString()).claim("roles", listOf("BM")) }
        mvc.perform(pretending).andExpect(status().isForbidden)
        verify(access).read(eq(entranceId), any(), eq(theOwner), eq("проверка"))
        mvc.perform(get("$url/access-log").param("purpose", "проверка").param("actor", manager.toString()).by("login-owner-a")).andExpect(status().isForbidden)
        verify(access).export(entranceId, theOwner, "проверка")
    }

    @Test
    fun `PM-BOOK-006 a reader the policy refuses gets a 403 naming the rule, and nothing of the book`() {
        whenever(access.read(eq(entranceId), any(), any(), any())).thenReturn(BookAnswer.Refused("PM-BOOK-006"))
        mvc.perform(get(url).param("purpose", "проверка").by("login-of-nobody"))                // signed in, tied to no party
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.rule").value("PM-BOOK-006"))
            .andExpect(jsonPath("$.units").doesNotExist())
        verify(access).read(eq(entranceId), any(), eq(Asking(Login(ISSUER, "login-of-nobody"), null)), eq("проверка"))
    }

    @Test
    fun `with no token, or one this api does not accept, nothing is asked of the book at all — a 401`() {
        whenever(logins.partyOf(any(), any())).thenReturn(manager)                              // were it asked, it would be the manager
        fun refused(bearer: String?) {
            for (path in listOf(url, "$url/access-log"))
                mvc.perform(get(path).param("purpose", "проверка").param("actor", manager.toString()).apply { if (bearer != null) header(HttpHeaders.AUTHORIZATION, "Bearer $bearer") })
                    .andExpect(status().isUnauthorized)
        }
        val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        refused(null)
        refused(signed(stranger, "login-manager"))                                               // signed by somebody else
        refused(signed(issuer, "login-manager") { issuer("https://id.example.test/realms/other") })
        refused(signed(issuer, "login-manager") { audience("another-application") })
        refused(signed(issuer, "login-manager") { expirationTime(Date.from(Instant.now().minusSeconds(300))) })
        verifyNoInteractions(access)
    }

    @Test
    fun `PM-SYS-004 with no date given the book is read as of today in Sofia`() {
        whenever(access.read(eq(entranceId), any(), any(), any())).thenAnswer { BookAnswer.Served(CondominiumBook(entranceId, it.getArgument(1), emptyList(), complete = true)) }
        mvc.perform(readBook()).andExpect(status().isOk).andExpect(jsonPath("$.asOf").value("2026-06-01"))
    }

    @Test
    fun `PM-BOOK-010 POST retention anonymises what is due today in Sofia and says what it did`() {
        whenever(retention.anonymiseDue(entranceId, LocalDate.parse("2026-06-01"))).thenReturn(
            RetentionApplied(LocalDate.parse("2026-06-01"), householdUnlinked = 1, animalPassportsCleared = 2),
        )
        mvc.perform(post("$url/retention").param("on", "2030-01-01").by("login-manager"))       // ignored: no caller date
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.on").value("2026-06-01"))
            .andExpect(jsonPath("$.householdUnlinked").value(1))
            .andExpect(jsonPath("$.animalPassportsCleared").value(2))
    }

    @Test
    fun `a malformed on date is a 400`() {
        mvc.perform(readBook().param("on", "nonsense")).andExpect(status().isBadRequest)
    }

    @Test
    fun `PM-BOOK-007 the book is not served to a reader who may read and does not say why`() {
        // what the service refuses of such a reader — no purpose, a blank one — is a 400 with its reason; an entrance nobody registered a 404
        whenever(access.read(eq(entranceId), any(), any(), eq(""))).thenThrow(BookAccessRefused("a purpose is required"))
        whenever(access.export(eq(entranceId), any(), eq(""))).thenThrow(BookAccessRefused("a purpose is required"))
        mvc.perform(get(url).by("login-manager")).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("a purpose is required"))
        mvc.perform(get("$url/access-log").by("login-manager")).andExpect(status().isBadRequest)
        whenever(access.read(eq(entranceId), any(), any(), eq("x"))).thenThrow(NoSuchElementException("no entrance"))
        mvc.perform(get(url).param("purpose", "x").by("login-manager")).andExpect(status().isNotFound)
    }

    @Test
    fun `PM-BOOK-006 a refused reader who does not say why gets the refusal, not a word about the request`() {
        whenever(access.read(eq(entranceId), any(), any(), eq(""))).thenReturn(BookAnswer.Refused("PM-BOOK-006"))
        whenever(access.export(eq(entranceId), any(), eq(""))).thenReturn(BookAnswer.Refused("PM-BOOK-006"))
        mvc.perform(get(url).by("login-owner-a")).andExpect(status().isForbidden)
        mvc.perform(get("$url/access-log").by("login-owner-a")).andExpect(status().isForbidden)
    }

    @Test
    fun `PM-BOOK-007 the access log is exported with who read or was refused, why and when`() {
        whenever(logins.partyOf(ISSUER, "login-manager")).thenReturn(manager)
        whenever(access.export(entranceId, theManager, "проверка на КЗЛД")).thenReturn(
            BookAnswer.Served(
                listOf(
                    BookAccessView(UUID.randomUUID(), manager, "Мария Иванова", "годишен отчет", "BOOK_READ", LocalDate.parse("2026-06-01"), Instant.parse("2026-05-31T21:30:00Z"), "SERVED", "PM-BOOK-006", ISSUER, "login-manager"),
                    BookAccessView(UUID.randomUUID(), null, null, "любопитство", "BOOK_READ", LocalDate.parse("2026-06-01"), Instant.parse("2026-05-31T21:31:00Z"), "REFUSED", "PM-BOOK-006", ISSUER, "login-of-nobody"),
                ),
            ),
        )
        mvc.perform(get("$url/access-log").param("purpose", "проверка на КЗЛД").by("login-manager"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].actorName").value("Мария Иванова"))
            .andExpect(jsonPath("$[0].kind").value("BOOK_READ"))
            .andExpect(jsonPath("$[0].bookDate").value("2026-06-01"))
            .andExpect(jsonPath("$[0].at").value("2026-05-31T21:30:00Z"))
            .andExpect(jsonPath("$[0].outcome").value("SERVED"))
            .andExpect(jsonPath("$[1].outcome").value("REFUSED"))
            .andExpect(jsonPath("$[1].ruleId").value("PM-BOOK-006"))
            .andExpect(jsonPath("$[1].loginSubject").value("login-of-nobody"))
            .andExpect(jsonPath("$[1].loginIssuer").value(ISSUER))
            .andExpect(jsonPath("$[1].actor").doesNotExist())
    }
}

/** Sign-in switched off: there is no token, so nobody is asking — the service is handed nobody, and what it refuses is a 403. */
@WebMvcTest(BookController::class)
@Import(WhoAsks::class)
class BookOpenTest {

    @TestConfiguration
    class Fixed {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC)
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var access: BookAccessService
    @MockitoBean lateinit var retention: BookRetentionService
    @MockitoBean lateinit var logins: Logins

    @Test
    fun `PM-BOOK-006 with sign-in switched off nobody is asking, whatever the request says — the book is refused, a 403`() {
        val entranceId = UUID.randomUUID()
        val manager = UUID.randomUUID()
        whenever(access.read(eq(entranceId), any(), eq(Asking(null, null)), any())).thenReturn(BookAnswer.Refused("PM-BOOK-006"))
        mvc.perform(
            get("/api/registry/entrances/$entranceId/book").param("purpose", "проверка").param("actor", manager.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer anything"),
        ).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-BOOK-006"))
        verify(access).read(eq(entranceId), any(), eq(Asking(null, null)), eq("проверка"))
        verifyNoInteractions(logins)
    }
}
