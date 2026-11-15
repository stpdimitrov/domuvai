package zues.app.registry

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
import zues.app.policy.Asking
import zues.app.policy.Login
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

private const val LISTS_REALM = "https://id.example.test/realms/domuvai"
private const val LISTS_API = "domuvai-api"

private fun signedFor(by: KeyPair, subject: String, more: JWTClaimsSet.Builder.() -> Unit = {}): String =
    SignedJWT(
        JWSHeader(JWSAlgorithm.RS256),
        JWTClaimsSet.Builder().subject(subject).issuer(LISTS_REALM).audience(LISTS_API).expirationTime(Date.from(Instant.now().plusSeconds(300))).apply(more).build(),
    ).apply { sign(RSASSASigner(by.private)) }.serialize()

/**
 * The owners list, the overdue list and the retention pass over HTTP with sign-in configured: tokens the test signs
 * are read by the application's decoder and the real [WhoAsks] says who is asking; what they are given is mocked.
 * Proves the caller handed on is the login's party — never a parameter or a claim — and that a refusal is a 403.
 */
@WebMvcTest(
    controllers = [OwnershipController::class, DeclarationController::class, BookController::class],
    properties = ["domuvai.auth.issuer-uri=$LISTS_REALM", "domuvai.auth.audience=$LISTS_API", "domuvai.auth.mode="],
)
@Import(WhoAsks::class)
class BookListsWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @TestConfiguration
    class Fixed {
        // 00:30 on 1 June in Sofia (UTC+3 in summer); still 31 May in UTC
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC)
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var lists: BookLists
    @MockitoBean lateinit var access: BookAccessService
    @MockitoBean lateinit var ownership: OwnershipService
    @MockitoBean lateinit var declarations: DeclarationService
    @MockitoBean lateinit var declarationFiling: zues.app.registry.DeclarationFiling
    @MockitoBean lateinit var bookEntries: BookEntries
    @MockitoBean lateinit var registrations: Registrations
    @MockitoBean lateinit var logins: Logins

    private val entrance = UUID.randomUUID()
    private val base = "/api/registry/entrances/$entrance"
    private val manager = UUID.randomUUID()
    private val theManager = Asking(Login(LISTS_REALM, "login-manager"), manager)
    private val ownerA = UUID.randomUUID()
    private val theOwner = Asking(Login(LISTS_REALM, "login-owner-a"), ownerA)
    private val sofiaToday = LocalDate.parse("2026-06-01")

    private fun MockHttpServletRequestBuilder.by(subject: String, more: JWTClaimsSet.Builder.() -> Unit = {}) =
        header(HttpHeaders.AUTHORIZATION, "Bearer ${signedFor(issuer, subject, more)}")

    private fun requests(): List<MockHttpServletRequestBuilder> = listOf(
        get("$base/owners").param("on", "2026-05-01"), get("$base/book/declarations/overdue"), post("$base/book/retention"),
    )

    @Test
    fun `PM-BOOK-006 the manager is given the owners and the overdue declarations and runs the retention pass — as the party the sign-in names`() {
        whenever(logins.partyOf(LISTS_REALM, "login-manager")).thenReturn(manager)
        whenever(lists.owners(entrance, LocalDate.parse("2026-05-01"), theManager)).thenReturn(BookAnswer.Served(listOf(OwnerView(UUID.randomUUID(), UUID.randomUUID(), "Иван Петров", "OWN", BigDecimal.ONE))))
        whenever(lists.overdue(entrance, sofiaToday, theManager)).thenReturn(
            BookAnswer.Served(listOf(OverdueDeclaration(UUID.randomUUID(), "ап. 1", "Мария Георгиева", "OWN", LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-18")))),
        )
        whenever(lists.anonymiseDue(entrance, sofiaToday, theManager)).thenReturn(BookAnswer.Served(RetentionApplied(sofiaToday, 1, 2)))
        mvc.perform(get("$base/owners").param("on", "2026-05-01").by("login-manager")).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].partyName").value("Иван Петров")).andExpect(jsonPath("$[0].idValue").doesNotExist())
        mvc.perform(get("$base/book/declarations/overdue").by("login-manager")).andExpect(status().isOk).andExpect(jsonPath("$[0].partyName").value("Мария Георгиева"))
        mvc.perform(post("$base/book/retention").by("login-manager")).andExpect(status().isOk).andExpect(jsonPath("$.householdUnlinked").value(1))
    }

    @Test
    fun `PM-BOOK-006 a caller the policy refuses gets a 403 naming the rule from each of the three — though the request says in every way it can that it is the manager's`() {
        whenever(logins.partyOf(LISTS_REALM, "login-owner-a")).thenReturn(ownerA)
        whenever(lists.owners(any(), any(), eq(theOwner))).thenReturn(BookAnswer.Refused("PM-BOOK-006"))
        whenever(lists.overdue(any(), any(), eq(theOwner))).thenReturn(BookAnswer.Refused("PM-BOOK-006"))
        whenever(lists.anonymiseDue(any(), any(), eq(theOwner))).thenReturn(BookAnswer.Refused("PM-BOOK-010"))
        for ((request, rule) in requests().zip(listOf("PM-BOOK-006", "PM-BOOK-006", "PM-BOOK-010"))) {
            val pretending = request.param("actor", manager.toString()).param("partyId", manager.toString())
                .by("login-owner-a") { claim("actor", manager.toString()).claim("partyId", manager.toString()).claim("roles", listOf("BM")) }
            mvc.perform(pretending).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value(rule)).andExpect(jsonPath("$[0]").doesNotExist())
        }
        verify(lists).owners(entrance, LocalDate.parse("2026-05-01"), theOwner)
        verify(lists).overdue(entrance, sofiaToday, theOwner)
        verify(lists).anonymiseDue(entrance, sofiaToday, theOwner)                                 // today in Sofia, whatever the request says
        verifyNoInteractions(ownership, declarations)                                              // the lists are not drawn past the guard
    }

    @Test
    fun `with no token, or one this api does not accept, nothing is asked at all — a 401`() {
        val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val tokens = listOf(
            null, signedFor(stranger, "login-manager"), signedFor(issuer, "login-manager") { issuer("https://id.example.test/realms/other") },
            signedFor(issuer, "login-manager") { audience("another-application") }, signedFor(issuer, "login-manager") { expirationTime(Date.from(Instant.now().minusSeconds(300))) },
        )
        for (token in tokens) for (request in requests())
            mvc.perform(request.apply { if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token") }).andExpect(status().isUnauthorized)
        verifyNoInteractions(lists, logins)
    }

    @Test
    fun `a malformed date is a 400 whoever asks`() {
        mvc.perform(get("$base/owners").param("on", "01.05.2026").by("login-owner-a")).andExpect(status().isBadRequest)
        mvc.perform(get("$base/book/declarations/overdue").param("on", "16.06.2026").by("login-owner-a")).andExpect(status().isBadRequest)
        verifyNoInteractions(lists)
    }
}
