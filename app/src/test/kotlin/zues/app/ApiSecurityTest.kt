package zues.app

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.law.LawController
import java.time.Instant

private const val ISSUER = "https://id.example.test/realms/domuvai"
private const val AUDIENCE = "domuvai-api"

private fun token(issuer: String = ISSUER, audience: List<String> = listOf(AUDIENCE), expiresIn: Long = 300): Jwt =
    Jwt.withTokenValue("t").header("alg", "RS256").subject("a-login").issuer(issuer).audience(audience)
        .issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().plusSeconds(expiresIn)).build()

/** Sign-in configured (ADR-011): the api lets in a request with a token its issuer signed for it, and no other. */
@WebMvcTest(LawController::class, properties = ["domuvai.auth.issuer-uri=$ISSUER", "domuvai.auth.audience=$AUDIENCE"])
class ApiSecurityTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var decoder: JwtDecoder          // stands in for the issuer's keys: what it accepts is a signed token

    private fun ask(bearer: String? = null) =
        mvc.perform(get("/api/law/version").apply { if (bearer != null) header(HttpHeaders.AUTHORIZATION, "Bearer $bearer") })

    @Test
    fun `with sign-in configured a request with no token, or one the issuer did not sign, is a 401 — and one it signed is let in`() {
        ask().andExpect(status().isUnauthorized)
        whenever(decoder.decode("forged")).thenThrow(BadJwtException("the signature does not match"))
        ask("forged").andExpect(status().isUnauthorized)
        whenever(decoder.decode("signed")).thenReturn(token())
        ask("signed").andExpect(status().isOk).andExpect(jsonPath("$.catalogueVersion").exists())
        mvc.perform(get("/api/law/version").header(HttpHeaders.AUTHORIZATION, "Basic bWU6cGFzcw==")).andExpect(status().isUnauthorized)   // no passwords here
    }

    @Test
    fun `a token is taken only from this issuer, in date, and issued for this api`() {
        val checks = ApiAuth(ISSUER, AUDIENCE).tokenValidator()
        assertThat(checks.validate(token()).hasErrors()).isFalse()
        assertThat(checks.validate(token(audience = listOf("account", AUDIENCE))).hasErrors()).isFalse()      // among others
        assertThat(checks.validate(token(audience = listOf("another-app"))).hasErrors()).isTrue()             // the same realm, another application
        assertThat(checks.validate(token(audience = emptyList())).hasErrors()).isTrue()
        assertThat(checks.validate(token(issuer = "https://id.example.test/realms/other")).hasErrors()).isTrue()
        assertThat(checks.validate(token(expiresIn = -120)).hasErrors()).isTrue()
    }

    @Test
    fun `an issuer with no audience, or an audience with no issuer, is refused at start`() {
        assertThatThrownBy { ApiAuth(ISSUER, "") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("DOMUVAI_AUTH_AUDIENCE")
        assertThatThrownBy { ApiAuth("", AUDIENCE) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("DOMUVAI_AUTH_ISSUER")
        assertThat(ApiAuth("", "").required).isFalse()
        assertThat(ApiAuth(ISSUER, AUDIENCE).required).isTrue()
    }
}

/** Sign-in not configured: the api answers as it did before there was any — this machine, and every other test. */
@WebMvcTest(LawController::class)
class ApiOpenTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `with no issuer configured a request needs no token`() {
        mvc.perform(get("/api/law/version")).andExpect(status().isOk)
        mvc.perform(get("/api/law/version").header(HttpHeaders.AUTHORIZATION, "Bearer anything")).andExpect(status().isOk)
    }
}
