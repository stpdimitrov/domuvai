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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.app.identity_org.Logins
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Votes against real PostgreSQL and the real registry, through the HTTP layer with real sign-ins: an opened sitting,
 * owners present in person and by proxy, a co-owned unit, a correction, and the table that refuses to be edited.
 * Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class VotePersistenceIT {

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

    private val realm = "https://id.example.test/realms/${UUID.randomUUID()}"
    private var signedInAs: String? = null

    private fun post(path: String, body: String) = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body)
        .also { request -> signedInAs?.let { request.with(jwt().jwt { token -> token.subject(it).issuer(realm) }) } }

    private fun created(path: String, body: String): JsonNode =
        json.readTree(mvc.perform(post(path, body)).andExpect(status().isCreated).andReturn().response.contentAsString)

    private fun <T> signedIn(party: UUID, act: () -> T): T {
        val before = signedInAs
        signedInAs = "login-$party"
        try { return act() } finally { signedInAs = before }
    }

    private fun person(): UUID = UUID.fromString(created("/api/registry/parties", """{"fullName":"Иван Петров"}""").get("partyId").asText())
        .also { logins.tie(realm, "login-$it", it) }

    /** An entrance of one unit of 100% — owned a half by the first owner, a quarter by each of the next two. */
    private class Meeting(val entranceId: UUID, val base: String, val item: String, val manager: UUID, val owners: List<UUID>, val unit: UUID)

    /** Convened by the manager, noticed a month ahead, then made to meet today; the second owner's proxy names the first, who is present; the sitting opened. */
    private fun opened(): Meeting {
        val entranceId = UUID.fromString(created("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""").get("entranceId").asText())
        val unit = UUID.fromString(
            created("/api/registry/entrances/$entranceId/units", """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""")
                .get("unitIds")[0].asText(),
        )
        val owners = listOf("0.5", "0.25", "0.25").map { share ->
            person().also { created("/api/registry/entrances/$entranceId/units/$unit/titles", """{"partyId":"$it","titleRole":"OWN","share":"$share","validFrom":"2025-01-01"}""") }
        }
        val manager = person()
        jdbc.update(
            sofiaDated("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, 'BM', ?, current_date - 30, current_date + 300)"),
            entranceId, manager,
        )
        val meets = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val base = "/api/assembly/entrances/$entranceId/assemblies"
        val id = signedIn(manager) {
            created(base, """{"convenedAs":"BM","scheduledAt":"$meets","place":"фоайето","mode":"IN_PERSON","urgent":false}""").get("id").asText()
        }
        val of = "$base/$id"
        val item = signedIn(manager) {
            created("$of/agenda", """{"text":"Ремонт на покрива","itemType":"GENERAL"}""").get("id").asText()
                .also { created("$of/notice-posting", """{"postedAt":"${Instant.now()}","coSignatoryPartyId":"${owners[0]}","photoHash":"${"3f".repeat(32)}"}""") }
        }
        jdbc.update("UPDATE assembly.assembly SET scheduled_at = now() WHERE id = ?::uuid", id)
        signedIn(owners[1]) { created("$of/proxies", """{"agentPartyId":"${owners[0]}","agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"}""") }
        signedIn(manager) {
            created("$of/attendance", """{"attendeePartyId":"${owners[0]}","channel":"IN_PERSON"}""")
            assertThat(created("$of/sittings", "{}").get("quorumMet").asBoolean()).isTrue()                  // 75% present
        }
        return Meeting(entranceId, of, item, manager, owners, unit)
    }

    private fun vote(m: Meeting, owner: UUID, choice: String, correction: String? = null, item: String = m.item): ResultActions =
        mvc.perform(post("${m.base}/agenda/$item/votes", """{"ownerPartyId":"$owner","choice":"$choice"${correction?.let { ""","correction":"$it"""" }.orEmpty()}}"""))

    private fun rows(m: Meeting) = jdbc.queryForList("SELECT party_id, weight, choice, channel, reverses, correction FROM assembly.vote WHERE agenda_item_id = ?::uuid ORDER BY cast_at, id", m.item)

    @Test
    fun `PM-ORG-005 a vote weighs the owner's title share of the unit's ideal parts - in person or by proxy, once, and only for an owner present`() {
        val m = opened()
        val (ana, boris, vera) = m.owners
        signedIn(m.manager) {
            vote(m, ana, "FOR").andExpect(status().isCreated)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].weight").value("50")).andExpect(jsonPath("$[0].channel").value("IN_PERSON"))
                .andExpect(jsonPath("$[0].unitId").value(m.unit.toString())).andExpect(jsonPath("$[0].sessionNo").value(1))
                .andExpect(jsonPath("$[0].recordedBy").value(m.manager.toString()))
            vote(m, boris, "AGAINST").andExpect(status().isCreated)
                .andExpect(jsonPath("$[0].weight").value("25")).andExpect(jsonPath("$[0].channel").value("PROXY"))   // brought by Ana's presence
            vote(m, vera, "FOR").andExpect(status().isBadRequest)                                                    // not present: no vote
            vote(m, ana, "AGAINST").andExpect(status().isConflict)                                                    // once — a second is a correction
            vote(m, ana, "MAYBE").andExpect(status().isBadRequest)
        }
        // what was cast is the represented owners' share of the unit, counted once each
        assertThat(rows(m).sumOf { it["weight"] as java.math.BigDecimal }.stripTrailingZeros().toPlainString()).isEqualTo("75")
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO assembly.vote (id, entrance_id, assembly_id, agenda_item_id, session_no, party_id, unit_id, weight, choice, channel, cast_at, recorded_by, login_issuer, login_subject) " +
                    "SELECT gen_random_uuid(), entrance_id, assembly_id, agenda_item_id, session_no, party_id, unit_id, weight, 'AGAINST', channel, now(), recorded_by, login_issuer, login_subject FROM assembly.vote WHERE party_id = ?",
                ana,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("vote_first_once")
    }

    @Test
    fun `PM-VOTE-014 a vote is never edited - a correction is a new row that reverses it and says why, and the table refuses an update or a delete`() {
        val m = opened()
        val ana = m.owners[0]
        val first = signedIn(m.manager) { json.readTree(vote(m, ana, "FOR").andExpect(status().isCreated).andReturn().response.contentAsString)[0] }
        signedIn(m.manager) {
            vote(m, ana, "FOR", correction = "  ").andExpect(status().isConflict)                                      // a correction says why
            vote(m, m.owners[1], "FOR", correction = "грешка").andExpect(status().isBadRequest)                       // nothing of Boris's to correct
            vote(m, ana, "AGAINST", correction = "вдигна ръка против, записано погрешно").andExpect(status().isCreated)
                .andExpect(jsonPath("$[0].reverses").value(first.get("id").asText()))
                .andExpect(jsonPath("$[0].correction").value("вдигна ръка против, записано погрешно"))
                .andExpect(jsonPath("$[0].weight").value("50"))
            vote(m, ana, "ABSTAIN", correction = "отново поправено").andExpect(status().isCreated)                    // the correction is reversed in its turn
        }
        val kept = rows(m)
        assertThat(kept.map { it["choice"] }).containsExactly("FOR", "AGAINST", "ABSTAIN")
        assertThat(kept[2]["reverses"]).isNotEqualTo(kept[1]["reverses"])
        // nothing is edited or removed, whoever asks the database
        assertThat(jdbc.update("UPDATE assembly.vote SET choice = 'FOR' WHERE party_id = ?", ana)).isZero()
        assertThat(jdbc.update("DELETE FROM assembly.vote WHERE party_id = ?", ana)).isZero()
        assertThat(rows(m)).isEqualTo(kept)
        // a row is reversed once; a correction names what it reverses, and a first vote names no correction
        val copy = "INSERT INTO assembly.vote (id, entrance_id, assembly_id, agenda_item_id, session_no, party_id, unit_id, weight, choice, channel, cast_at, reverses, correction, recorded_by, login_issuer, login_subject) " +
            "SELECT gen_random_uuid(), entrance_id, assembly_id, agenda_item_id, session_no, party_id, unit_id, weight, 'FOR', channel, now(), %s, %s, recorded_by, login_issuer, login_subject FROM assembly.vote WHERE id = ?::uuid"
        assertThatThrownBy { jdbc.update(copy.format("?::uuid", "'двойно'"), first.get("id").asText(), first.get("id").asText()) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("vote_reversed_once")
        assertThatThrownBy { jdbc.update(copy.format("NULL", "'без обръщане'"), first.get("id").asText()) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("vote_correction_says_why")
    }

    @Test
    fun `PM-VOTE-001 a vote goes on an item of this assembly's agenda, at its opened sitting - an item of another, or none, is a 404, and before the sitting opens a 409`() {
        val m = opened()
        val other = opened()
        signedIn(m.manager) {
            // an item of another assembly of the same entrance, convened by the same manager
            val later = Instant.now().plus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
            val sibling = created(m.base.substringBeforeLast("/"), """{"convenedAs":"BM","scheduledAt":"$later","place":"двора","mode":"IN_PERSON","urgent":false}""").get("id").asText()
            val itsItem = created("${m.base.substringBeforeLast("/")}/$sibling/agenda", """{"text":"Друго","itemType":"GENERAL"}""").get("id").asText()
            vote(m, m.owners[0], "FOR", item = itsItem).andExpect(status().isNotFound)
            vote(m, m.owners[0], "FOR", item = other.item).andExpect(status().isNotFound)
            vote(m, m.owners[0], "FOR", item = UUID.randomUUID().toString()).andExpect(status().isNotFound)
        }
        // an assembly whose sitting has not opened takes no vote
        jdbc.update("UPDATE assembly.assembly SET status = 'NOTICED' WHERE id = ?::uuid", m.base.substringAfterLast("/"))
        signedIn(m.manager) { vote(m, m.owners[0], "FOR").andExpect(status().isConflict) }
        assertThat(rows(m)).isEmpty()
        // the item named carries the majority it was bound to on the agenda
        val bound = json.readTree(mvc.perform(get(m.base)).andReturn().response.contentAsString).get("agenda")[0].get("majorityRuleId").asText()
        assertThat(bound).startsWith("GENERAL@")
    }

    @Test
    fun `PM-SEC-001 a vote is recorded by whoever runs the assembly - an owner, another office and a stranger are a 403 naming the rule, and nothing is recorded`() {
        val m = opened()
        val controller = person()
        jdbc.update(
            sofiaDated("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, 'CTL', ?, current_date - 30, current_date + 300)"),
            m.entranceId, controller,
        )
        for (who in listOf(m.owners[0], m.owners[1], controller, person())) {
            signedIn(who) { vote(m, m.owners[0], "FOR").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001")) }
        }
        vote(m, m.owners[0], "FOR").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))   // nobody signed in records nothing
        assertThat(rows(m)).isEmpty()
    }
}
