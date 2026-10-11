package zues.app.assembly

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
@Import(WhoAsks::class, Acting::class, Policy::class)
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

    private fun appointing(principal: UUID? = null) =
        """{${principal?.let { "\"principalPartyId\":\"$it\"," }.orEmpty()}"agentPartyId":"$agent","agentKind":"THIRD_PARTY","scope":"LISTED_ITEMS","items":[2,3],"form":"NOTARISED"}"""

    private fun stored(principal: UUID, subject: String) = Proxy(
        UUID.randomUUID(), entranceId, assemblyId, principal, agent, "THIRD_PARTY", "LISTED_ITEMS", "2,3", "NOTARISED", Instant.parse("2026-10-09T09:00:00Z"), ISSUER, subject,
    )

    @Test
    fun `PM-GA-009 a proxy is appointed by the signed-in owner or holder of use for themselves - the body cannot name another principal`() {
        val ana = person("login-ana", Role.OWN)
        val boris = person("login-boris", Role.USR)
        for ((principal, subject) in listOf(ana to "login-ana", boris to "login-boris")) {
            val command = RegisterProxy(principal, agent, "THIRD_PARTY", "LISTED_ITEMS", listOf(2, 3), "NOTARISED")
            whenever(proxies.register(entranceId, assemblyId, command, Actor(principal, ISSUER, subject))).thenReturn(stored(principal, subject))
        }
        // Ana appoints, naming Boris as the principal: the proxy is hers
        mvc.perform(post(base).by("login-ana").content(appointing(principal = boris)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.principalPartyId").value(ana.toString()))
            .andExpect(jsonPath("$.agentPartyId").value(agent.toString()))
            .andExpect(jsonPath("$.agentKind").value("THIRD_PARTY"))
            .andExpect(jsonPath("$.scope").value("LISTED_ITEMS"))
            .andExpect(jsonPath("$.items[1]").value(3))
            .andExpect(jsonPath("$.form").value("NOTARISED"))
        verify(proxies).register(entranceId, assemblyId, RegisterProxy(ana, agent, "THIRD_PARTY", "LISTED_ITEMS", listOf(2, 3), "NOTARISED"), Actor(ana, ISSUER, "login-ana"))
        // a holder of a right of use appoints too: the rule says "an owner or user"
        mvc.perform(post(base).by("login-boris").content(appointing())).andExpect(status().isCreated).andExpect(jsonPath("$.principalPartyId").value(boris.toString()))
    }

    @Test
    fun `PM-SEC-001 nobody appoints a proxy for somebody else - the manager, the controller, an occupant, a stranger and a login tied to no party are a 403`() {
        val owner = person("login-owner-2", Role.OWN)
        person("login-manager", Role.BM)
        person("login-controller", Role.CTL)
        person("login-occupant", Role.OCC)
        person("login-stranger")
        for (subject in listOf("login-manager", "login-controller", "login-occupant", "login-stranger")) {
            mvc.perform(post(base).by(subject).content(appointing(principal = owner))).andExpect(status().isForbidden).andExpect(jsonPath("$.error").value("not allowed (PM-GA-009) — PM-GA-009"))
        }
        mvc.perform(post(base).by("login-tied-to-nobody").content(appointing(principal = owner))).andExpect(status().isForbidden)
        // an owner of this entrance appoints nothing in another
        mvc.perform(post("/api/assembly/entrances/${UUID.randomUUID()}/assemblies/$assemblyId/proxies").by("login-owner-2").content(appointing())).andExpect(status().isForbidden)
        verifyNoInteractions(proxies)
    }

    @Test
    fun `PM-GA-010 a registration past the limit is a 409 that shows the limit`() {
        val owner = person("login-owner-3", Role.OWN)
        whenever(proxies.register(eq(entranceId), eq(assemblyId), any(), eq(Actor(owner, ISSUER, "login-owner-3")))).thenThrow(ProxyLimitReached(3, "чл. 14 ЗУЕС"))
        mvc.perform(post(base).by("login-owner-3").content(appointing()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.limit").value(3))
            .andExpect(jsonPath("$.error").value("one person represents at most 3 principals at an assembly (чл. 14 ЗУЕС) — PM-GA-010"))
    }

    @Test
    fun `what the service refuses keeps its status, the list is read, and no token is a 401`() {
        val owner = person("login-owner-4", Role.OWN)
        whenever(proxies.register(any(), any(), any(), any())).thenThrow(IllegalArgumentException("unknown kind"))
        mvc.perform(post(base).by("login-owner-4").content(appointing())).andExpect(status().isBadRequest).andExpect(jsonPath("$.limit").doesNotExist())
        whenever(proxies.forAssembly(entranceId, assemblyId)).thenReturn(listOf(stored(owner, "login-owner-4").copy(scope = "WHOLE_AGENDA", scopeItems = null)))
        mvc.perform(get(base).by("login-owner-4")).andExpect(status().isOk).andExpect(jsonPath("$[0].items").isEmpty)
        whenever(proxies.forAssembly(any(), any())).thenThrow(NoSuchElementException("no assembly"))
        mvc.perform(get(base).by("login-owner-4")).andExpect(status().isNotFound)
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(appointing())).andExpect(status().isUnauthorized)
    }
}
