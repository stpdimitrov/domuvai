package zues.app

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.law.LawController
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date

private const val ISSUER = "https://id.example.test/realms/domuvai"
private const val AUDIENCE = "domuvai-api"

private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

/** A token as an issuer would mint it, signed with [by]. */
private fun signed(by: KeyPair, issuer: String = ISSUER, audience: List<String> = listOf(AUDIENCE), expiresIn: Long = 300): String =
    SignedJWT(
        JWSHeader(JWSAlgorithm.RS256),
        JWTClaimsSet.Builder().subject("a-login").issuer(issuer).audience(audience)
            .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(Instant.now().plusSeconds(expiresIn))).build(),
    ).apply { sign(RSASSASigner(by.private)) }.serialize()

/**
 * Sign-in configured (ADR-011): the api lets in a request with a token its issuer signed for it, and no other. The
 * test stands in for the issuer with a key of its own; every check on the token is the application's real one.
 */
@WebMvcTest(LawController::class, properties = ["domuvai.auth.issuer-uri=$ISSUER", "domuvai.auth.audience=$AUDIENCE", "domuvai.auth.mode="])
class ApiSecurityTest {

    companion object {
        val issuer: KeyPair = rsa()
    }

    @TestConfiguration
    class TheIssuersKeys {
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
    }

    @Autowired lateinit var mvc: MockMvc

    private fun ask(bearer: String? = null) =
        mvc.perform(get("/api/law/version").apply { if (bearer != null) header(HttpHeaders.AUTHORIZATION, "Bearer $bearer") })

    @Test
    fun `a request with a token the issuer signed for this api is let in, and one with no token is a 401`() {
        ask().andExpect(status().isUnauthorized)
        ask(signed(issuer)).andExpect(status().isOk).andExpect(jsonPath("$.catalogueVersion").exists())
        ask(signed(issuer, audience = listOf("account", AUDIENCE))).andExpect(status().isOk)                  // among other audiences
    }

    @Test
    fun `a token signed by someone else, for another application, from another issuer, out of date or mangled is a 401`() {
        ask(signed(rsa())).andExpect(status().isUnauthorized)                                                  // not the issuer's key
        ask(signed(issuer, audience = listOf("another-app"))).andExpect(status().isUnauthorized)               // the same realm, another application
        ask(signed(issuer, audience = emptyList())).andExpect(status().isUnauthorized)
        ask(signed(issuer, issuer = "https://id.example.test/realms/other")).andExpect(status().isUnauthorized)
        ask(signed(issuer, expiresIn = -120)).andExpect(status().isUnauthorized)
        ask("abc.def.ghi").andExpect(status().isUnauthorized)
        val good = signed(issuer)
        ask(good.dropLast(4) + "AAAA").andExpect(status().isUnauthorized)                                      // the signature no longer matches
    }

    @Test
    fun `there is no other way in — no password, no form, nothing to log out of`() {
        mvc.perform(get("/api/law/version").header(HttpHeaders.AUTHORIZATION, "Basic bWU6cGFzcw==")).andExpect(status().isUnauthorized)
        mvc.perform(post("/login").param("username", "me").param("password", "pass")).andExpect(status().isUnauthorized)
        mvc.perform(post("/logout")).andExpect(status().isUnauthorized)
        mvc.perform(get("/actuator")).andExpect(status().isUnauthorized)
        mvc.perform(get("/no-such-path")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `closed unless told otherwise — and a half-said configuration is refused at start`() {
        assertThat(ApiAuth(ISSUER, AUDIENCE, "").required).isTrue()
        assertThat(ApiAuth(" $ISSUER ", " $AUDIENCE ", " ").issuer).isEqualTo(ISSUER)
        assertThat(ApiAuth("", "", "off").required).isFalse()                                                  // open only in so many words
        assertThat(ApiAuth("http://localhost:8180/realms/domuvai", AUDIENCE, "").required).isTrue()            // http for this machine only
        for ((issuer, audience, mode, names) in listOf(
            listOf("", "", "", "DOMUVAI_AUTH=off"),                    // nothing said: it does not start open
            listOf("  ", " ", " ", "DOMUVAI_AUTH=off"),
            listOf(ISSUER, "", "", "DOMUVAI_AUTH_AUDIENCE"),
            listOf("", AUDIENCE, "off", "DOMUVAI_AUTH_ISSUER"),
            listOf(ISSUER, AUDIENCE, "off", "not both"),
            listOf("", "", "on", "the only value"),
            listOf("http://id.example.test/realms/domuvai", AUDIENCE, "", "https"),
            listOf("id.example.test", AUDIENCE, "", "https"),
        )) {
            assertThatThrownBy { ApiAuth(issuer, audience, mode) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(names)
        }
    }
}

/** Sign-in switched off in so many words: the api answers as it did before there was any — this machine, and every other test. */
@WebMvcTest(LawController::class)
class ApiOpenTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `with sign-in switched off a request needs no token`() {
        mvc.perform(get("/api/law/version")).andExpect(status().isOk)
        mvc.perform(get("/api/law/version").header(HttpHeaders.AUTHORIZATION, "Bearer anything")).andExpect(status().isOk)
    }
}
