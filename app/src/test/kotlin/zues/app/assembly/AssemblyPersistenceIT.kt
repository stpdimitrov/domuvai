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

    private fun convening(convenor: UUID, urgent: String = """"urgent":false""") =
        """{"convenedBy":"$convenor","convenedAs":"BM","scheduledAt":"2026-11-20T17:00:00Z","place":"фоайето на вх. А","mode":"IN_PERSON",$urgent}"""

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
        assertThat(read.get("scheduledAt").asText()).isEqualTo("2026-11-20T17:00:00Z")
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
}
