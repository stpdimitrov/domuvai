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
@Import(WhoAsks::class, PaperDecisions::class, VoteRequestKeys::class, AssemblyReads::class, Policy::class, Administrators::class)
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
    @MockitoBean lateinit var kept: VoteRequestKeyRepository
    @MockitoBean lateinit var readLog: PaperDecisionReadRepository

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

    /** Each record request is sent under an `Idempotency-Key` (PM-SYS-014) unless [key] is null. */
    private fun send(subject: String?, content: String = body(), key: String? = UUID.randomUUID().toString()) =
        mvc.perform(
            post(url).contentType(MediaType.APPLICATION_JSON).content(content)
                .apply { if (subject != null) header(HttpHeaders.AUTHORIZATION, "Bearer ${token(subject)}") }.apply { if (key != null) header("Idempotency-Key", key) },
        )

    private fun read(subject: String) = mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer ${token(subject)}"))

    private fun readLog(subject: String) = mvc.perform(get("$url/reads").header(HttpHeaders.AUTHORIZATION, "Bearer ${token(subject)}"))

    private fun answering() = whenever(aggregates.insert(any<PaperDecision>())).thenAnswer { it.arguments[0] }

    private fun aRecord() = PaperDecision(UUID.randomUUID(), entrance, "вноска", LocalDate.parse("2026-03-14"), "Протокол № 3", "шкаф", "Иван Иванов", "basis", "ab".repeat(32), "law", "engine", PAPER_REALM, "operator", Instant.now())

    @Test
    fun `PM-SEC-001 the administrator records a decision taken on paper, and the record says who, from what, under which law`() {
        answering()
        send("operator").andExpect(status().isCreated).andExpect(jsonPath("$.id").isNotEmpty).andExpect(jsonPath("$.custodian").value("Иван Иванов"))
            .andExpect(jsonPath("$.protocolRef").value("Протокол № 3")).andExpect(jsonPath("$.basisHash").isNotEmpty)
        val written = argumentCaptor<Any>().apply { verify(aggregates, times(2)).insert(capture()) }.allValues   // the key, then the record
        val row = written.filterIsInstance<PaperDecision>().single()
        assertThat(row.requestKeyId).isEqualTo(written.filterIsInstance<VoteRequestKeyRow>().single().id)
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

    @Test
    fun `PM-SEC-004 a mandate reader's read of the custodian is entered SERVED, with the party, the login and the policy's rule`() {
        whenever(stored.findByEntranceIdOrderByProtocolDateDescRecordedAtDesc(entrance)).thenReturn(listOf(aRecord()))
        whenever(logins.partyOf(PAPER_REALM, "login-BM")).thenReturn(parties.getValue(Role.BM))
        read("login-BM").andExpect(status().isOk).andExpect(jsonPath("$[0].custodian").value("Иван Иванов"))
        val entry = argumentCaptor<PaperDecisionRead>().apply { verify(aggregates).insert(capture()) }.firstValue
        assertThat(entry.entranceId).isEqualTo(entrance)
        assertThat(entry.actor).isEqualTo(parties.getValue(Role.BM))
        assertThat(entry.loginIssuer to entry.loginSubject).isEqualTo(PAPER_REALM to "login-BM")
        assertThat(entry.outcome).isEqualTo("SERVED")
        assertThat(entry.ruleId).matches("PM-[A-Z]+-[0-9]{3}")
    }

    @Test
    fun `PM-SEC-002 an owner's read withholds the custodian and enters nothing, as does a refused read`() {
        whenever(stored.findByEntranceIdOrderByProtocolDateDescRecordedAtDesc(entrance)).thenReturn(listOf(aRecord()))
        whenever(logins.partyOf(PAPER_REALM, "login-OWN")).thenReturn(parties.getValue(Role.OWN))
        read("login-OWN").andExpect(status().isOk).andExpect(jsonPath("$[0].custodian").doesNotExist())
        read("login-of-nobody").andExpect(status().isForbidden)
        whenever(stored.findByEntranceIdOrderByProtocolDateDescRecordedAtDesc(entrance)).thenReturn(emptyList())
        whenever(logins.partyOf(PAPER_REALM, "login-BM")).thenReturn(parties.getValue(Role.BM))
        read("login-BM").andExpect(status().isOk).andExpect(jsonPath("$").isEmpty)   // nothing recorded: no name served, none entered
        verifyNoInteractions(aggregates)
    }

    @Test
    fun `PM-SYS-014 a paper decision without an Idempotency-Key is a 400 and nothing is written, a blank one is a 400 once the policy has been asked`() {
        send("operator", key = null).andExpect(status().isBadRequest)
        send("operator", key = " ").andExpect(status().isBadRequest).andExpect(jsonPath("$.error").isNotEmpty)
        send("login-of-nobody", key = " ").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))   // asked before the key is looked at
        verifyNoInteractions(aggregates)
    }

    @Test
    fun `PM-SYS-014 a key used for another operation is a 409 and nothing is written`() {
        val key = UUID.randomUUID().toString()
        whenever(kept.findByEntranceIdAndIdempotencyKey(entrance, key)).thenReturn(VoteRequestKeyRow(UUID.randomUUID(), entrance, key, VoteRequestKeys.RECORD_VOTE, "ab".repeat(32), Instant.now()))
        send("operator", key = key).andExpect(status().isConflict).andExpect(jsonPath("$.error").value("Idempotency-Key $key was used for another request (PM-SYS-014)"))
        verifyNoInteractions(aggregates)
    }

    @Test
    fun `PM-SEC-001 PM-GOV-008 the manager, the board and the controller each read the log of reads, their own read entered in it`() {
        val earlier = PaperDecisionRead(UUID.randomUUID(), entrance, parties.getValue(Role.BM), PAPER_REALM, "login-BM", Instant.parse("2026-11-01T10:00:00Z"), "SERVED", "PM-SEC-001", "CUSTODIAN")
        val log = mutableListOf(earlier)
        whenever(aggregates.insert(any<PaperDecisionRead>())).thenAnswer { (it.arguments[0] as PaperDecisionRead).also(log::add) }
        whenever(readLog.findByEntranceIdOrderByAtAscIdAsc(entrance)).thenAnswer { log.toList() }
        for ((role, rule) in listOf(Role.BM to "PM-SEC-001", Role.MB to "PM-SEC-001", Role.CTL to "PM-GOV-008")) {
            whenever(logins.partyOf(PAPER_REALM, "login-$role")).thenReturn(parties.getValue(role))
            readLog("login-$role").andExpect(status().isOk)
                .andExpect(jsonPath("$[0].readOf").value("CUSTODIAN")).andExpect(jsonPath("$[0].loginSubject").value("login-BM"))
                .andExpect(jsonPath("$[-1].readOf").value("LOG")).andExpect(jsonPath("$[-1].outcome").value("SERVED")).andExpect(jsonPath("$[-1].ruleId").value(rule))
                .andExpect(jsonPath("$[-1].actor").value(parties.getValue(role).toString())).andExpect(jsonPath("$[-1].loginSubject").value("login-$role"))
                .andExpect(jsonPath("$[-1].fullName").doesNotExist())
        }
        assertThat(log.drop(1).map { it.readOf to it.entranceId }).containsOnly("LOG" to entrance).hasSize(3)
    }

    @Test
    fun `PM-SEC-004 a read of the log is entered SERVED and LOG, with the party, the login and the policy's rule, before the log is read`() {
        whenever(logins.partyOf(PAPER_REALM, "login-MB")).thenReturn(parties.getValue(Role.MB))
        readLog("login-MB").andExpect(status().isOk)
        val entry = argumentCaptor<PaperDecisionRead>().apply { verify(aggregates).insert(capture()) }.firstValue
        assertThat(entry.entranceId).isEqualTo(entrance)
        assertThat(entry.actor).isEqualTo(parties.getValue(Role.MB))
        assertThat(entry.loginIssuer to entry.loginSubject).isEqualTo(PAPER_REALM to "login-MB")
        assertThat(listOf(entry.outcome, entry.ruleId, entry.readOf)).containsExactly("SERVED", "PM-SEC-001", "LOG")
        org.mockito.kotlin.inOrder(aggregates, readLog) {
            verify(aggregates).insert(any<PaperDecisionRead>())
            verify(readLog).findByEntranceIdOrderByAtAscIdAsc(entrance)
        }
    }

    @Test
    fun `PM-SEC-002 an owner, a user, the cashier and the administrator are refused the log - a 403, and nothing is entered or read`() {
        for (role in listOf(Role.OWN, Role.USR, Role.CSH)) {
            whenever(logins.partyOf(PAPER_REALM, "login-$role")).thenReturn(parties.getValue(role))
            readLog("login-$role").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-002"))
        }
        readLog("operator").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-002"))
        readLog("login-of-nobody").andExpect(status().isForbidden)
        verifyNoInteractions(aggregates, readLog)
    }

    @Test
    fun `PM-SEC-004 a custodian read is entered as a read of the custodian, not of the log`() {
        whenever(stored.findByEntranceIdOrderByProtocolDateDescRecordedAtDesc(entrance)).thenReturn(listOf(aRecord()))
        whenever(logins.partyOf(PAPER_REALM, "login-CTL")).thenReturn(parties.getValue(Role.CTL))
        read("login-CTL").andExpect(status().isOk)
        assertThat(argumentCaptor<PaperDecisionRead>().apply { verify(aggregates).insert(capture()) }.firstValue.readOf).isEqualTo("CUSTODIAN")
    }
}
