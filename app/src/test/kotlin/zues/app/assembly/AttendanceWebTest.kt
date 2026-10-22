package zues.app.assembly

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
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
import zues.app.identity_org.Succession
import zues.app.identity_org.WhoAsks
import zues.app.policy.Held
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

private const val ISSUER = "https://id.example.test/realms/domuvai"
private const val AUDIENCE = "domuvai-api"

/**
 * The register's endpoints with sign-in configured, the real policy and guard over a mocked service — no database.
 * Proves the register is kept by whoever runs the assembly, signed in, and that anybody else gets a 403 and reaches nothing.
 */
@WebMvcTest(AttendanceController::class, properties = ["domuvai.auth.issuer-uri=$ISSUER", "domuvai.auth.audience=$AUDIENCE", "domuvai.auth.mode="])
@Import(WhoAsks::class, Acting::class, AssemblyReads::class, Policy::class, AssemblyActs::class)
class AttendanceWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val entranceId: UUID = UUID.randomUUID()
        val held = mutableMapOf<UUID, Set<Role>>()
    }

    @TestConfiguration
    class Fixed {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-09T09:00:00Z"), ZoneOffset.UTC)
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
        @Bean fun roles() = RoleSource { who, entrance, _ ->
            if (entrance != entranceId) emptySet() else held[who.party].orEmpty().mapTo(mutableSetOf()) { Held(it, entrance, setOf(UUID.randomUUID())) }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var attendance: AttendanceService
    @MockitoBean lateinit var proxies: ProxyService
    @MockitoBean lateinit var sittings: SittingService
    @MockitoBean lateinit var assemblies: AssemblyService
    @MockitoBean lateinit var notices: NoticeService
    @MockitoBean lateinit var repository: AssemblyRepository
    @MockitoBean lateinit var logins: Logins
    @MockitoBean lateinit var succession: Succession

    private val assemblyId = UUID.randomUUID()
    private val base = "/api/assembly/entrances/$entranceId/assemblies/$assemblyId/attendance"
    private val at = Instant.parse("2026-10-09T09:00:00Z")

    private fun person(subject: String, vararg roles: Role): UUID = UUID.randomUUID().also {
        whenever(logins.partyOf(ISSUER, subject)).thenReturn(it)
        held[it] = roles.toSet()
    }

    private fun MockHttpServletRequestBuilder.by(subject: String) = header(
        HttpHeaders.AUTHORIZATION,
        "Bearer " + SignedJWT(
            JWSHeader(JWSAlgorithm.RS256),
            JWTClaimsSet.Builder().subject(subject).issuer(ISSUER).audience(AUDIENCE).expirationTime(Date.from(Instant.now().plusSeconds(300))).build(),
        ).apply { sign(RSASSASigner(issuer.private)) }.serialize(),
    ).contentType(MediaType.APPLICATION_JSON)

    private fun convenedBy(convenor: UUID) = whenever(repository.findById(assemblyId)).thenReturn(
        java.util.Optional.of(
            Assembly(
                assemblyId, entranceId, convenor, "BM", Instant.parse("2026-10-09T08:00:00Z"), "фоайето", "HYBRID", "NOTICED", false, null,
                convenedLoginIssuer = ISSUER, convenedLoginSubject = "login-of-the-convenor",
            ),
        ),
    )

    private fun line(attendee: UUID, keeper: UUID, channel: String, verified: Boolean, brings: Set<UUID>) =
        Present(Attendance(UUID.randomUUID(), entranceId, assemblyId, 1, attendee, channel, keeper, ISSUER, "login-keeper", at), at.takeIf { verified }, brings)

    @Test
    fun `PM-GA-012 the register is kept by whoever runs the assembly, signed in - and by no other office, owner or stranger`() {
        val manager = person("login-keeper", Role.BM)
        person("login-controller", Role.CTL)
        person("login-owner", Role.OWN)
        person("login-stranger")
        convenedBy(manager)
        val attendee = UUID.randomUUID()
        val principal = UUID.randomUUID()
        val keeper = Actor(manager, ISSUER, "login-keeper")
        whenever(attendance.record(entranceId, assemblyId, keeper, attendee, "IN_PERSON")).thenReturn(line(attendee, manager, "IN_PERSON", false, setOf(attendee, principal)))
        val body = """{"attendeePartyId":"$attendee","channel":"IN_PERSON","recordedBy":"${UUID.randomUUID()}"}"""

        for (subject in listOf("login-controller", "login-owner", "login-stranger")) {
            mvc.perform(post(base).by(subject).content(body)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-GA-012"))
        }
        mvc.perform(post(base).by("login-tied-to-nobody").content(body)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        verifyNoInteractions(attendance)

        mvc.perform(post(base).by("login-keeper").content(body))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.attendeePartyId").value(attendee.toString()))
            .andExpect(jsonPath("$.recordedBy").value(manager.toString()))             // the one signed in, whatever the body named
            .andExpect(jsonPath("$.counted").value(true))
            .andExpect(jsonPath("$.represents.length()").value(2))
        verify(attendance).record(entranceId, assemblyId, keeper, attendee, "IN_PERSON")
    }

    @Test
    fun `PM-GA-016 a remote attendee reads as not counted until whoever runs the assembly records the verification - nobody else verifies`() {
        val manager = person("login-keeper-2", Role.BM)
        person("login-owner-2", Role.OWN)
        convenedBy(manager)
        val attendee = UUID.randomUUID()
        val keeper = Actor(manager, ISSUER, "login-keeper-2")
        val unverified = line(attendee, manager, "VIDEO", false, setOf(attendee))
        whenever(attendance.record(entranceId, assemblyId, keeper, attendee, "VIDEO")).thenReturn(unverified)
        mvc.perform(post(base).by("login-keeper-2").content("""{"attendeePartyId":"$attendee","channel":"VIDEO"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.identityVerified").value(false))
            .andExpect(jsonPath("$.counted").value(false))
            .andExpect(jsonPath("$.represents").isEmpty)

        val id = unverified.attendance.id
        val how = """{"how":"лична карта пред камерата"}"""
        mvc.perform(post("$base/$id/identity-verification").by("login-owner-2").content(how)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-GA-012"))
        whenever(attendance.verifyIdentity(entranceId, assemblyId, id, keeper, "лична карта пред камерата")).thenReturn(unverified.copy(identityVerifiedAt = at))
        mvc.perform(post("$base/$id/identity-verification").by("login-keeper-2").content(how))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.counted").value(true))
            .andExpect(jsonPath("$.represents[0]").value(attendee.toString()))
        verify(attendance).verifyIdentity(entranceId, assemblyId, id, keeper, "лична карта пред камерата")
    }

    @Test
    fun `PM-SEC-001 the register is read by those holding a role in the entrance - a stranger, an occupant and a login tied to nobody are a 403 naming the rule, and nothing is read`() {
        person("login-stranger-5")
        person("login-occupant-5", Role.OCC)
        for (subject in listOf("login-stranger-5", "login-occupant-5", "login-tied-to-nobody")) {
            mvc.perform(get(base).by(subject)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        }
        verifyNoInteractions(attendance)
        person("login-owner-5", Role.OWN)
        whenever(attendance.register(entranceId, assemblyId)).thenReturn(emptyList())
        mvc.perform(get(base).by("login-owner-5")).andExpect(status().isOk)
    }

    @Test
    fun `what the service refuses keeps its status, the register is read, and no token is a 401`() {
        val manager = person("login-keeper-3", Role.BM)
        convenedBy(manager)
        val body = """{"attendeePartyId":"${UUID.randomUUID()}","channel":"IN_PERSON"}"""
        whenever(attendance.record(any(), any(), any(), any(), any())).thenThrow(IllegalStateException("already on the register"))
        mvc.perform(post(base).by("login-keeper-3").content(body)).andExpect(status().isConflict)
        whenever(attendance.register(entranceId, assemblyId)).thenReturn(emptyList())
        mvc.perform(get(base).by("login-keeper-3")).andExpect(status().isOk)
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized)
    }
}
