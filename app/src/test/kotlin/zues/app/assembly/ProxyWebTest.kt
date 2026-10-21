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
 * The proxy endpoints with sign-in configured, the real policy and guard over a mocked service — no database. Tokens
 * are signed by a key of the test's own. Proves the principal the service is handed is the party the api's record ties
 * the login to — never a field of the request — and that whoever may not appoint gets a 403 and reaches nothing.
 */
@WebMvcTest(ProxyController::class, properties = ["domuvai.auth.issuer-uri=$ISSUER", "domuvai.auth.audience=$AUDIENCE", "domuvai.auth.mode="])
@Import(WhoAsks::class, Acting::class, Policy::class, AssemblyActs::class)
class ProxyWebTest {

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
    @MockitoBean lateinit var proxies: ProxyService
    @MockitoBean lateinit var sittings: SittingService
    @MockitoBean lateinit var attendance: AttendanceService
    @MockitoBean lateinit var assemblies: AssemblyService
    @MockitoBean lateinit var notices: NoticeService
    @MockitoBean lateinit var repository: AssemblyRepository
    @MockitoBean lateinit var logins: Logins

    private val assemblyId = UUID.randomUUID()
    private val agent = UUID.randomUUID()
    private val base = "/api/assembly/entrances/$entranceId/assemblies/$assemblyId/proxies"

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

    private val document = "5d".repeat(32)

    private fun appointing(principal: UUID? = null, hash: String? = null) =
        """{${principal?.let { "\"principalPartyId\":\"$it\"," }.orEmpty()}${hash?.let { "\"authorisationHash\":\"$it\"," }.orEmpty()}"agentPartyId":"$agent","agentKind":"THIRD_PARTY","scope":"LISTED_ITEMS","items":[2,3],"form":"NOTARISED"}"""

    /** The assembly these proxies are for, convened by [convenor] — as the manager unless said otherwise. */
    private fun convenedBy(convenor: UUID, convenedAs: String = "BM") = whenever(repository.findById(assemblyId)).thenReturn(
        java.util.Optional.of(
            Assembly(
                assemblyId, entranceId, convenor, convenedAs, Instant.parse("2026-11-20T17:00:00Z"), "фоайето", "IN_PERSON", "DRAFT", false, null,
                convenedLoginIssuer = ISSUER, convenedLoginSubject = "login-of-the-convenor",
            ),
        ),
    )

    private fun stored(principal: UUID, subject: String) = Proxy(
        UUID.randomUUID(), entranceId, assemblyId, principal, agent, "THIRD_PARTY", "LISTED_ITEMS", "2,3", "NOTARISED", Instant.parse("2026-10-09T09:00:00Z"), ISSUER, subject,
        principal, null,
    )

    @Test
    fun `PM-GA-009 a proxy is appointed by the signed-in owner or holder of use for themselves - in person, with no document`() {
        val ana = person("login-ana", Role.OWN)
        val boris = person("login-boris", Role.USR)
        for ((principal, subject) in listOf(ana to "login-ana", boris to "login-boris")) {
            val command = RegisterProxy(principal, agent, "THIRD_PARTY", "LISTED_ITEMS", listOf(2, 3), "NOTARISED")
            whenever(proxies.register(entranceId, assemblyId, command, Actor(principal, ISSUER, subject), null)).thenReturn(stored(principal, subject))
        }
        // naming herself, or nobody, Ana appoints her own
        mvc.perform(post(base).by("login-ana").content(appointing(principal = ana))).andExpect(status().isCreated)
        mvc.perform(post(base).by("login-ana").content(appointing()))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.principalPartyId").value(ana.toString()))
            .andExpect(jsonPath("$.recordedBy").value(ana.toString()))
            .andExpect(jsonPath("$.authorisationHash").doesNotExist())
            .andExpect(jsonPath("$.agentPartyId").value(agent.toString()))
            .andExpect(jsonPath("$.agentKind").value("THIRD_PARTY"))
            .andExpect(jsonPath("$.scope").value("LISTED_ITEMS"))
            .andExpect(jsonPath("$.items[1]").value(3))
            .andExpect(jsonPath("$.form").value("NOTARISED"))
        verify(proxies, times(2)).register(entranceId, assemblyId, RegisterProxy(ana, agent, "THIRD_PARTY", "LISTED_ITEMS", listOf(2, 3), "NOTARISED"), Actor(ana, ISSUER, "login-ana"), null)
        // a holder of a right of use appoints too: the rule says "an owner or user"
        mvc.perform(post(base).by("login-boris").content(appointing())).andExpect(status().isCreated).andExpect(jsonPath("$.principalPartyId").value(boris.toString()))
        // in person there is no document to give
        mvc.perform(post(base).by("login-ana").content(appointing(hash = document))).andExpect(status().isBadRequest)
    }

    @Test
    fun `PM-GA-009 whoever runs the assembly records a written authorisation for the principal it names, with its SHA-256 - and nobody else names a principal`() {
        val owner = person("login-owner-5", Role.OWN)
        val manager = person("login-manager-5", Role.BM)
        person("login-controller-5", Role.CTL)
        person("login-other-owner-5", Role.OWN)
        person("login-stranger-5")
        convenedBy(manager)
        val command = RegisterProxy(owner, agent, "THIRD_PARTY", "LISTED_ITEMS", listOf(2, 3), "NOTARISED")
        whenever(proxies.register(entranceId, assemblyId, command, Actor(manager, ISSUER, "login-manager-5"), document))
            .thenReturn(stored(owner, "login-manager-5").copy(recordedBy = manager, authorisationHash = document))

        // another office, another owner, a stranger and a login tied to no party do not record a proxy for the owner — document or not
        for (subject in listOf("login-controller-5", "login-other-owner-5", "login-stranger-5")) {
            mvc.perform(post(base).by(subject).content(appointing(principal = owner, hash = document)))
                .andExpect(status().isForbidden).andExpect(jsonPath("$.error").value("not allowed (PM-GA-009)"))      // the proxy's rule, whoever they are
        }
        mvc.perform(post(base).by("login-tied-to-nobody").content(appointing(principal = owner, hash = document))).andExpect(status().isForbidden)
        // the convenor without the document records nothing
        mvc.perform(post(base).by("login-manager-5").content(appointing(principal = owner))).andExpect(status().isBadRequest)
        verifyNoInteractions(proxies)

        mvc.perform(post(base).by("login-manager-5").content(appointing(principal = owner, hash = document)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.principalPartyId").value(owner.toString()))
            .andExpect(jsonPath("$.recordedBy").value(manager.toString()))
            .andExpect(jsonPath("$.authorisationHash").value(document))
        verify(proxies).register(entranceId, assemblyId, command, Actor(manager, ISSUER, "login-manager-5"), document)
        // nobody records an authorisation that names themself as the proxy
        mvc.perform(post(base).by("login-manager-5").content(appointing(principal = owner, hash = document).replace("$agent", "$manager"))).andExpect(status().isBadRequest)
        verifyNoMoreInteractions(proxies)
        // a manager who did not convene this assembly, while its convenor holds the office, records nothing either
        convenedBy(person("login-another-manager-5", Role.BM))
        mvc.perform(post(base).by("login-manager-5").content(appointing(principal = owner, hash = document))).andExpect(status().isForbidden)
    }

    @Test
    fun `PM-GA-009 the owner who convened on a petition records written proxies for that assembly - another owner, and the manager, do not`() {
        val convenor = person("login-convening-owner", Role.OWN)
        val principal = person("login-absent-owner", Role.OWN)
        person("login-manager-6", Role.BM)
        convenedBy(convenor, "OWNERS")
        val command = RegisterProxy(principal, agent, "THIRD_PARTY", "LISTED_ITEMS", listOf(2, 3), "NOTARISED")
        whenever(proxies.register(entranceId, assemblyId, command, Actor(convenor, ISSUER, "login-convening-owner"), document))
            .thenReturn(stored(principal, "login-convening-owner").copy(recordedBy = convenor, authorisationHash = document))
        for (subject in listOf("login-absent-owner", "login-manager-6")) {
            val named = if (subject == "login-absent-owner") convenor else principal
            mvc.perform(post(base).by(subject).content(appointing(principal = named, hash = document))).andExpect(status().isForbidden)
        }
        mvc.perform(post(base).by("login-convening-owner").content(appointing(principal = principal, hash = document)))
            .andExpect(status().isCreated).andExpect(jsonPath("$.recordedBy").value(convenor.toString()))
    }

    @Test
    fun `PM-ORG-005 a person on the register is present, and gives no proxy - in person or recorded for them`() {
        val owner = person("login-present-owner", Role.OWN)
        val manager = person("login-manager-7", Role.BM)
        convenedBy(manager)
        whenever(attendance.isPresent(assemblyId, owner)).thenReturn(true)
        mvc.perform(post(base).by("login-present-owner").content(appointing())).andExpect(status().isConflict)
        mvc.perform(post(base).by("login-manager-7").content(appointing(principal = owner, hash = document))).andExpect(status().isConflict)
        verifyNoInteractions(proxies)
    }

    @Test
    fun `PM-SEC-001 nobody without a title appoints - the manager for themselves, an occupant, a stranger and a login tied to no party are a 403`() {
        person("login-manager", Role.BM)
        person("login-occupant", Role.OCC)
        person("login-stranger")
        for (subject in listOf("login-manager", "login-occupant", "login-stranger")) {
            mvc.perform(post(base).by(subject).content(appointing())).andExpect(status().isForbidden).andExpect(jsonPath("$.error").value("not allowed (PM-GA-009)"))
        }
        mvc.perform(post(base).by("login-tied-to-nobody").content(appointing())).andExpect(status().isForbidden)
        // an owner of this entrance appoints nothing in another
        person("login-owner-2", Role.OWN)
        mvc.perform(post("/api/assembly/entrances/${UUID.randomUUID()}/assemblies/$assemblyId/proxies").by("login-owner-2").content(appointing())).andExpect(status().isForbidden)
        verifyNoInteractions(proxies)
    }

    @Test
    fun `PM-GA-010 a registration past the limit is a 409 that shows the limit`() {
        val owner = person("login-owner-3", Role.OWN)
        whenever(proxies.register(eq(entranceId), eq(assemblyId), any(), eq(Actor(owner, ISSUER, "login-owner-3")), eq(null))).thenThrow(ProxyLimitReached(3, "чл. 14 ЗУЕС"))
        mvc.perform(post(base).by("login-owner-3").content(appointing()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.limit").value(3))
            .andExpect(jsonPath("$.error").value("one person represents at most 3 principals at an assembly (чл. 14 ЗУЕС) — PM-GA-010"))
    }

    @Test
    fun `what the service refuses keeps its status, the list is read, and no token is a 401`() {
        val owner = person("login-owner-4", Role.OWN)
        whenever(proxies.register(any(), any(), any(), any(), eq(null))).thenThrow(IllegalArgumentException("unknown kind"))
        mvc.perform(post(base).by("login-owner-4").content(appointing())).andExpect(status().isBadRequest).andExpect(jsonPath("$.limit").doesNotExist())
        whenever(proxies.forAssembly(entranceId, assemblyId)).thenReturn(listOf(stored(owner, "login-owner-4").copy(scope = "WHOLE_AGENDA", scopeItems = null)))
        mvc.perform(get(base).by("login-owner-4")).andExpect(status().isOk).andExpect(jsonPath("$[0].items").isEmpty)
        whenever(proxies.forAssembly(any(), any())).thenThrow(NoSuchElementException("no assembly"))
        mvc.perform(get(base).by("login-owner-4")).andExpect(status().isNotFound)
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(appointing())).andExpect(status().isUnauthorized)
    }
}
