package zues.app.identity_org

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.IssuerKeys
import zues.app.policy.Policy
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

private const val READ_REALM = "https://id.example.test/realms/domuvai"

/**
 * An entrance's mandates over HTTP with sign-in configured: tokens the test signs are read by the application's
 * decoder, and the real policy, roles, standing and read decide — only the table of mandates and the record of ties are
 * mocked. Who may read is decided as at today in Sofia; what is read is the date asked for.
 */
@WebMvcTest(
    MandateController::class,
    properties = ["domuvai.auth.issuer-uri=$READ_REALM", "domuvai.auth.audience=domuvai-api", "domuvai.auth.mode=", "domuvai.auth.admins=operator"],
)
@Import(WhoAsks::class, MandateAdministration::class, MandateReading::class, MandateStanding::class, MandateRoles::class, Policy::class, Administrators::class)
class MandateReadWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @TestConfiguration
    class Fixed {
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
        // 00:30 on 1 June 2026 in Sofia; still 31 May in UTC
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC)
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var store: MandateStore
    @MockitoBean lateinit var acts: MandateActLog
    @MockitoBean lateinit var logins: Logins
    @MockitoBean lateinit var names: PartyNames         // the registry's; unanswered here, so the ids alone
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean lateinit var standing: MandateStanding     // the real one, watched

    private val entrance = UUID.randomUUID()
    private fun day(iso: String) = LocalDate.parse(iso)
    private val manager = UUID.randomUUID(); private val boardMember = UUID.randomUUID(); private val controller = UUID.randomUUID()
    private val cashier = UUID.randomUUID(); private val stranger = UUID.randomUUID(); private val nextDoor = UUID.randomUUID()

    // The manager's term ends on 1 June 2026 — today in Sofia — and nobody is elected: acting past term from today.
    private val managers = Mandate(UUID.randomUUID(), entrance, "BM", manager, day("2024-06-01"), day("2026-06-01"), null)
    // a board sits in another entrance: a member beside a manager would succeed them (a board replaces a sole manager)
    private val hall = UUID.randomUUID()
    private val members = Mandate(UUID.randomUUID(), hall, "MB", boardMember, day("2025-06-01"), day("2027-06-01"), null)
    private val controllers = Mandate(UUID.randomUUID(), entrance, "CTL", controller, day("2025-06-01"), day("2027-06-01"), null)
    private val cashiers = Mandate(UUID.randomUUID(), entrance, "CSH", cashier, day("2025-06-01"), day("2027-06-01"), null)
    private val elsewhere = Mandate(UUID.randomUUID(), UUID.randomUUID(), "BM", nextDoor, day("2025-01-01"), day("2027-01-01"), null)

    private fun bearer(subject: String, party: UUID?): String {
        whenever(logins.partyOf(READ_REALM, subject)).thenReturn(party)
        return "Bearer " + SignedJWT(
            JWSHeader(JWSAlgorithm.RS256),
            JWTClaimsSet.Builder().subject(subject).issuer(READ_REALM).audience("domuvai-api").expirationTime(Date.from(Instant.now().plusSeconds(300))).build(),
        ).apply { sign(RSASSASigner(issuer.private)) }.serialize()
    }

    private fun read(token: String?, on: String? = null, of: UUID = entrance) =
        mvc.perform(get("/api/identity/entrances/$of/mandates").apply { if (on != null) param("on", on) }.apply { if (token != null) header(HttpHeaders.AUTHORIZATION, token) })

    private fun theEntrance() {
        whenever(store.ofEntrance(entrance)).thenReturn(listOf(managers, controllers, cashiers))
        whenever(store.ofEntrance(hall)).thenReturn(listOf(members))
    }

    @Test
    fun `PM-GOV-004 the manager reads the entrance's mandates and how each stands — today the manager's own is acting past term, the controller's in term`() {
        theEntrance()
        whenever(store.ofEntrance(elsewhere.entranceId)).thenReturn(listOf(elsewhere))
        read(bearer("login-manager", manager)).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].partyId").value(manager.toString()))
            .andExpect(jsonPath("$[0].role").value("BM"))
            .andExpect(jsonPath("$[0].standing.kind").value("EXPIRED_ACTING"))
            .andExpect(jsonPath("$[0].standing.actingSince").value("2026-06-01"))
            .andExpect(jsonPath("$[0].standing.electBy").value("2026-06-01"))
            .andExpect(jsonPath("$[1].role").value("CTL"))
            .andExpect(jsonPath("$[1].standing.kind").value("IN_TERM"))
            .andExpect(jsonPath("$[1].standing.until").value("2027-06-01"))
            .andExpect(jsonPath("$[0].partyName").doesNotExist())
        // a day before, the election was due by today; the date asked for is the one read
        read(bearer("login-manager", manager), on = "2026-05-31").andExpect(status().isOk)
            .andExpect(jsonPath("$[0].standing.kind").value("ELECTION_DUE")).andExpect(jsonPath("$[0].standing.electBy").value("2026-06-01"))
    }

    @Test
    fun `PM-SEC-001 the board, the controller and the administrator read them too — and the cashier, a stranger, another entrance's manager and nobody do not, and nothing is read for them`() {
        theEntrance()
        whenever(store.ofEntrance(elsewhere.entranceId)).thenReturn(listOf(elsewhere))
        for ((subject, party) in listOf("login-controller" to controller, "operator" to null))
            read(bearer(subject, party)).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(3))
        read(bearer("login-board", boardMember), of = hall).andExpect(status().isOk).andExpect(jsonPath("$[0].role").value("MB"))
        read(bearer("login-board", boardMember)).andExpect(status().isForbidden)                       // a member of another entrance's board
        for ((subject, party) in listOf("login-cashier" to cashier, "login-stranger" to stranger, "login-next-door" to nextDoor, "login-of-nobody" to null))
            read(bearer(subject, party)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001")).andExpect(jsonPath("$[0]").doesNotExist())
        verify(store, never()).ofEntrance(elsewhere.entranceId)
    }

    @Test
    fun `PM-SEC-001 who may read is decided as at today — a manager whose mandate ended reads nothing, not even of the days of their own term`() {
        val former = Mandate(UUID.randomUUID(), entrance, "BM", stranger, day("2022-06-01"), day("2024-06-01"), day("2024-06-01"))
        whenever(store.ofEntrance(entrance)).thenReturn(listOf(former, managers))
        read(bearer("login-former", stranger), on = "2023-06-01").andExpect(status().isForbidden)
        read(bearer("login-manager", manager), on = "2023-06-01").andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].partyId").value(stranger.toString()))
    }

    @Test
    fun `PM-GOV-008 the controller reads the entrance's mandates — all of them, the manager's and the cashier's included`() {
        theEntrance()
        read(bearer("login-controller", controller)).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].role").value("BM")).andExpect(jsonPath("$[2].role").value("CSH"))
    }

    @Test
    fun `nothing is read for a caller the policy refuses — the decision comes first`() {
        theEntrance()
        read(bearer("login-stranger", stranger)).andExpect(status().isForbidden)
        read(bearer("login-stranger", stranger), of = UUID.randomUUID()).andExpect(status().isForbidden)   // registered or not, the same answer
        verify(standing, never()).of(any(), any())                                                // the policy asked who holds what; nothing was read for the caller
    }

    @Test
    fun `with no token, or one this api does not accept, nothing is asked — a 401, and a malformed date is a 400`() {
        read(null).andExpect(status().isUnauthorized)
        val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        read("Bearer " + SignedJWT(JWSHeader(JWSAlgorithm.RS256), JWTClaimsSet.Builder().subject("login-manager").issuer(READ_REALM).audience("domuvai-api")
            .expirationTime(Date.from(Instant.now().plusSeconds(300))).build()).apply { sign(RSASSASigner(stranger.private)) }.serialize()).andExpect(status().isUnauthorized)
        verify(store, never()).ofEntrance(any())
        for (notADay in listOf("01.06.2026", "+999999999-12-31", "0001-01-01")) read(bearer("login-manager", manager), on = notADay).andExpect(status().isBadRequest)
    }
}
