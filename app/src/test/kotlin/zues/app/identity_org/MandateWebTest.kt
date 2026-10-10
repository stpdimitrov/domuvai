package zues.app.identity_org

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
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
import zues.app.policy.Policy
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID

private const val MANDATE_REALM = "https://id.example.test/realms/domuvai"
private const val MANDATE_API = "domuvai-api"

private fun bearer(by: KeyPair, subject: String, more: JWTClaimsSet.Builder.() -> Unit = {}): String =
    SignedJWT(
        JWSHeader(JWSAlgorithm.RS256),
        JWTClaimsSet.Builder().subject(subject).issuer(MANDATE_REALM).audience(MANDATE_API).expirationTime(Date.from(Instant.now().plusSeconds(300))).apply(more).build(),
    ).apply { sign(RSASSASigner(by.private)) }.serialize()

/**
 * Recording a mandate over HTTP with sign-in configured: tokens the test signs are read by the application's decoder,
 * and the real policy, administrators and service decide — only the tables are mocked. The administrator is the login
 * of the token and nothing the request says (PM-SEC-001).
 */
@WebMvcTest(
    MandateController::class,
    properties = ["domuvai.auth.issuer-uri=$MANDATE_REALM", "domuvai.auth.audience=$MANDATE_API", "domuvai.auth.mode=", "domuvai.auth.admins=operator"],
)
@Import(WhoAsks::class, MandateAdministration::class, Policy::class, Administrators::class)
class MandateWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @TestConfiguration
    class Fixed {
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
        @Bean fun clock(): Clock = Clock.systemUTC()
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var store: MandateStore
    @MockitoBean lateinit var acts: MandateActLog
    @MockitoBean lateinit var logins: Logins

    private val entrance = UUID.randomUUID()
    private val maria = UUID.randomUUID()
    private val url = "/api/identity/entrances/$entrance/mandates"
    private val aMandate = """{"body":"BM","partyId":"$maria","validFrom":"2026-05-10","validTo":"2028-05-10","protocolRef":"Протокол № 3"}"""

    private fun send(token: String?, body: String = aMandate, path: String = url) =
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body).apply { if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token") })

    @Test
    fun `PM-SEC-001 the administrator records a mandate and its end, and is told what was done`() {
        val old = UUID.randomUUID()
        whenever(store.open(entrance, listOf("BM", "MB"), LocalDate.parse("2026-05-10"), false)).thenReturn(listOf(old))
        whenever(store.end(old, LocalDate.parse("2026-05-10"))).thenReturn(true)
        send(bearer(issuer, "operator")).andExpect(status().isOk).andExpect(jsonPath("$.mandateId").isNotEmpty).andExpect(jsonPath("$.succeeded[0]").value(old.toString()))
        verify(store).add(any(), org.mockito.kotlin.eq(entrance), org.mockito.kotlin.eq("BM"), org.mockito.kotlin.eq(maria), any(), any(), org.mockito.kotlin.eq("Протокол № 3"))
        val mandate = UUID.randomUUID()
        whenever(store.find(entrance, mandate)).thenReturn(LocalDate.parse("2024-05-10") to null)
        whenever(store.end(mandate, LocalDate.parse("2026-03-01"))).thenReturn(true)
        send(bearer(issuer, "operator"), """{"on":"2026-03-01","protocolRef":"Протокол № 4"}""", "$url/$mandate/end").andExpect(status().isOk).andExpect(jsonPath("$.on").value("2026-03-01"))
    }

    @Test
    fun `PM-SEC-001 anybody else is a 403 naming the rule — though the request says in every way it can that it is the administrator's`() {
        whenever(logins.partyOf(MANDATE_REALM, "login-owner")).thenReturn(maria)                // an owner giving herself the manager's mandate
        val pretending = bearer(issuer, "login-owner") {
            claim("roles", listOf("SYS_ADMIN")).claim("realm_access", mapOf("roles" to listOf("SYS_ADMIN", "admin"))).claim("preferred_username", "operator").claim("admin", true)
        }
        send(pretending, aMandate.replace("}", ""","by":"operator","admin":true}"""), "$url?by=operator").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        send(pretending, "{}").andExpect(status().isForbidden)                                    // nothing said of what the request lacks
        send(pretending, """{"on":"2026-03-01","protocolRef":"Протокол № 4"}""", "$url/${UUID.randomUUID()}/end").andExpect(status().isForbidden)
        send(bearer(issuer, "login-of-nobody")).andExpect(status().isForbidden)
        verifyNoInteractions(store)
        verify(acts).record(argThat { act == "RECORD_REFUSED" && by.subject == "login-owner" && partyId == maria && entranceId == entrance })
        verify(acts, never()).record(argThat { act == "RECORDED" || act == "ENDED" })
    }

    @Test
    fun `with no token, or one this api does not accept, nothing is asked at all — a 401`() {
        val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        send(null).andExpect(status().isUnauthorized)
        send(bearer(stranger, "operator")).andExpect(status().isUnauthorized)
        send(bearer(issuer, "operator") { issuer("https://id.example.test/realms/other") }).andExpect(status().isUnauthorized)
        send(bearer(issuer, "operator") { audience("another-application") }).andExpect(status().isUnauthorized)
        send(bearer(issuer, "operator") { expirationTime(Date.from(Instant.now().minusSeconds(300))) }).andExpect(status().isUnauthorized)
        verifyNoInteractions(store, acts)
    }

    @Test
    fun `what cannot be done is said to the administrator — something lacking a 400, a mandate ended already a 409, one the entrance has not a 404`() {
        send(bearer(issuer, "operator"), """{"body":"BM","partyId":"$maria","validFrom":"2026-05-10","validTo":"2028-05-10"}""").andExpect(status().isBadRequest)
        send(bearer(issuer, "operator"), """{"body":"PMC","partyId":"$maria","validFrom":"2026-05-10","validTo":"2028-05-10","protocolRef":"x"}""").andExpect(status().isBadRequest)
        val mandate = UUID.randomUUID()
        send(bearer(issuer, "operator"), """{"on":"2026-03-01","protocolRef":"Протокол № 4"}""", "$url/$mandate/end").andExpect(status().isNotFound)
        whenever(store.find(entrance, mandate)).thenReturn(LocalDate.parse("2024-05-10") to LocalDate.parse("2026-01-01"))
        send(bearer(issuer, "operator"), """{"on":"2026-03-01","protocolRef":"Протокол № 4"}""", "$url/$mandate/end").andExpect(status().isConflict)
        verifyNoInteractions(acts)
    }
}

/** Sign-in switched off: there is no login, so there is no administrator — whatever is sent. */
@WebMvcTest(MandateController::class)
@Import(WhoAsks::class, MandateAdministration::class, Policy::class, Administrators::class)
class MandateOpenTest {

    @TestConfiguration
    class Fixed {
        @Bean fun clock(): Clock = Clock.systemUTC()
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var store: MandateStore
    @MockitoBean lateinit var acts: MandateActLog
    @MockitoBean lateinit var logins: Logins

    @Test
    fun `PM-SEC-001 with sign-in switched off nobody is the administrator — a 403, nothing is recorded and nothing written`() {
        val url = "/api/identity/entrances/${UUID.randomUUID()}/mandates"
        val body = """{"body":"BM","partyId":"${UUID.randomUUID()}","validFrom":"2026-05-10","validTo":"2028-05-10","protocolRef":"x"}"""
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body).header(HttpHeaders.AUTHORIZATION, "Bearer anything")).andExpect(status().isForbidden)
        mvc.perform(post("$url/${UUID.randomUUID()}/end").contentType(MediaType.APPLICATION_JSON).content("""{"on":"2026-03-01","protocolRef":"Протокол № 4"}""")).andExpect(status().isForbidden)
        verifyNoInteractions(store, acts)
    }
}
