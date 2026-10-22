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

private const val REALM = "https://id.example.test/realms/domuvai"
private const val API = "domuvai-api"

/**
 * Quorum and sittings with sign-in configured, the real policy and guard over a mocked service — no database.
 * Proves a sitting is weighed and opened by whoever runs the assembly, signed in, and by nobody else; the meter is read.
 */
@WebMvcTest(SittingController::class, properties = ["domuvai.auth.issuer-uri=$REALM", "domuvai.auth.audience=$API", "domuvai.auth.mode="])
@Import(WhoAsks::class, Acting::class, AssemblyReads::class, Policy::class, AssemblyActs::class)
class SittingWebTest {

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
    private val base = "/api/assembly/entrances/$entranceId/assemblies/$assemblyId"
    private val at = Instant.parse("2026-10-09T09:00:00Z")

    private fun person(subject: String, vararg roles: Role): UUID = UUID.randomUUID().also {
        whenever(logins.partyOf(REALM, subject)).thenReturn(it)
        held[it] = roles.toSet()
    }

    private fun MockHttpServletRequestBuilder.by(subject: String) = header(
        HttpHeaders.AUTHORIZATION,
        "Bearer " + SignedJWT(
            JWSHeader(JWSAlgorithm.RS256),
            JWTClaimsSet.Builder().subject(subject).issuer(REALM).audience(API).expirationTime(Date.from(Instant.now().plusSeconds(300))).build(),
        ).apply { sign(RSASSASigner(issuer.private)) }.serialize(),
    ).contentType(MediaType.APPLICATION_JSON)

    private fun convenedBy(convenor: UUID) = whenever(repository.findById(assemblyId)).thenReturn(
        java.util.Optional.of(
            Assembly(
                assemblyId, entranceId, convenor, "BM", Instant.parse("2026-10-09T08:00:00Z"), "фоайето", "HYBRID", "NOTICED", false, null,
                convenedLoginIssuer = REALM, convenedLoginSubject = "login-of-the-convenor",
            ),
        ),
    )

    private fun weighed(by: Actor, represented: String, met: Boolean) = Sitting(
        UUID.randomUUID(), entranceId, assemblyId, 1, at.takeIf { met }, BigDecimal(represented), met, at, LocalDate.parse("2026-10-09"),
        BigDecimal("51"), "GA_QUORUM_PCT@2009-01-01", true, "basis", "ab".repeat(32), "v1.3", "engine-1", by.party, by.loginIssuer!!, by.loginSubject!!,
    )

    @Test
    fun `PM-GA-012 a sitting is weighed and opened by whoever runs the assembly, signed in - and by no other office, owner or stranger`() {
        val manager = person("login-runner", Role.BM)
        person("login-controller", Role.CTL)
        person("login-owner", Role.OWN)
        person("login-stranger")
        convenedBy(manager)
        val runner = Actor(manager, REALM, "login-runner")
        whenever(sittings.open(entranceId, assemblyId, runner)).thenReturn(weighed(runner, "51.0000000000", true))

        for (subject in listOf("login-controller", "login-owner", "login-stranger")) {
            mvc.perform(post("$base/sittings").by(subject)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-GA-012"))
        }
        mvc.perform(post("$base/sittings").by("login-tied-to-nobody")).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        verifyNoInteractions(sittings)

        mvc.perform(post("$base/sittings").by("login-runner"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.sessionNo").value(1))
            .andExpect(jsonPath("$.quorumMet").value(true))
            .andExpect(jsonPath("$.representedPct").value("51"))
            .andExpect(jsonPath("$.thresholdPct").value("51"))
            .andExpect(jsonPath("$.thresholdConstant").value("GA_QUORUM_PCT@2009-01-01"))
            .andExpect(jsonPath("$.weighedBy").value(manager.toString()))
            .andExpect(jsonPath("$.basisHash").value("ab".repeat(32)))
        verify(sittings).open(entranceId, assemblyId, runner)
    }

    @Test
    fun `PM-GA-012 the meter is read by an owner - what is represented against the threshold and its source, and why it cannot be weighed`() {
        person("login-owner-2", Role.OWN)
        val quorum = Quorum(
            LocalDate.parse("2026-10-09"), BigDecimal("50.9900000000"), BigDecimal("51"), "чл. 15 ал. 1 ЗУЕС", true, "GA_QUORUM_PCT@2009-01-01",
            listOf("unit X has both an owner and a holder of a right of use"), true, "basis",
        )
        whenever(sittings.read(entranceId, assemblyId)).thenReturn(QuorumRead(quorum, emptyList(), 1, Instant.parse("2026-10-09T08:00:00Z")))
        mvc.perform(get("$base/quorum").by("login-owner-2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.representedPct").value("50.99"))
            .andExpect(jsonPath("$.thresholdPct").value("51"))
            .andExpect(jsonPath("$.thresholdSource").value("чл. 15 ал. 1 ЗУЕС"))
            .andExpect(jsonPath("$.thresholdVerified").value(true))
            .andExpect(jsonPath("$.met").value(false))
            .andExpect(jsonPath("$.cannotWeigh[0]").value("unit X has both an owner and a holder of a right of use"))
            .andExpect(jsonPath("$.derivedParts").value(true))
            .andExpect(jsonPath("$.sittings").isEmpty)
    }

    @Test
    fun `PM-SEC-002 who holds the share behind a higher quorum is named to the manager, the board and the controller - to an owner, a holder of use and the cashier only how much`() {
        val concentrated = UUID.randomUUID()
        val quorum = Quorum(
            LocalDate.parse("2026-10-09"), BigDecimal("80"), BigDecimal("75"), "чл. 15 ал. 4 ЗУЕС", true, "GA_QUORUM_CONCENTRATED_PCT@2009-01-01",
            emptyList(), false, "basis", concentratedBy = concentrated, concentratedPct = BigDecimal("60.5000000000"),
        )
        whenever(sittings.read(entranceId, assemblyId)).thenReturn(QuorumRead(quorum, emptyList(), 1, Instant.parse("2026-10-09T08:00:00Z")))
        for ((n, role) in listOf(Role.BM, Role.MB, Role.CTL).withIndex()) {
            person("login-office-$n", role)
            mvc.perform(get("$base/quorum").by("login-office-$n")).andExpect(status().isOk)
                .andExpect(jsonPath("$.concentratedBy").value(concentrated.toString())).andExpect(jsonPath("$.concentratedPct").value("60.5"))
        }
        for ((n, role) in listOf(Role.OWN, Role.USR, Role.CSH).withIndex()) {
            person("login-reader-$n", role)
            mvc.perform(get("$base/quorum").by("login-reader-$n")).andExpect(status().isOk)
                .andExpect(jsonPath("$.concentratedBy").doesNotExist()).andExpect(jsonPath("$.concentratedPct").value("60.5")).andExpect(jsonPath("$.thresholdPct").value("75"))
        }
    }

    @Test
    fun `PM-SEC-001 the meter is read by those holding a role in the entrance - a stranger and a login tied to nobody are a 403 naming the rule, and nothing is weighed`() {
        person("login-stranger-4")
        person("login-occupant-4", Role.OCC)
        for (subject in listOf("login-stranger-4", "login-occupant-4", "login-tied-to-nobody")) {
            mvc.perform(get("$base/quorum").by(subject)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        }
        verifyNoInteractions(sittings)
    }

    @Test
    fun `what the service refuses keeps its status, and no token is a 401`() {
        val manager = person("login-runner-3", Role.BM)
        convenedBy(manager)
        whenever(sittings.open(any(), any(), any())).thenThrow(QuorumNotWeighable("the quorum cannot be weighed"))
        mvc.perform(post("$base/sittings").by("login-runner-3")).andExpect(status().isConflict).andExpect(jsonPath("$.error").value("the quorum cannot be weighed"))
        whenever(sittings.read(any(), any())).thenThrow(NoSuchElementException("no assembly"))
        mvc.perform(get("$base/quorum").by("login-runner-3")).andExpect(status().isNotFound)
        mvc.perform(post("$base/sittings")).andExpect(status().isUnauthorized)
    }
}
