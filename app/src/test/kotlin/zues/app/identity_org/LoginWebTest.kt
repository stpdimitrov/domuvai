package zues.app.identity_org

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.IssuerKeys
import zues.app.policy.Asking
import zues.app.policy.Login
import zues.app.policy.Policy
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID

private const val REALM = "https://id.example.test/realms/domuvai"
private const val API = "domuvai-api"

private fun token(by: KeyPair, subject: String, more: JWTClaimsSet.Builder.() -> Unit = {}): String =
    SignedJWT(
        JWSHeader(JWSAlgorithm.RS256),
        JWTClaimsSet.Builder().subject(subject).issuer(REALM).audience(API).expirationTime(Date.from(Instant.now().plusSeconds(300))).apply(more).build(),
    ).apply { sign(RSASSASigner(by.private)) }.serialize()

/**
 * Tying a login over HTTP with sign-in configured: tokens the test signs are read by the application's decoder, and
 * the real policy, administrators and service decide — only the tables are mocked. The administrator is the login of
 * the token and nothing the request says (PM-SEC-001).
 */
@WebMvcTest(
    LoginController::class,
    properties = ["domuvai.auth.issuer-uri=$REALM", "domuvai.auth.audience=$API", "domuvai.auth.mode=", "domuvai.auth.admins=operator"],
)
@Import(WhoAsks::class, LoginAdministration::class, Policy::class, Administrators::class)
class LoginWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @TestConfiguration
    class Fixed {
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
        @Bean fun clock(): Clock = Clock.systemUTC()
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var logins: Logins
    @MockitoBean lateinit var acts: LoginActLog

    private val ivan = UUID.randomUUID()

    private fun tie(bearer: String?, body: String = """{"subject":"login-ivan","partyId":"$ivan"}""", path: String = "/api/identity/logins") =
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body).apply { if (bearer != null) header(HttpHeaders.AUTHORIZATION, "Bearer $bearer") })

    @Test
    fun `PM-SEC-001 the administrator ties and unties a login, and is told what was done`() {
        whenever(logins.untie(REALM, "login-ivan")).thenReturn(ivan)
        tie(token(issuer, "operator")).andExpect(status().isOk)
            .andExpect(jsonPath("$.issuer").value(REALM)).andExpect(jsonPath("$.subject").value("login-ivan")).andExpect(jsonPath("$.partyId").value(ivan.toString()))
        verify(logins).tie(REALM, "login-ivan", ivan)
        verify(acts).record("TIED", REALM, "login-ivan", ivan, Asking(Login(REALM, "operator"), null), "PM-SEC-001")
        tie(token(issuer, "operator"), """{"subject":"login-ivan"}""", "/api/identity/logins/untie").andExpect(status().isOk)
            .andExpect(jsonPath("$.untied").value(true)).andExpect(jsonPath("$.partyId").value(ivan.toString()))
        tie(token(issuer, "operator"), """{"subject":"login-nobody"}""", "/api/identity/logins/untie").andExpect(status().isOk).andExpect(jsonPath("$.untied").value(false))
    }

    @Test
    fun `PM-SEC-001 anybody else is a 403 naming the rule — though the request says in every way it can that it is the administrator's`() {
        whenever(logins.partyOf(REALM, "login-owner")).thenReturn(UUID.randomUUID())
        val pretending = token(issuer, "login-owner") {
            claim("roles", listOf("SYS_ADMIN")).claim("realm_access", mapOf("roles" to listOf("SYS_ADMIN", "admin"))).claim("preferred_username", "operator").claim("admin", true)
        }
        // an owner tying their own login to another party, and untying somebody's
        tie(pretending, """{"subject":"login-owner","partyId":"$ivan","by":"operator","admin":true}""").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        tie(pretending, """{"subject":"operator"}""", "/api/identity/logins/untie?by=operator").andExpect(status().isForbidden)
        tie(token(issuer, "login-of-nobody")).andExpect(status().isForbidden)
        tie(token(issuer, "login-of-nobody"), "{}").andExpect(status().isForbidden)               // nothing said of what the request lacks
        tie(token(issuer, "login-of-nobody"), "{}", "/api/identity/logins/untie").andExpect(status().isForbidden)
        verify(logins, never()).tie(any(), any(), any())
        verify(logins, never()).untie(any(), any())
        verify(acts).record(eq("TIE_REFUSED"), eq(REALM), eq("login-owner"), eq(ivan), any(), eq("PM-SEC-001"))
        verify(acts, never()).record(eq("TIED"), any(), any(), anyOrNull(), any(), any())
    }

    @Test
    fun `with no token, or one this api does not accept, nothing is asked at all — a 401`() {
        val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        tie(null).andExpect(status().isUnauthorized)
        tie(token(stranger, "operator")).andExpect(status().isUnauthorized)                       // the administrator's subject, signed by somebody else
        tie(token(issuer, "operator") { issuer("https://id.example.test/realms/other") }).andExpect(status().isUnauthorized)
        tie(token(issuer, "operator") { audience("another-application") }).andExpect(status().isUnauthorized)
        tie(token(issuer, "operator") { expirationTime(Date.from(Instant.now().minusSeconds(300))) }).andExpect(status().isUnauthorized)
        verifyNoInteractions(logins, acts)
    }

    @Test
    fun `what cannot be done is said to the administrator — a login tied already is a 409, a party nobody registered a 400`() {
        whenever(logins.tie(REALM, "login-ivan", ivan)).thenThrow(LoginAlreadyTied("this login is already tied"))
        tie(token(issuer, "operator")).andExpect(status().isConflict)
        whenever(logins.tie(eq(REALM), eq("login-b"), any())).thenThrow(IllegalArgumentException("no party is registered"))
        tie(token(issuer, "operator"), """{"subject":"login-b","partyId":"${UUID.randomUUID()}"}""").andExpect(status().isBadRequest)
        tie(token(issuer, "operator"), """{"subject":" ","partyId":"$ivan"}""").andExpect(status().isBadRequest)
        tie(token(issuer, "operator"), """{"subject":"login-c"}""").andExpect(status().isBadRequest)
        tie(token(issuer, "operator"), """{"subject":"operator","partyId":"$ivan"}""").andExpect(status().isBadRequest)   // the administrator's own login
        verifyNoInteractions(acts)
    }
}

/** Sign-in switched off: there is no login, so there is no administrator — whatever is configured or sent. */
@WebMvcTest(LoginController::class)
@Import(WhoAsks::class, LoginAdministration::class, Policy::class, Administrators::class)
class LoginOpenTest {

    @TestConfiguration
    class Fixed {
        @Bean fun clock(): Clock = Clock.systemUTC()
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var logins: Logins
    @MockitoBean lateinit var acts: LoginActLog

    @Test
    fun `PM-SEC-001 with sign-in switched off nobody is the administrator — a 403, nothing is tied and nothing written`() {
        val body = """{"subject":"operator","partyId":"${UUID.randomUUID()}"}"""
        mvc.perform(post("/api/identity/logins").contentType(MediaType.APPLICATION_JSON).content(body).header(HttpHeaders.AUTHORIZATION, "Bearer anything"))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/identity/logins/untie").contentType(MediaType.APPLICATION_JSON).content("""{"subject":"operator"}""")).andExpect(status().isForbidden)
        verifyNoInteractions(logins, acts)                                                       // nobody is signed in: nobody to enter
    }
}
