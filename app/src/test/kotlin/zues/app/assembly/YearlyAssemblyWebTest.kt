package zues.app.assembly

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
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

private const val REALM = "https://id.example.test/realms/domuvai"
private const val API = "domuvai-api"

/** The yearly read with sign-in configured, the real policy over a mocked service — no database. It is read as an assembly is. */
@WebMvcTest(YearlyAssemblyController::class, properties = ["domuvai.auth.issuer-uri=$REALM", "domuvai.auth.audience=$API", "domuvai.auth.mode="])
@Import(WhoAsks::class, AssemblyReads::class, Policy::class)
class YearlyAssemblyWebTest {

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
    @MockitoBean lateinit var yearly: YearlyAssembly
    @MockitoBean lateinit var logins: Logins

    private val base = "/api/assembly/entrances/$entranceId/years/2026"

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
    )

    @Test
    fun `PM-SEC-001 whether a year's assembly was held is read by those holding a role in the entrance - a stranger, an occupant and a login tied to nobody are a 403 naming the rule`() {
        person("login-stranger")
        person("login-occupant", Role.OCC)
        for (subject in listOf("login-stranger", "login-occupant", "login-tied-to-nobody")) {
            mvc.perform(get(base).by(subject)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        }
        mvc.perform(get("/api/assembly/entrances/$entranceId/years/0").by("login-stranger")).andExpect(status().isForbidden)    // decided before the year is looked at
        verifyNoInteractions(yearly)

        person("login-owner", Role.OWN)
        whenever(yearly.heldIn(entranceId, 2026)).thenReturn(HeldInYear(entranceId, 2026, 0, 1, "чл. 12 ЗУЕС", null, null))
        mvc.perform(get(base).by("login-owner")).andExpect(status().isOk).andExpect(jsonPath("$.met").value(false))
        mvc.perform(get(base)).andExpect(status().isUnauthorized)
    }
}
