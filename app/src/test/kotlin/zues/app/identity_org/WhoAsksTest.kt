package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import zues.app.policy.Asking
import zues.app.policy.Login
import java.util.UUID

private const val ISSUER = "https://id.example.test/realms/domuvai"

/** Who is asking, for the policy: the token's login and the party the api's record ties it to — nothing the caller or a claim says. */
class WhoAsksTest {

    private val logins: Logins = mock()
    private val who = WhoAsks(logins)

    private fun token(issuer: String?, subject: String?, more: Map<String, Any> = emptyMap()) = JwtAuthenticationToken(
        Jwt.withTokenValue("t").header("alg", "RS256").claim("aud", "domuvai-api")
            .apply { if (issuer != null) claim("iss", issuer) }.apply { if (subject != null) subject(subject) }
            .apply { more.forEach { (name, value) -> claim(name, value) } }.build(),
    )

    @Test
    fun `the party is the one the login is tied to — and a claim naming another changes nothing`() {
        val ivan = UUID.randomUUID()
        whenever(logins.partyOf(ISSUER, "login-a")).thenReturn(ivan)
        val claims = mapOf("party_id" to UUID.randomUUID().toString(), "partyId" to UUID.randomUUID().toString(), "actor" to UUID.randomUUID().toString())
        assertThat(who.of(token(ISSUER, "login-a", claims))).isEqualTo(Asking(Login(ISSUER, "login-a"), ivan))
    }

    @Test
    fun `a login tied to no party is a login with no party, and the same subject from another issuer is another login`() {
        whenever(logins.partyOf(ISSUER, "login-a")).thenReturn(UUID.randomUUID())
        assertThat(who.of(token(ISSUER, "login-b"))).isEqualTo(Asking(Login(ISSUER, "login-b"), null))
        assertThat(who.of(token("$ISSUER-other", "login-a"))).isEqualTo(Asking(Login("$ISSUER-other", "login-a"), null))
    }

    @Test
    fun `no token, no issuer or no subject is nobody — and no record is looked up`() {
        for (nobody in listOf(null, token(null, "login-a"), token(ISSUER, null), token(" ", "login-a"), token(ISSUER, " ")))
            assertThat(who.of(nobody)).isEqualTo(Asking(null, null))
        verify(logins, never()).partyOf(any(), any())
    }
}
