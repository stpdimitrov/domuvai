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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Convening against real PostgreSQL, through the HTTP layer: the draft and its agenda read back as
 * stored, and the database itself refuses what the service refuses. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class AssemblyPersistenceIT {

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

    /** A month from now: far enough for any notice period, whenever this runs. */
    private val meets: Instant = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

    private fun convening(convenor: UUID, urgent: String = """"urgent":false""", at: Instant = meets) =
        """{"convenedBy":"$convenor","convenedAs":"BM","scheduledAt":"$at","place":"фоайето на вх. А","mode":"IN_PERSON",$urgent}"""

    private fun read(base: String, id: String): JsonNode =
        json.readTree(mvc.perform(get("$base/$id")).andExpect(status().isOk).andReturn().response.contentAsString)

    @Test
    fun `PM-GA-002 a convened assembly is a draft that reads back with its convenor, capacity and agenda in order`() {
        val entranceId = entrance()
        val convenor = party()
        val base = "/api/assembly/entrances/$entranceId/assemblies"
        val id = created(base, convening(convenor)).get("id").asText()

        val first = created("$base/$id/agenda", """{"text":"Отчет на управителя","itemType":"GENERAL"}""")
        val second = created("$base/$id/agenda", """{"text":"Избор на управител","itemType":"GENERAL"}""")
        assertThat(first.get("ordinal").asInt()).isEqualTo(1)
        assertThat(second.get("ordinal").asInt()).isEqualTo(2)
        assertThat(first.get("majority").get("denominator").asText()).isEqualTo("REPRESENTED")

        val read = json.readTree(mvc.perform(get("$base/$id")).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(read.get("status").asText()).isEqualTo("DRAFT")
        assertThat(read.get("convenedBy").asText()).isEqualTo(convenor.toString())
        assertThat(read.get("convenedAs").asText()).isEqualTo("BM")
        assertThat(read.get("scheduledAt").asText()).isEqualTo(meets.toString())
        assertThat(read.get("agenda").map { it.get("text").asText() }).containsExactly("Отчет на управителя", "Избор на управител")
        assertThat(read.get("agenda")[0].get("majorityRuleId").asText()).isEqualTo(first.get("majority").get("id").asText())

        // another entrance does not see it; an unregistered entrance is a 404 and an unregistered convenor a 400, not a 500
        mvc.perform(get("/api/assembly/entrances/${entrance()}/assemblies/$id")).andExpect(status().isNotFound)
        mvc.perform(post("/api/assembly/entrances/${UUID.randomUUID()}/assemblies").contentType(MediaType.APPLICATION_JSON).content(convening(convenor)))
            .andExpect(status().isNotFound)
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(convening(UUID.randomUUID()))).andExpect(status().isBadRequest)
    }

    @Test
    fun `PM-GA-005 the justification of an urgent assembly is stored, and the database refuses urgency without one`() {
        val entranceId = entrance()
        val convenor = party()
        val base = "/api/assembly/entrances/$entranceId/assemblies"
        val id = created(base, convening(convenor, """"urgent":true,"urgencyReason":"спукана тръба в мазето"""")).get("id").asText()
        assertThat(jdbc.queryForObject("SELECT urgency_reason FROM assembly.assembly WHERE id = ?::uuid", String::class.java, id))
            .isEqualTo("спукана тръба в мазето")

        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(convening(convenor, """"urgent":true"""))).andExpect(status().isBadRequest)

        val insert = "INSERT INTO assembly.assembly(id, entrance_id, convened_by, convened_as, place, scheduled_at, mode, status, urgent, urgency_reason) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'фоайето', now(), 'IN_PERSON', 'DRAFT', ?, ?)"
        assertThat(jdbc.update(insert, entranceId, convenor, "BM", true, "теч")).isEqualTo(1)          // the control: this insert is a good one
        for ((urgent, reason) in listOf(true to null, true to "   ", true to "\t\n", false to "бързаме", false to "")) {
            assertThatThrownBy { jdbc.update(insert, entranceId, convenor, "BM", urgent, reason) }
                .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("assembly_urgency_recorded")
        }
        // PM-GA-002 — a capacity outside the three, or none at all
        assertThatThrownBy { jdbc.update(insert, entranceId, convenor, "CSH", false, null) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("convened_as")
        assertThatThrownBy { jdbc.update(insert, entranceId, convenor, null, false, null) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("assembly_convening_stated")
    }

    @Test
    fun `PM-VOTE-004 an item typed COMMON_PART_USE_RIGHT is refused while no threshold is confirmed, and nothing is stored`() {
        val entranceId = entrance()
        val base = "/api/assembly/entrances/$entranceId/assemblies"
        val id = created(base, convening(party())).get("id").asText()
        mvc.perform(
            post("$base/$id/agenda").contentType(MediaType.APPLICATION_JSON).content("""{"text":"Покривът под наем","itemType":"COMMON_PART_USE_RIGHT"}"""),
        ).andExpect(status().isUnprocessableEntity)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.agenda_item WHERE assembly_id = ?::uuid", Int::class.java, id)).isZero()
    }

    @Test
    fun `PM-GA-007 the posting act makes the assembly NOTICED, and the database refuses NOTICED without one`() {
        val entranceId = entrance()
        val convenor = party()
        val owner = party()
        val base = "/api/assembly/entrances/$entranceId/assemblies"
        val id = created(base, convening(convenor)).get("id").asText()
        created("$base/$id/agenda", """{"text":"Отчет на управителя","itemType":"GENERAL"}""")
        val photo = "3f".repeat(32)
        val posted = Instant.now().truncatedTo(ChronoUnit.MICROS)                       // after the agenda was last touched
        fun posting(coSignatory: UUID) = """{"postedAt":"$posted","coSignatoryPartyId":"$coSignatory","photoHash":"$photo"}"""

        // neither the status alone nor a posting time alone makes it noticed: there must be an act, and its own
        for (set in listOf("status = 'NOTICED'", "status = 'NOTICED', notice_posted_at = now()", "status = 'OPEN'")) {
            assertThatThrownBy { jdbc.update("UPDATE assembly.assembly SET $set WHERE id = ?::uuid", id) }
                .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("assembly_noticed_by_posting")
        }
        // a signatory nobody registered, and the convenor signing twice, are the caller's errors
        mvc.perform(post("$base/$id/notice-posting").contentType(MediaType.APPLICATION_JSON).content(posting(UUID.randomUUID()))).andExpect(status().isBadRequest)
        mvc.perform(post("$base/$id/notice-posting").contentType(MediaType.APPLICATION_JSON).content(posting(convenor))).andExpect(status().isBadRequest)
        assertThat(read(base, id).get("status").asText()).isEqualTo("DRAFT")

        val act = created("$base/$id/notice-posting", posting(owner))
        assertThat(act.get("convenorPartyId").asText()).isEqualTo(convenor.toString())
        assertThat(act.get("statedAgenda").asText()).isEqualTo("1. Отчет на управителя")
        val noticed = read(base, id)
        assertThat(noticed.get("status").asText()).isEqualTo("NOTICED")
        assertThat(noticed.get("noticePostedAt").asText()).isEqualTo(posted.toString())
        mvc.perform(post("$base/$id/notice-posting").contentType(MediaType.APPLICATION_JSON).content(posting(owner))).andExpect(status().isConflict)

        // the act is evidence: it cannot be rewritten or removed, and the table itself wants two signatories
        jdbc.update("UPDATE assembly.notice_posting SET photo_hash = ? WHERE assembly_id = ?::uuid", "00".repeat(32), id)
        jdbc.update("DELETE FROM assembly.notice_posting WHERE assembly_id = ?::uuid", id)
        assertThat(jdbc.queryForObject("SELECT photo_hash FROM assembly.notice_posting WHERE assembly_id = ?::uuid", String::class.java, id)).isEqualTo(photo)
        assertThatThrownBy { jdbc.execute("TRUNCATE assembly.notice_posting CASCADE") }.hasMessageContaining("is not emptied")
        // another assembly cannot borrow this one's act
        val other = created(base, convening(convenor)).get("id").asText()
        assertThatThrownBy {
            jdbc.update(
                "UPDATE assembly.assembly SET status = 'NOTICED', notice_posted_at = now(), notice_posting_id = ?::uuid WHERE id = ?::uuid",
                act.get("id").asText(), other,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("assembly_notice_posting_is_its_own")
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO assembly.notice_posting SELECT gen_random_uuid(), entrance_id, assembly_id, posted_at, convenor_party_id, convenor_party_id, " +
                    "photo_hash, stated_scheduled_at, stated_place, stated_agenda, recorded_at FROM assembly.notice_posting WHERE assembly_id = ?::uuid",
                id,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("notice_posting_two_signatories")
    }

    @Test
    fun `PM-GA-006 an agenda item added after posting returns the assembly to DRAFT, and the next act states the new agenda`() {
        val entranceId = entrance()
        val convenor = party()
        val owner = party()
        val base = "/api/assembly/entrances/$entranceId/assemblies"
        val id = created(base, convening(convenor)).get("id").asText()
        fun posting(at: Instant = Instant.now()) = """{"postedAt":"$at","coSignatoryPartyId":"$owner","photoHash":"${"3f".repeat(32)}"}"""
        mvc.perform(post("$base/$id/notice-posting").contentType(MediaType.APPLICATION_JSON).content(posting())).andExpect(status().isConflict)   // no agenda yet
        assertThat(created("$base/$id/agenda", """{"text":"Отчет","itemType":"GENERAL"}""").get("noticeVoided").asBoolean()).isFalse()
        val first = Instant.now()
        created("$base/$id/notice-posting", posting(first))

        assertThat(created("$base/$id/agenda", """{"text":"Ремонт на покрива","itemType":"GENERAL"}""").get("noticeVoided").asBoolean()).isTrue()
        val voided = read(base, id)
        assertThat(voided.get("status").asText()).isEqualTo("DRAFT")
        assertThat(voided.has("noticePostedAt")).isFalse()

        // the first posting stated one item: its time cannot serve the new agenda
        mvc.perform(post("$base/$id/notice-posting").contentType(MediaType.APPLICATION_JSON).content(posting(first))).andExpect(status().isBadRequest)
        assertThat(created("$base/$id/notice-posting", posting()).get("statedAgenda").asText()).isEqualTo("1. Отчет\n2. Ремонт на покрива")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.notice_posting WHERE assembly_id = ?::uuid", Int::class.java, id)).isEqualTo(2)

        // moving the meeting voids the notice too
        mvc.perform(
            post("$base/$id/schedule").contentType(MediaType.APPLICATION_JSON).content("""{"scheduledAt":"${meets.plusSeconds(3600)}","place":"двора"}"""),
        ).andExpect(status().isOk)
        assertThat(read(base, id).get("status").asText()).isEqualTo("DRAFT")
    }

    @Test
    fun `PM-GA-004 an assembly cannot be scheduled, or moved, closer than the notice period`() {
        val entranceId = entrance()
        val convenor = party()
        val base = "/api/assembly/entrances/$entranceId/assemblies"
        val tooSoon = Instant.now().plus(3, ChronoUnit.DAYS)
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(convening(convenor, at = tooSoon))).andExpect(status().isBadRequest)
        val id = created(base, convening(convenor)).get("id").asText()
        mvc.perform(post("$base/$id/schedule").contentType(MediaType.APPLICATION_JSON).content("""{"scheduledAt":"$tooSoon","place":"двора"}"""))
            .andExpect(status().isBadRequest)
        assertThat(read(base, id).get("scheduledAt").asText()).isEqualTo(meets.toString())
    }
}
