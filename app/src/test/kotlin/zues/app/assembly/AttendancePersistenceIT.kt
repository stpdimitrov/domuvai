package zues.app.assembly

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import zues.app.identity_org.Logins
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.kernel.toSofiaDate
import zues.law.constantOn
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The register against real PostgreSQL and the real registry, through the HTTP layer with real sign-ins: who is
 * present, whom their presence brings, and that a remote participant counts only once identified. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class AttendancePersistenceIT {

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

    private fun created(path: String, body: String): JsonNode = json.readTree(
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated).andReturn().response.contentAsString,
    )

    private fun entrance() = UUID.fromString(
        created("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""").get("entranceId").asText(),
    )

    private fun party() = UUID.fromString(created("/api/registry/parties", """{"fullName":"Иван Петров"}""").get("partyId").asText())

    @Autowired lateinit var logins: Logins
    private val realm = "https://id.example.test/realms/${UUID.randomUUID()}"

    /** Whose requests these are: every request below carries this login's token, until another person is signed in. */
    private var signedInAs: String? = null

    private fun post(path: String) = MockMvcRequestBuilders.post(path).also { request -> signedInAs?.let { request.with(jwt().jwt { token -> token.subject(it).issuer(realm) }) } }

    /** A party with a login of its own, tied to it. */
    private fun person(): UUID = party().also { logins.tie(realm, "login-$it", it) }

    private fun <T> signedIn(party: UUID?, act: () -> T): T {
        val before = signedInAs
        signedInAs = party?.let { "login-$it" }
        try { return act() } finally { signedInAs = before }
    }

    /** The entrance's manager, with the mandate in force — and signed in from here on. */
    private fun manager(entranceId: UUID, body: String = "BM", from: Int = -30, to: Int = 300): UUID = person().also {
        jdbc.update(
            "INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, ?, ?, current_date + ?, current_date + ?)",
            entranceId, body, it, from, to,
        )
        signedInAs = "login-$it"
    }

    /** An owner of a unit of the entrance since the start of last year — the whole entrance, the first time it is asked for. */
    private fun owner(entranceId: UUID): UUID = person().also {
        val existing = jdbc.queryForList("SELECT id FROM registry.unit WHERE entrance_id = ?", UUID::class.java, entranceId)
        val unit = existing.firstOrNull() ?: UUID.fromString(
            created("/api/registry/entrances/$entranceId/units", """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""")
                .get("unitIds")[0].asText(),
        )
        created("/api/registry/entrances/$entranceId/units/$unit/titles", """{"partyId":"$it","titleRole":"OWN","share":"${if (existing.isEmpty()) "0.5" else "0.25"}","validFrom":"2025-01-01"}""")
    }

    /** A month from now: far enough for any notice period, whenever this runs. */
    private val meets: Instant = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

    private fun convening(convenor: UUID, urgent: String = """"urgent":false""", at: Instant = meets) =
        """{"convenedBy":"$convenor","convenedAs":"BM","scheduledAt":"$at","place":"фоайето на вх. А","mode":"IN_PERSON",$urgent}"""

    private fun read(base: String, id: String): JsonNode =
        json.readTree(mvc.perform(get("$base/$id")).andExpect(status().isOk).andReturn().response.contentAsString)

    private fun assembly(entranceId: UUID): String {
        val base = "/api/assembly/entrances/$entranceId/assemblies"
        val id = created(base, convening(manager(entranceId))).get("id").asText()
        created("$base/$id/agenda", """{"text":"Отчет","itemType":"GENERAL"}""")
        created("$base/$id/agenda", """{"text":"Ремонт на покрива","itemType":"GENERAL"}""")
        return "$base/$id/proxies"
    }

    /** A noticed assembly meeting today: convened and noticed a month ahead as the law wants, then the day is made today. */
    private fun meetingToday(entranceId: UUID, coSignatory: UUID): String {
        val proxies = assembly(entranceId)
        val base = proxies.removeSuffix("/proxies")
        created("$base/notice-posting", """{"postedAt":"${Instant.now()}","coSignatoryPartyId":"$coSignatory","photoHash":"${"3f".repeat(32)}"}""")
        jdbc.update("UPDATE assembly.assembly SET scheduled_at = now(), mode = 'HYBRID' WHERE id = ?::uuid", base.substringAfterLast("/"))
        return base
    }

    private fun present(base: String, attendee: UUID, channel: String = "IN_PERSON") =
        mvc.perform(post("$base/attendance").contentType(MediaType.APPLICATION_JSON).content("""{"attendeePartyId":"$attendee","channel":"$channel"}"""))

    private fun register(base: String): JsonNode = json.readTree(mvc.perform(get("$base/attendance")).andExpect(status().isOk).andReturn().response.contentAsString)

    @Test
    fun `PM-GA-012 PM-GA-016 the register reads back who is present and whom they bring - in person, by proxy, and by video only once identified`() {
        val entranceId = entrance()
        val (ana, boris, vera) = listOf(owner(entranceId), owner(entranceId), owner(entranceId))
        val base = meetingToday(entranceId, ana)                                     // convened by the entrance's manager, signed in from here on
        val manager = jdbc.queryForObject("SELECT convened_by FROM assembly.assembly WHERE entrance_id = ?", UUID::class.java, entranceId)!!
        // Boris appoints Ana, signed in; then the manager keeps the register
        signedIn(boris) {
            created("$base/proxies", """{"agentPartyId":"$ana","agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"}""")
        }
        val stranger = person()
        present(base, stranger).andExpect(status().isBadRequest)                     // neither an owner nor anybody's proxy
        present(base, ana).andExpect(status().isCreated)
        present(base, ana).andExpect(status().isConflict)                            // once
        present(base, boris).andExpect(status().isConflict)                          // Boris is represented by Ana: not entered beside her (PM-ORG-005)
        // once somebody is on the register the agenda and the date stay as noticed, and a present person gives no proxy
        mvc.perform(post("$base/agenda").contentType(MediaType.APPLICATION_JSON).content("""{"text":"Още една точка","itemType":"GENERAL"}""")).andExpect(status().isConflict)
        signedIn(ana) {
            mvc.perform(post("$base/proxies").contentType(MediaType.APPLICATION_JSON).content("""{"agentPartyId":"$vera","agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"}"""))
                .andExpect(status().isConflict)
        }
        assertThat(jdbc.queryForObject("SELECT status FROM assembly.assembly WHERE entrance_id = ?", String::class.java, entranceId)).isEqualTo("NOTICED")
        val remote = json.readTree(present(base, vera, "VIDEO").andExpect(status().isCreated).andReturn().response.contentAsString)
        assertThat(remote.get("counted").asBoolean()).isFalse()

        var lines = register(base).associateBy { it.get("attendeePartyId").asText() }
        assertThat(lines.getValue("$ana").get("represents").map { it.asText() }).containsExactlyInAnyOrder("$ana", "$boris")
        assertThat(lines.getValue("$vera").get("represents")).isEmpty()
        assertThat(lines.getValue("$ana").get("recordedBy").asText()).isEqualTo("$manager")

        // nobody but whoever runs the assembly keeps the register or verifies: an owner, the controller, nobody at all
        val controller = person().also {
            jdbc.update("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, 'CTL', ?, current_date - 30, current_date + 300)", entranceId, it)
        }
        val verify = "$base/attendance/${remote.get("id").asText()}/identity-verification"
        for (who in listOf(ana, controller, null)) {
            signedIn(who) {
                present(base, vera).andExpect(status().isForbidden)
                mvc.perform(post(verify).contentType(MediaType.APPLICATION_JSON).content("""{"how":"камера"}""")).andExpect(status().isForbidden)
            }
        }
        created(verify, """{"how":"лична карта пред камерата"}""")
        mvc.perform(post(verify).contentType(MediaType.APPLICATION_JSON).content("""{"how":"пак"}""")).andExpect(status().isConflict)
        lines = register(base).associateBy { it.get("attendeePartyId").asText() }
        assertThat(lines.getValue("$vera").get("counted").asBoolean()).isTrue()
        assertThat(lines.getValue("$vera").get("represents").map { it.asText() }).containsExactly("$vera")

        // the register is evidence: a line and a verification are neither rewritten nor removed, and each names its sign-in
        jdbc.update("UPDATE assembly.attendance SET channel = 'IN_PERSON' WHERE attendee_party_id = ?", vera)
        jdbc.update("DELETE FROM assembly.attendance WHERE attendee_party_id = ?", ana)
        jdbc.update("DELETE FROM assembly.identity_verification WHERE entrance_id = ?", entranceId)
        assertThat(jdbc.queryForList("SELECT channel || ' ' || login_subject FROM assembly.attendance WHERE entrance_id = ? ORDER BY recorded_at", String::class.java, entranceId))
            .containsExactly("IN_PERSON login-$manager", "VIDEO login-$manager")
        assertThat(jdbc.queryForObject("SELECT how FROM assembly.identity_verification WHERE entrance_id = ?", String::class.java, entranceId)).isEqualTo("лична карта пред камерата")
        assertThatThrownBy { jdbc.execute("TRUNCATE assembly.attendance CASCADE") }.hasMessageContaining("is not emptied")
        assertThatThrownBy { jdbc.execute("TRUNCATE assembly.identity_verification") }.hasMessageContaining("is not emptied")
        // a verification belongs to a line of its own assembly
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO assembly.identity_verification SELECT attendance_id, gen_random_uuid(), entrance_id, how, verified_by, login_issuer, login_subject, verified_at FROM assembly.identity_verification WHERE entrance_id = ?",
                entranceId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `PM-GA-012 the register opens on the day of a noticed assembly - not for a draft, and not before the day`() {
        val entranceId = entrance()
        val ana = owner(entranceId)
        val draft = assembly(entranceId).removeSuffix("/proxies")
        present(draft, ana).andExpect(status().isConflict)                           // a draft
        created("$draft/notice-posting", """{"postedAt":"${Instant.now()}","coSignatoryPartyId":"$ana","photoHash":"${"3f".repeat(32)}"}""")
        present(draft, ana).andExpect(status().isConflict)                           // noticed, a month ahead
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.attendance WHERE entrance_id = ?", Int::class.java, entranceId)).isZero()
        // through another entrance's path the assembly is not there
        val other = entrance()
        signedIn(manager(other)) { present(draft.replace("$entranceId", "$other"), ana).andExpect(status().isNotFound) }
    }
}
