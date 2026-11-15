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

    /** An entrance or a party, registered as nobody — sign-in switched off — whoever the test has signed in: registering them is the administrator's (AUTH-05e). */
    private fun registered(path: String, body: String): JsonNode {
        val before = signedInAs
        signedInAs = null
        try { return created(path, body) } finally { signedInAs = before }
    }

    private fun entrance() = UUID.fromString(
        registered("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""").get("entranceId").asText(),
    )

    private val aside by lazy { entrance() }   // BOOK-02e: a party is registered in an entrance; one in no book here is registered aside
    private fun party(entranceId: UUID = aside) = UUID.fromString(registered("/api/registry/parties", """{"entranceId":"$entranceId","fullName":"Иван Петров"}""").get("partyId").asText())

    @Autowired lateinit var logins: Logins
    private val realm = "https://id.example.test/realms/${UUID.randomUUID()}"

    /** Whose requests these are: every request below carries this login's token, until another person is signed in. */
    private var signedInAs: String? = null

    /** A request, under [key] — a fresh one for a proxy or an attendance unless the test replays one; null sends none (PM-SYS-014). */
    private fun post(path: String, key: String? = if (path.endsWith("/proxies") || path.endsWith("/attendance")) UUID.randomUUID().toString() else null) =
        MockMvcRequestBuilders.post(path).also { request -> key?.let { request.header("Idempotency-Key", it) } }.also { request -> signedInAs?.let { request.with(jwt().jwt { token -> token.subject(it).issuer(realm) }) } }

    /** A party with a login of its own, tied to it. */
    private fun person(entranceId: UUID = aside): UUID = party(entranceId).also { logins.tie(realm, "login-$it", it) }

    private fun <T> signedIn(party: UUID?, act: () -> T): T {
        val before = signedInAs
        signedInAs = party?.let { "login-$it" }
        try { return act() } finally { signedInAs = before }
    }

    /** The entrance's manager, with the mandate in force — and signed in from here on. */
    private fun manager(entranceId: UUID, body: String = "BM", from: Int = -30, to: Int = 300): UUID = person().also {
        jdbc.update(
            sofiaDated("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, ?, ?, current_date + ?, current_date + ?)"),
            entranceId, body, it, from, to,
        )
        signedInAs = "login-$it"
    }

    /** An owner of a unit of the entrance since the start of last year — the whole entrance, the first time it is asked for. */
    private fun owner(entranceId: UUID): UUID = person(entranceId).also {
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

    private fun present(base: String, attendee: UUID, channel: String = "IN_PERSON", key: String? = UUID.randomUUID().toString()) =
        mvc.perform(post("$base/attendance", key).contentType(MediaType.APPLICATION_JSON).content("""{"attendeePartyId":"$attendee","channel":"$channel"}"""))

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
            jdbc.update(sofiaDated("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, 'CTL', ?, current_date - 30, current_date + 300)"), entranceId, it)
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

    private fun lineOf(result: org.springframework.test.web.servlet.ResultActions) = json.readTree(result.andExpect(status().isCreated).andReturn().response.contentAsString)

    private fun lineRows(entranceId: UUID) = jdbc.queryForList("SELECT id::text, attendee_party_id, channel, recorded_at, request_key_id FROM assembly.attendance WHERE entrance_id = ? ORDER BY recorded_at", entranceId)

    private fun keptKeys(entranceId: UUID) = jdbc.queryForObject("SELECT count(*) FROM assembly.vote_request_key WHERE entrance_id = ?", Int::class.java, entranceId)

    @Test
    fun `PM-SYS-014 replaying a queued attendance twice enters the person once - the same key and request answer with the line first made, also on a later day`() {
        val entranceId = entrance()
        val (ana, boris) = listOf(owner(entranceId), owner(entranceId))
        val base = meetingToday(entranceId, ana)
        val queued = UUID.randomUUID().toString()
        val first = lineOf(present(base, ana, key = queued))
        repeat(2) { assertThat(lineOf(present(base, ana, key = queued))).isEqualTo(first) }                         // the queue replayed twice: one line
        assertThat(lineRows(entranceId).map { it["id"] }).containsExactly(first.get("id").asText())
        assertThat(keptKeys(entranceId)).isEqualTo(1)
        present(base, ana).andExpect(status().isConflict)                                                          // another key: Ana is on the register
        // the day passes: a new key is refused, the replay is still answered with the line
        jdbc.update("UPDATE assembly.assembly SET scheduled_at = now() - interval '2 days' WHERE id = ?::uuid", base.substringAfterLast("/"))
        present(base, boris).andExpect(status().isConflict)
        assertThat(lineOf(present(base, ana, key = queued))).isEqualTo(first)
        assertThat(lineRows(entranceId)).hasSize(1)
    }

    @Test
    fun `PM-SYS-014 an attendance's key is refused for another request or operation, and an attendance without an Idempotency-Key is a 400`() {
        val entranceId = entrance()
        val (ana, boris) = listOf(owner(entranceId), owner(entranceId))
        val base = meetingToday(entranceId, ana)
        present(base, ana, key = null).andExpect(status().isBadRequest)
        present(base, ana, key = "  ").andExpect(status().isBadRequest)
        val k = UUID.randomUUID().toString()
        jdbc.update("INSERT INTO assembly.vote_request_key (id, entrance_id, idempotency_key, operation, request_hash, kept_at) VALUES (gen_random_uuid(), ?, ?, 'RECORD_VOTE', 'x', now())", entranceId, k)
        present(base, ana, key = k).andExpect(status().isConflict)                                                 // k was kept for a vote: another operation
        assertThat(lineRows(entranceId)).isEmpty()
        assertThat(keptKeys(entranceId)).isEqualTo(1)                                                              // a refused entry keeps no key
        present(base, person(), key = UUID.randomUUID().toString()).andExpect(status().isBadRequest)               // neither an owner nor anybody's proxy
        assertThat(keptKeys(entranceId)).isEqualTo(1)
        val bad = "bad-${UUID.randomUUID()}"
        present(base, ana, key = bad).andExpect(status().isCreated)
        present(base, boris, key = bad).andExpect(status().isConflict)                                             // another attendee
        present(base, ana, "VIDEO", key = bad).andExpect(status().isConflict)                                      // another channel
        signedIn(manager(entranceId, "CTL")) { present(base, ana, key = bad).andExpect(status().isForbidden) }
        assertThat(lineRows(entranceId)).hasSize(1)
    }

    @Test
    fun `PM-ORG-005 a replay of an attendance is answered with the line, not refused as already on the register or as represented, while a new key for the same person is still refused`() {
        val entranceId = entrance()
        val (ana, boris) = listOf(owner(entranceId), owner(entranceId))
        val base = meetingToday(entranceId, ana)
        val queued = UUID.randomUUID().toString()
        val line = lineOf(present(base, boris, key = queued))
        present(base, boris).andExpect(status().isConflict)                                                        // a new key: Boris is on the register
        // a proxy naming Boris as principal exists since: the replay is answered, not refused as represented
        jdbc.update(
            "INSERT INTO assembly.proxy (id, entrance_id, assembly_id, principal_party_id, agent_party_id, agent_kind, scope, form, registered_at, login_issuer, login_subject, recorded_by) " +
                "VALUES (gen_random_uuid(), ?, ?::uuid, ?, ?, 'OWNER', 'WHOLE_AGENDA', 'WRITTEN', now(), ?, ?, ?)",
            entranceId, base.substringAfterLast("/"), boris, ana, realm, "login-$boris", boris,
        )
        assertThat(lineOf(present(base, boris, key = queued)).get("id")).isEqualTo(line.get("id"))                 // the replay is not one more
        assertThat(lineRows(entranceId)).hasSize(1)
    }

    @Test
    fun `PM-VOTE-014 a replayed attendance adds no row and edits none - its row names the key, which is never changed or removed`() {
        val entranceId = entrance()
        val ana = owner(entranceId)
        val base = meetingToday(entranceId, ana)
        val queued = UUID.randomUUID().toString()
        repeat(2) { lineOf(present(base, ana, key = queued)) }
        val kept = jdbc.queryForObject("SELECT id FROM assembly.vote_request_key WHERE entrance_id = ? AND idempotency_key = ?", UUID::class.java, entranceId, queued)
        assertThat(jdbc.queryForObject("SELECT operation FROM assembly.vote_request_key WHERE id = ?", String::class.java, kept)).isEqualTo("RECORD_ATTENDANCE")
        val row = lineRows(entranceId).single()
        assertThat(row["request_key_id"]).isEqualTo(kept)
        assertThat(jdbc.update("UPDATE assembly.vote_request_key SET request_hash = 'x' WHERE id = ?", kept)).isZero()
        assertThat(jdbc.update("DELETE FROM assembly.vote_request_key WHERE id = ?", kept)).isZero()
        assertThat(jdbc.update("UPDATE assembly.attendance SET request_key_id = NULL WHERE entrance_id = ?", entranceId)).isZero()
        assertThat(lineRows(entranceId).single()).isEqualTo(row)                                                   // the replay changed nothing
    }

    @Test
    fun `PM-SEC-001 a replayed attendance is answered only to whoever keeps the register - the policy is asked before the key is looked up`() {
        val entranceId = entrance()
        val ana = owner(entranceId)
        val base = meetingToday(entranceId, ana)
        val queued = UUID.randomUUID().toString()
        lineOf(present(base, ana, key = queued))
        for (who in listOf(ana, person(), null)) {                                                                  // an owner, a stranger, nobody signed in
            signedIn(who) { present(base, ana, key = queued).andExpect(status().isForbidden) }
        }
        assertThat(lineRows(entranceId)).hasSize(1)
    }

    @Test
    fun `PM-GA-016 a replayed remote attendance is answered with the line as it stands - unverified is not counted, verified is`() {
        val entranceId = entrance()
        val (ana, vera) = listOf(owner(entranceId), owner(entranceId))
        val base = meetingToday(entranceId, ana)
        val queued = UUID.randomUUID().toString()
        val first = lineOf(present(base, vera, "VIDEO", key = queued))
        assertThat(first.get("counted").asBoolean()).isFalse()
        assertThat(lineOf(present(base, vera, "VIDEO", key = queued)).get("counted").asBoolean()).isFalse()        // not identified yet: not counted
        created("$base/attendance/${first.get("id").asText()}/identity-verification", """{"how":"лична карта пред камерата"}""")
        val replayed = lineOf(present(base, vera, "VIDEO", key = queued))
        assertThat(replayed.get("id")).isEqualTo(first.get("id"))
        assertThat(replayed.get("identityVerified").asBoolean()).isTrue()                                           // the line as it stands now
        assertThat(replayed.get("counted").asBoolean()).isTrue()
        assertThat(replayed.get("represents").map { it.asText() }).containsExactly("$vera")
        assertThat(lineRows(entranceId)).hasSize(1)
    }
}
