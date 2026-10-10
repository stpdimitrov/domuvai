package zues.app.registry

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.IssuerKeys
import zues.app.identity_org.Logins
import zues.app.identity_org.WhoAsks
import zues.app.policy.Policy
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID

private const val CLOSED_REALM = "https://id.example.test/realms/domuvai"

/**
 * The guard as the application wires it when sign-in is configured: the real service, lists, policy and resolver
 * behind the controllers — only the tables are mocked, and no role source exists, so nobody holds anything. A signed-in
 * person tied to a party is refused all three, each refusal entered, nothing drawn or anonymised: with an issuer set
 * the service does not take itself for open.
 */
@WebMvcTest(
    controllers = [OwnershipController::class, DeclarationController::class, BookController::class],
    properties = ["domuvai.auth.issuer-uri=$CLOSED_REALM", "domuvai.auth.audience=domuvai-api", "domuvai.auth.mode="],
)
@Import(WhoAsks::class, BookLists::class, BookAccessService::class, Policy::class)
class BookListsClosedTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @TestConfiguration
    class Fixed {
        @Bean fun clock(): Clock = Clock.systemUTC()
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var ownership: OwnershipService
    @MockitoBean lateinit var declarations: DeclarationService
    @MockitoBean lateinit var retention: BookRetentionService
    @MockitoBean lateinit var book: BookService
    @MockitoBean lateinit var entrances: EntranceRepository
    @MockitoBean lateinit var parties: PartyRepository
    @MockitoBean lateinit var log: BookAccessRepository
    @MockitoBean lateinit var aggregates: JdbcAggregateTemplate
    @MockitoBean lateinit var logins: Logins

    @Test
    fun `PM-BOOK-006 with sign-in configured the lists and the retention pass are refused to a signed-in person who holds no office — entered, and nothing drawn`() {
        val entrance = UUID.randomUUID()
        val party = UUID.randomUUID()
        whenever(logins.partyOf(CLOSED_REALM, "login-owner")).thenReturn(party)
        whenever(entrances.existsById(entrance)).thenReturn(true)
        val bearer = "Bearer " + SignedJWT(
            JWSHeader(JWSAlgorithm.RS256),
            JWTClaimsSet.Builder().subject("login-owner").issuer(CLOSED_REALM).audience("domuvai-api").expirationTime(Date.from(Instant.now().plusSeconds(300))).build(),
        ).apply { sign(RSASSASigner(issuer.private)) }.serialize()
        val base = "/api/registry/entrances/$entrance"
        for (request in listOf(get("$base/owners").param("on", "2026-05-01"), get("$base/book/declarations/overdue"), post("$base/book/retention")))
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden)
        verify(aggregates, times(3)).insert(argThat<BookAccessRow> { outcome == "REFUSED" && actor == party && loginSubject == "login-owner" })
        verifyNoInteractions(ownership, declarations, retention, book)
    }
}
