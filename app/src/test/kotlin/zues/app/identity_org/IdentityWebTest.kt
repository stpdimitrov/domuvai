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
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.IssuerKeys
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID

private const val ISSUER = "https://id.example.test/realms/domuvai"
private const val AUDIENCE = "domuvai-api"

/** A token as the issuer would mint it for [subject], with whatever else [more] puts in it. */
private fun signed(by: KeyPair, subject: String?, more: JWTClaimsSet.Builder.() -> Unit = {}): String =
    SignedJWT(
        JWSHeader(JWSAlgorithm.RS256),
        JWTClaimsSet.Builder().subject(subject).issuer(ISSUER).audience(AUDIENCE)
            .expirationTime(Date.from(Instant.now().plusSeconds(300))).apply(more).build(),
    ).apply { sign(RSASSASigner(by.private)) }.serialize()

/**
 * Who is asking, with sign-in configured (ADR-011): the token names the login, and the party comes from the api's own
 * record of that login — never from the caller, never from a claim. The test stands in for the issuer with a key of
 * its own; the application's real decoder reads the token.
 */
@WebMvcTest(IdentityController::class, properties = ["domuvai.auth.issuer-uri=$ISSUER", "domuvai.auth.audience=$AUDIENCE", "domuvai.auth.mode="])
class IdentityWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @TestConfiguration
    class TheIssuersKeys {
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var logins: Logins

    private fun me(bearer: String? = null) =
        mvc.perform(get("/api/identity/me").apply { if (bearer != null) header(HttpHeaders.AUTHORIZATION, "Bearer $bearer") })

    @Test
    fun `a login tied to a party is that party, and one tied to none is signed in with no party`() {
        val party = UUID.randomUUID()
        whenever(logins.partyOf(ISSUER, "login-a")).thenReturn(party)
        me(signed(issuer, "login-a")).andExpect(status().isOk)
            .andExpect(jsonPath("$.subject").value("login-a")).andExpect(jsonPath("$.partyId").value(party.toString()))
        me(signed(issuer, "login-b")).andExpect(status().isOk)
            .andExpect(jsonPath("$.subject").value("login-b")).andExpect(jsonPath("$.partyId").doesNotExist())
    }

    @Test
    fun `the party comes from the api's record of the login — a claim or a parameter naming another party changes nothing`() {
        val party = UUID.randomUUID()
        val another = UUID.randomUUID()
        whenever(logins.partyOf(ISSUER, "login-a")).thenReturn(party)
        val claiming = signed(issuer, "login-a") { claim("partyId", another.toString()).claim("party_id", another.toString()) }
        mvc.perform(get("/api/identity/me").param("partyId", another.toString()).param("subject", "login-b").header(HttpHeaders.AUTHORIZATION, "Bearer $claiming"))
            .andExpect(status().isOk).andExpect(jsonPath("$.subject").value("login-a")).andExpect(jsonPath("$.partyId").value(party.toString()))
    }

    @Test
    fun `with no token, or a token that names no login, nobody is asking — a 401 and no lookup`() {
        me().andExpect(status().isUnauthorized)
        me(signed(issuer, null)).andExpect(status().isUnauthorized).andExpect(content().string(""))
        me(signed(issuer, " ")).andExpect(status().isUnauthorized)
        verify(logins, never()).partyOf(any(), any())
    }
}

/** Sign-in switched off: every other endpoint answers anyone, and this one still answers nobody — there is no login to name. */
@WebMvcTest(IdentityController::class)
class IdentityOpenTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var logins: Logins

    @Test
    fun `with sign-in switched off nobody is asking — a 401, whatever the request carries`() {
        mvc.perform(get("/api/identity/me")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/identity/me").header(HttpHeaders.AUTHORIZATION, "Bearer anything")).andExpect(status().isUnauthorized)
        verify(logins, never()).partyOf(any(), any())
    }
}
