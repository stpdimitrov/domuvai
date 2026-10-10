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
 * The owners' petition against real PostgreSQL and the real registry: titles written through the registry are
 * what the petition weighs, and the database holds what the service decided. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PetitionPersistenceIT {

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

    /** Four units of 12.5, 7.5, 30 and 50 percent, each with its owner from the start of the year. */
    private fun owned(entranceId: UUID): List<Pair<UUID, UUID>> {
        val units = created(
            "/api/registry/entrances/$entranceId/units",
            """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"12.5000","separateEntrance":false},
                         {"designation":"ап. 2","unitType":"FLAT","idealParts":"7.5000","separateEntrance":false},
                         {"designation":"ап. 3","unitType":"FLAT","idealParts":"30.0000","separateEntrance":false},
                         {"designation":"ап. 4","unitType":"FLAT","idealParts":"50.0000","separateEntrance":false}]}""",
        ).get("unitIds").map { UUID.fromString(it.asText()) }
        return units.map { unit -> party().also { title(entranceId, unit, it, "OWN") } to unit }
    }

    private fun party() = UUID.fromString(created("/api/registry/parties", """{"fullName":"Иван Петров"}""").get("partyId").asText())

    private fun title(entranceId: UUID, unitId: UUID, partyId: UUID, role: String) = created(
        "/api/registry/entrances/$entranceId/units/$unitId/titles", """{"partyId":"$partyId","titleRole":"$role","share":"1","validFrom":"2026-01-01"}""",
    )

    private fun meeting(by: UUID) =
        """{"convenedBy":"$by","scheduledAt":"${Instant.now().plus(30, ChronoUnit.DAYS)}","place":"фоайето на вх. А","mode":"IN_PERSON","demandUnmet":"Искането е връчено на управителя; събрание не е свикано."}"""

    @Test
    fun `PM-GA-003 owners' signatures accumulate to the threshold, and one of them convenes the assembly the database records as theirs`() {
        val entranceId = entrance()
        val (first, second, third, _) = owned(entranceId).map { it.first }
        val base = "/api/assembly/entrances/$entranceId/petitions"

        val opened = created(base, """{"openedBy":"$first","subject":"Ремонт на покрива"}""")
        val id = opened.get("id").asText()
        assertThat(opened.get("heldPct").asText()).isEqualTo("12.5")
        assertThat(opened.get("unlocked").asBoolean()).isFalse()
        mvc.perform(post("$base/$id/assembly").contentType(MediaType.APPLICATION_JSON).content(meeting(first))).andExpect(status().isConflict)

        val signed = created("$base/$id/signatures", """{"partyId":"$second"}""")
        assertThat(signed.get("heldPct").asText()).isEqualTo("20")                    // 12.5 + 7.5: exactly the threshold
        assertThat(signed.get("thresholdPct").asText()).isEqualTo("20")
        assertThat(signed.get("thresholdVerified").asBoolean()).isFalse()
        assertThat(signed.get("unlocked").asBoolean()).isTrue()
        mvc.perform(post("$base/$id/signatures").contentType(MediaType.APPLICATION_JSON).content("""{"partyId":"$second"}""")).andExpect(status().isConflict)
        mvc.perform(post("$base/$id/signatures").contentType(MediaType.APPLICATION_JSON).content("""{"partyId":"${party()}"}""")).andExpect(status().isBadRequest)
        mvc.perform(post("$base/$id/assembly").contentType(MediaType.APPLICATION_JSON).content(meeting(third))).andExpect(status().isBadRequest)   // not a signatory

        val convened = created("$base/$id/assembly", meeting(second))
        assertThat(convened.get("convenedAs").asText()).isEqualTo("OWNERS")
        val assemblyId = convened.get("assemblyId").asText()
        val row = jdbc.queryForMap("SELECT * FROM assembly.assembly WHERE id = ?::uuid", assemblyId)
        assertThat(row["petition_id"].toString()).isEqualTo(id)
        assertThat(row["convened_by"]).isEqualTo(second)
        val unlock = jdbc.queryForMap("SELECT * FROM assembly.petition_unlock WHERE petition_id = ?::uuid", id)
        assertThat(unlock["held_pct"] as java.math.BigDecimal).isEqualByComparingTo("20")
        assertThat(unlock["threshold_pct"] as java.math.BigDecimal).isEqualByComparingTo("20")
        assertThat(unlock["threshold_constant"] as String).startsWith("GA_PETITION_MIN_PCT@")
        assertThat(unlock["threshold_verified"]).isEqualTo(false)
        assertThat(listOf(unlock["law_version"], unlock["engine_version"])).doesNotContainNull()
        assertThat(unlock["demand_unmet_note"] as String).startsWith("Искането е връчено")
        assertThat(json.readTree(mvc.perform(get("$base/$id")).andReturn().response.contentAsString).get("assemblyId").asText()).isEqualTo(assemblyId)

        // once: no second assembly on the petition, by the service or by hand; and no signature after it
        mvc.perform(post("$base/$id/assembly").contentType(MediaType.APPLICATION_JSON).content(meeting(first))).andExpect(status().isConflict)
        mvc.perform(post("$base/$id/signatures").contentType(MediaType.APPLICATION_JSON).content("""{"partyId":"$third"}""")).andExpect(status().isConflict)
        val copy = "INSERT INTO assembly.assembly(id, entrance_id, convened_by, convened_as, place, scheduled_at, mode, status, notice_content_changed_at, petition_id) " +
            "SELECT gen_random_uuid(), %s, convened_by, %s, place, scheduled_at, mode, status, notice_content_changed_at, %s FROM assembly.assembly WHERE id = '$assemblyId'"
        fun refused(entrance: String, convenedAs: String, petition: String, constraint: String) =
            assertThatThrownBy { jdbc.update(copy.format(entrance, convenedAs, petition)) }
                .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining(constraint)
        refused("entrance_id", "convened_as", "petition_id", "assembly_one_per_petition")
        refused("entrance_id", "'OWNERS'", "NULL", "assembly_owners_convene_on_petition")               // the owners, on no petition
        refused("entrance_id", "'BM'", "petition_id", "assembly_owners_convene_on_petition")            // an office, on a petition
        val another = created(base, """{"openedBy":"$third","subject":"Друго"}""").get("id").asText()    // 30%: open, never convened on
        refused("entrance_id", "'OWNERS'", "'$another'", "assembly_on_an_unlocked_petition")            // a petition nothing unlocked
        assertThat(jdbc.update(copy.format("entrance_id", "'BM'", "NULL"))).isEqualTo(1)                // the control: an ordinary copy goes in

        // the unlock record: at or above its own threshold, of its own entrance's petition, and not rewritten
        val record = "INSERT INTO assembly.petition_unlock SELECT %s, %s, convened_by, demand_unmet_note, weighed_on, %s, threshold_pct, threshold_constant, " +
            "threshold_verified, law_version, engine_version, recorded_at FROM assembly.petition_unlock WHERE petition_id = '$id'"
        assertThatThrownBy { jdbc.update(record.format("'$another'", "entrance_id", "19.99")) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("petition_unlock_at_threshold")
        assertThatThrownBy { jdbc.update(record.format("'$another'", "'${entrance()}'", "held_pct")) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("petition_unlock_petition_id_entrance_id_fkey")
        jdbc.update("UPDATE assembly.petition_unlock SET held_pct = 99 WHERE petition_id = ?::uuid", id)
        jdbc.update("DELETE FROM assembly.petition_unlock WHERE petition_id = ?::uuid", id)
        assertThat(jdbc.queryForObject("SELECT held_pct FROM assembly.petition_unlock WHERE petition_id = ?::uuid", java.math.BigDecimal::class.java, id))
            .isEqualByComparingTo("20")

        // what was signed is not rewritten under its signatures
        jdbc.update("UPDATE assembly.petition SET subject = 'Друго' WHERE id = ?::uuid", id)
        jdbc.update("DELETE FROM assembly.petition WHERE id = ?::uuid", another)
        assertThat(jdbc.queryForObject("SELECT subject FROM assembly.petition WHERE id = ?::uuid", String::class.java, id)).isEqualTo("Ремонт на покрива")

        // signatures are evidence
        jdbc.update("UPDATE assembly.petition_signature SET party_id = ? WHERE petition_id = ?::uuid", third, id)
        jdbc.update("DELETE FROM assembly.petition_signature WHERE petition_id = ?::uuid", id)
        assertThat(jdbc.queryForList("SELECT party_id FROM assembly.petition_signature WHERE petition_id = ?::uuid ORDER BY signed_at", UUID::class.java, id))
            .containsExactly(first, second)
        assertThatThrownBy { jdbc.execute("TRUNCATE assembly.petition_signature") }.hasMessageContaining("is not emptied")
        // another entrance does not see the petition
        mvc.perform(get("/api/assembly/entrances/${entrance()}/petitions/$id")).andExpect(status().isNotFound)
    }

    @Test
    fun `PM-GA-003 a unit that also has a holder of use is not weighed - the petition says why and nothing is convened`() {
        val entranceId = entrance()
        val owners = owned(entranceId)
        val (third, thirdUnit) = owners[2]                                           // 30%: past the threshold alone
        val base = "/api/assembly/entrances/$entranceId/petitions"
        val id = created(base, """{"openedBy":"$third","subject":"Ремонт на покрива"}""").get("id").asText()
        fun read() = json.readTree(mvc.perform(get("$base/$id")).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(read().get("unlocked").asBoolean()).isTrue()                      // owners only: weighed in full

        title(entranceId, thirdUnit, party(), "USR")                                 // a right of use over the same unit
        val weighed = read()
        assertThat(weighed.get("heldPct").asText()).isEqualTo("0")                    // the unit is left out of the figure
        assertThat(weighed.get("unlocked").asBoolean()).isFalse()
        assertThat(weighed.get("cannotWeigh").single().asText()).contains(thirdUnit.toString()).contains("TODO(legal): PM-GA-003")
        val refusal = mvc.perform(post("$base/$id/assembly").contentType(MediaType.APPLICATION_JSON).content(meeting(third)))
            .andExpect(status().isConflict).andReturn().response.contentAsString
        assertThat(json.readTree(refusal).get("error").asText()).contains("cannot be weighed").contains(thirdUnit.toString())
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.petition_unlock WHERE petition_id = ?::uuid", Int::class.java, id)).isZero()
    }
}
