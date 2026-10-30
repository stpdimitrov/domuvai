package zues.app.assembly

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.app.identity_org.Logins
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val EGN = "7501020018"
private const val REALM_NAMES = "https://id.example.test/realms/domuvai"
private const val OPERATOR = "operator-names"

/**
 * The register, the proxies and the votes with the parties' names beside their ids, on a real Postgres and the real
 * registry, through the HTTP layer with sign-in configured and one administrator named (#249 N1, #300 Q3): the names go to whoever may read the book,
 * each served read is entered once in the book's access log, and an owner keeps the ids alone. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["domuvai.auth.issuer-uri=$REALM_NAMES", "domuvai.auth.audience=domuvai-api", "domuvai.auth.mode=", "domuvai.auth.admins=$OPERATOR"])
@AutoConfigureMockMvc
class AssemblyNamesIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            val schemas = "registry,identity_org,assembly,money,maintenance,compliance,evidence,app,public"
            registry.add("spring.datasource.url") { "${postgres.jdbcUrl}&currentSchema=$schemas" }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var logins: Logins

    private val realm = REALM_NAMES
    private var signedInAs: String? = null

    private fun post(path: String, body: String) = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body)
        .also { request -> signedInAs?.let { request.with(jwt().jwt { token -> token.subject(it).issuer(realm) }) } }

    private fun get(path: String) = MockMvcRequestBuilders.get(path).also { request -> signedInAs?.let { request.with(jwt().jwt { token -> token.subject(it).issuer(realm) }) } }

    private fun created(path: String, body: String): JsonNode =
        json.readTree(mvc.perform(post(path, body)).andExpect(status().isCreated).andReturn().response.contentAsString)

    /** The registry's entrances and parties are the administrator's to write (AUTH-05e). */
    private fun registered(path: String, body: String): JsonNode {
        val before = signedInAs
        signedInAs = OPERATOR
        try { return created(path, body) } finally { signedInAs = before }
    }

    private fun <T> signedIn(party: UUID, act: () -> T): T {
        val before = signedInAs
        signedInAs = "login-$party"
        try { return act() } finally { signedInAs = before }
    }

    private fun person(name: String, egn: String? = null): UUID =
        UUID.fromString(registered("/api/registry/parties", """{"fullName":"$name"${egn?.let { ""","idType":"EGN","idValue":"$it"""" }.orEmpty()}}""").get("partyId").asText())
            .also { logins.tie(realm, "login-$it", it) }

    private fun mandate(entranceId: UUID, body: String, party: UUID) = jdbc.update(
        sofiaDated("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, ?, ?, current_date - 30, current_date + 300)"),
        entranceId, body, party,
    )

    /**
     * One unit of three owners — Ana (with an identity number), Boris and Vera — a manager and a controller. Boris appoints Ana
     * his proxy, Ana and Vera are present (Ana for Boris too), the sitting is open, and Ana has voted.
     */
    private inner class Meeting {
        val entranceId = UUID.fromString(registered("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""").get("entranceId").asText())
        val ana = person("Ана Колева", EGN)
        val boris = person("Борис Недев")
        val vera = person("Вера Стоянова")
        val manager = person("Иван Петров")
        val controller = person("Мария Иванова")
        val base: String
        val item: String

        init {
            mandate(entranceId, "BM", manager)
            mandate(entranceId, "CTL", controller)
            signedIn(manager) {                                                                  // the book is the manager's to keep (PM-BOOK-005)
                val unit = created("/api/registry/entrances/$entranceId/units", """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""")
                    .get("unitIds")[0].asText()
                listOf(ana to "0.5", boris to "0.25", vera to "0.25").forEach { (owner, share) ->
                    created("/api/registry/entrances/$entranceId/units/$unit/titles", """{"partyId":"$owner","titleRole":"OWN","share":"$share","validFrom":"2025-01-01"}""")
                }
            }
            val meets = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
            val assemblies = "/api/assembly/entrances/$entranceId/assemblies"
            val id = signedIn(manager) {
                created(assemblies, """{"convenedAs":"BM","scheduledAt":"$meets","place":"фоайето","mode":"IN_PERSON","urgent":false}""").get("id").asText()
            }
            base = "$assemblies/$id"
            item = signedIn(manager) {
                created("$base/agenda", """{"text":"Ремонт на покрива","itemType":"GENERAL"}""").get("id").asText()
                    .also { created("$base/notice-posting", """{"postedAt":"${Instant.now()}","coSignatoryPartyId":"$ana","photoHash":"${"3f".repeat(32)}"}""") }
            }
            jdbc.update("UPDATE assembly.assembly SET scheduled_at = now() WHERE id = ?::uuid", id)
            signedIn(boris) { created("$base/proxies", """{"agentPartyId":"$ana","agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"}""") }
            signedIn(manager) {
                created("$base/attendance", """{"attendeePartyId":"$ana","channel":"IN_PERSON"}""")
                created("$base/attendance", """{"attendeePartyId":"$vera","channel":"IN_PERSON"}""")
                created("$base/sittings", "{}")
                created("$base/agenda/$item/votes", """{"ownerPartyId":"$ana","choice":"FOR"}""")
            }
        }

        fun body(who: UUID, path: String): String = signedIn(who) { mvc.perform(get("$base/$path")).andExpect(status().isOk).andReturn().response.contentAsString }
        fun read(who: UUID, path: String): JsonNode = json.readTree(body(who, path))
        fun entries(purpose: String) = jdbc.queryForList("SELECT actor FROM registry.book_access WHERE entrance_id = ? AND kind = 'PARTY_NAMES' AND purpose = ? ORDER BY at", entranceId, purpose)
    }

    private fun names(rows: JsonNode, i: Int): Map<String, String> =
        rows[i].get("names")?.properties()?.associate { it.key to it.value.asText() }.orEmpty()

    @Test
    fun `PM-BOOK-006 the manager, the board and the controller read the register, the proxies and the votes named`() {
        val m = Meeting()
        for (who in listOf(m.manager, m.controller)) {
            val register = m.read(who, "attendance").associateBy { it.get("attendeePartyId").asText() }
            // Ana's line names her and Boris, whom she brings — the id in represents[] alone
            assertThat(register.getValue("${m.ana}").get("names").properties().associate { it.key to it.value.asText() })
                .isEqualTo(mapOf("${m.ana}" to "Ана Колева", "${m.boris}" to "Борис Недев"))
            assertThat(register.getValue("${m.vera}").get("names").properties().associate { it.key to it.value.asText() }).isEqualTo(mapOf("${m.vera}" to "Вера Стоянова"))
            assertThat(m.read(who, "proxies").single().get("names").properties().associate { it.key to it.value.asText() })
                .isEqualTo(mapOf("${m.boris}" to "Борис Недев", "${m.ana}" to "Ана Колева"))
            assertThat(m.read(who, "agenda/${m.item}/votes").single().get("names").properties().associate { it.key to it.value.asText() })
                .isEqualTo(mapOf("${m.ana}" to "Ана Колева"))
        }
    }

    @Test
    fun `PM-SEC-002 an owner reads the register and the proxies with ids only — no name of another person`() {
        val m = Meeting()
        for (path in listOf("attendance", "proxies")) {
            val text = m.body(m.vera, path)
            assertThat(text).doesNotContain("names").doesNotContain("Ана Колева").doesNotContain("Борис Недев").doesNotContain("Вера Стоянова")
            assertThat(names(m.read(m.ana, path), 0)).isEmpty()
        }
        assertThat(m.read(m.vera, "attendance")).hasSize(2)                                  // the ids are still served
        signedIn(m.vera) { mvc.perform(get("${m.base}/agenda/${m.item}/votes")).andExpect(status().isForbidden) }   // the votes are the book's readers' alone, as before
    }

    @Test
    fun `PM-BOOK-007 names served beside the register are entered once per read — withheld names enter nothing`() {
        val m = Meeting()
        assertThat(m.entries(NamedRead.REGISTER)).isEmpty()
        m.read(m.manager, "attendance")                                                       // two lines, one entry
        assertThat(m.entries(NamedRead.REGISTER).map { it["actor"] }).containsExactly(m.manager)
        m.read(m.controller, "attendance")
        assertThat(m.entries(NamedRead.REGISTER).map { it["actor"] }).containsExactly(m.manager, m.controller)
        m.read(m.vera, "attendance")                                                          // an owner: names withheld, nothing entered
        assertThat(m.entries(NamedRead.REGISTER)).hasSize(2)
        m.read(m.manager, "proxies")
        m.read(m.vera, "proxies")
        assertThat(m.entries(NamedRead.PROXIES).map { it["actor"] }).containsExactly(m.manager)
        m.read(m.controller, "agenda/${m.item}/votes")
        assertThat(m.entries(NamedRead.VOTES).map { it["actor"] }).containsExactly(m.controller)
        signedIn(m.vera) { mvc.perform(get("${m.base}/agenda/${m.item}/votes")).andExpect(status().isForbidden) }
        assertThat(m.entries(NamedRead.VOTES)).hasSize(1)
    }

    @Test
    fun `PM-BOOK-011 the names beside the register carry no identity number`() {
        val m = Meeting()
        for (path in listOf("attendance", "proxies", "agenda/${m.item}/votes")) {
            val text = m.body(m.manager, path)
            assertThat(text).contains("Ана Колева").doesNotContain(EGN).doesNotContain("idValue").doesNotContain("EGN")
        }
    }
}
