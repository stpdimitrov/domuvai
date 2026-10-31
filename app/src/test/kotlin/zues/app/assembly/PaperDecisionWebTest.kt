package zues.app.assembly

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
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
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.IssuerKeys
import zues.app.identity_org.Administrators
import zues.app.identity_org.Logins
import zues.app.identity_org.WhoAsks
import zues.app.policy.Held
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import zues.kernel.toSofiaDate
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID

private const val PAPER_REALM = "https://id.example.test/realms/domuvai"
private const val PAPER_API = "domuvai-api"

/**
 * Recording a decision taken on paper over HTTP with sign-in configured: tokens the test signs are read by the
 * application's decoder, and the real policy and administrators decide. Each login's party holds the one role of its
 * name; only the tables are mocked. The write itself against the database is [PaperDecisionPersistenceIT]'s.
 */
@WebMvcTest(
    PaperDecisionController::class,
    properties = ["domuvai.auth.issuer-uri=$PAPER_REALM", "domuvai.auth.audience=$PAPER_API", "domuvai.auth.mode=", "domuvai.auth.admins=operator"],
)
@Import(WhoAsks::class, PaperDecisions::class, AssemblyReads::class, Policy::class, Administrators::class)
class PaperDecisionWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val parties: Map<Role, UUID> = Role.entries.associateWith { UUID.randomUUID() }
    }

    @TestConfiguration
    class Fixed {
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
        @Bean fun clock(): Clock = Clock.systemUTC()
        @Bean fun roles() = RoleSource { who, entrance, _ -> parties.filterValues { it == who.party }.keys.mapTo(mutableSetOf()) { Held(it, entrance, emptySet()) } }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var aggregates: JdbcAggregateTemplate
    @MockitoBean lateinit var stored: PaperDecisionRepository
    @MockitoBean lateinit var logins: Logins

    private val entrance = UUID.randomUUID()
    private val url = "/api/assembly/entrances/$entrance/paper-decisions"
    private val today = LocalDate.parse(toSofiaDate(Instant.now()))

    private fun body(date: Any? = "2026-03-14", text: Any? = "Месечна вноска за управление", ref: Any? = "Протокол № 3", location: Any? = "шкаф в офиса", custodian: Any? = "Иван Иванов"): String =
        listOf("decisionText" to text, "protocolDate" to date, "protocolRef" to ref, "archiveLocation" to location, "custodian" to custodian)
            .filter { it.second != null }.joinToString(",", "{", "}") { (field, value) -> "\"$field\":\"$value\"" }

    private fun token(subject: String): String =
        SignedJWT(
            JWSHeader(JWSAlgorithm.RS256),
            JWTClaimsSet.Builder().subject(subject).issuer(PAPER_REALM).audience(PAPER_API).expirationTime(Date.from(Instant.now().plusSeconds(300))).build(),
        ).apply { sign(RSASSASigner(issuer.private)) }.serialize()

    private fun send(subject: String?, content: String = body()) =
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(content).apply { if (subject != null) header(HttpHeaders.AUTHORIZATION, "Bearer ${token(subject)}") })

    private fun read(subject: String) = mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer ${token(subject)}"))

    private fun answering() = whenever(aggregates.insert(any<PaperDecision>())).thenAnswer { it.arguments[0] }

    private fun aRecord() = PaperDecision(UUID.randomUUID(), entrance, "вноска", LocalDate.parse("2026-03-14"), "Протокол № 3", "шкаф", "Иван Иванов", "basis", "ab".repeat(32), "law", "engine", PAPER_REALM, "operator", Instant.now())

    @Test
    fun `PM-SEC-001 the administrator records a decision taken on paper, and the record says who, from what, under which law`() {
        answering()
        send("operator").andExpect(status().isCreated).andExpect(jsonPath("$.id").isNotEmpty).andExpect(jsonPath("$.custodian").value("Иван Иванов"))
            .andExpect(jsonPath("$.protocolRef").value("Протокол № 3")).andExpect(jsonPath("$.basisHash").isNotEmpty)
        val row = argumentCaptor<PaperDecision>().apply { verify(aggregates).insert(capture()) }.firstValue
        assertThat(row.entranceId).isEqualTo(entrance)
        assertThat(row.loginIssuer to row.loginSubject).isEqualTo(PAPER_REALM to "operator")
        assertThat(row.basis).contains("Протокол № 3", "2026-03-14", "шкаф в офиса", "Иван Иванов", "Месечна вноска за управление")
        assertThat(row.basisHash).matches("[0-9a-f]{64}")
        assertThat(row.lawVersion).isNotBlank()
        assertThat(row.engineVersion).isNotBlank()
    }

    @Test
    fun `PM-SEC-001 every other role is a 403 naming the rule, a login of nobody too, whatever the body says, and nothing is written`() {
        for ((role, party) in parties) {
            if (role == Role.SYS_ADMIN) continue
            whenever(logins.partyOf(PAPER_REALM, "login-$role")).thenReturn(party)
            send("login-$role", """{"by":"operator","admin":true}""").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
            send("login-$role").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        }
        send("login-of-nobody").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        whenever(logins.partyOf(PAPER_REALM, "operator")).thenReturn(parties.getValue(Role.BM))   // a tied login does not administer
        send("operator").andExpect(status().isForbidden)
        verifyNoInteractions(aggregates)
    }

    @Test
    fun `PM-SEC-001 with no token nothing is asked at all - a 401`() {
        send(null).andExpect(status().isUnauthorized)
        verifyNoInteractions(aggregates)
    }

    @Test
    fun `PM-DOC-007 where the paper is kept and who keeps it are required, as is everything else the protocol gives`() {
        for (lacking in listOf(body(location = null), body(location = "   "), body(custodian = null), body(custodian = " "), body(ref = null), body(ref = ""), body(text = null), body(text = " \\n "), body(date = null), body(ref = "x".repeat(501)), body(text = "x".repeat(10_001))))
            send("operator", lacking).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").isNotEmpty)
        verifyNoInteractions(aggregates)
    }

    @Test
    fun `PM-GA-022 a protocol is of a day that has come - today in Sofia and no later - and records its archive location`() {
        answering()
        send("operator", body(date = today.plusDays(1).toString())).andExpect(status().isBadRequest)
        verifyNoInteractions(aggregates)
        send("operator", body(date = today.toString())).andExpect(status().isCreated).andExpect(jsonPath("$.archiveLocation").value("шкаф в офиса"))
    }

    @Test
    fun `PM-SEC-001 PM-SEC-002 whoever reads the assemblies lists the records, the custodian named only to a reader of the mandates`() {
        whenever(stored.findByEntranceIdOrderByProtocolDateDescRecordedAtDesc(entrance)).thenReturn(listOf(aRecord()))
        for ((role, party) in parties) whenever(logins.partyOf(PAPER_REALM, "login-$role")).thenReturn(party)
        for (role in listOf(Role.BM, Role.MB, Role.CTL))
            read("login-$role").andExpect(status().isOk).andExpect(jsonPath("$[0].custodian").value("Иван Иванов")).andExpect(jsonPath("$[0].archiveLocation").value("шкаф"))
        for (role in listOf(Role.OWN, Role.USR, Role.CSH))
            read("login-$role").andExpect(status().isOk).andExpect(jsonPath("$[0].protocolRef").value("Протокол № 3")).andExpect(jsonPath("$[0].custodian").doesNotExist())
                .andExpect(jsonPath("$[0].archiveLocation").value("шкаф")).andExpect(jsonPath("$[0].basis").doesNotExist())
        read("login-of-nobody").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
    }
}
