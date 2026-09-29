package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * Money persistence against real PostgreSQL: compute from registry units → store the jsonb
 * basis and the charge lines → prove they are immutable (PM-FEE-015). Exercises the jsonb
 * converter and the FKs, which cannot run without Docker. `disabledWithoutDocker` skips it
 * locally; it runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ChargeRunPersistenceIT {

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
    @Autowired lateinit var chargeRuns: ChargeRunRepository
    @Autowired lateinit var chargeLines: ChargeLineRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    private val runRequest = StoredChargeRunRequest(
        period = "2026-05", legalDate = "2026-05-01",
        lines = listOf(TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
    )

    private fun createEntrance(): UUID {
        val body = """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""
        val response = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("entranceId").asText())
    }

    private fun registerUnits(entranceId: UUID) {
        val body = """
            {"units":[
              {"designation":"ап. 1","unitType":"FLAT","idealParts":"60.0000","separateEntrance":false},
              {"designation":"ап. 2","unitType":"FLAT","idealParts":"40.0000","separateEntrance":false}
            ]}
        """.trimIndent()
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated)
    }

    private fun issueRun(entranceId: UUID): UUID {
        val response = mvc.perform(
            post("/api/money/entrances/$entranceId/charge-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(runRequest)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("chargeRunId").asText())
    }

    @Test
    fun `issuing a run stores the basis and one typed line per unit`() {
        val entranceId = createEntrance().also { registerUnits(it) }
        val runId = issueRun(entranceId)

        val run = chargeRuns.findById(runId).orElseThrow()
        assertThat(run.basisHash).isNotBlank()
        assertThat(run.basis.json).contains("MAINTENANCE")   // the jsonb round-tripped
        assertThat(run.status).isEqualTo("COMPLETE")

        val lines = chargeLines.findByChargeRunId(runId)
        assertThat(lines).hasSize(2)
        assertThat(lines.map { it.component }).containsOnly("MAINTENANCE")
        assertThat(lines.sumOf { it.amountMinor }).isEqualTo(10_000)
    }

    @Test
    fun `PM-FEE-011 PM-FEE-003 a concierge line is stored as a second maintenance line, and every line names its decision`() {
        val entranceId = createEntrance().also { registerUnits(it) }
        val withConcierge = StoredChargeRunRequest(
            period = "2026-06", legalDate = "2026-06-01",
            lines = listOf(
                TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000),
                TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-2", totalMinor = 5_000, item = "CONCIERGE"),
            ),
        )
        val response = mvc.perform(
            post("/api/money/entrances/$entranceId/charge-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(withConcierge)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val lines = chargeLines.findByChargeRunId(UUID.fromString(json.readTree(response).get("chargeRunId").asText()))

        assertThat(lines).hasSize(4)                                             // two per unit, one of them the concierge
        assertThat(lines.map { it.component }).containsOnly("MAINTENANCE")
        assertThat(lines.filter { it.item == "CONCIERGE" }.sumOf { it.amountMinor }).isEqualTo(5_000)
        assertThat(lines.map { it.decisionId }).containsOnly("GA-2026-1", "GA-2026-2")
    }

    @Test
    fun `PM-FEE-011 PM-FEE-003 the schema refuses a concierge line outside maintenance, and a new line without its decision`() {
        val entranceId = createEntrance().also { registerUnits(it) }
        val line = chargeLines.findByChargeRunId(issueRun(entranceId)).first()
        fun insert(component: String, item: String?, decision: String?) = jdbc.update(
            "INSERT INTO charge_line (id, entrance_id, charge_run_id, unit_id, component, allocation_key, quantity, " +
                "amount_minor, currency, derivation, item, decision_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'EUR', 'probe', ?, ?)",
            UUID.randomUUID(), line.entranceId, line.chargeRunId, line.unitId, component, line.allocationKey, line.quantity, 1L, item, decision,
        )
        assertThatThrownBy { insert("REPAIR_FUND", "CONCIERGE", "GA-2026-1") }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insert("MANAGEMENT", null, null) }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(insert("MANAGEMENT", null, "GA-2026-9")).isEqualTo(1)       // the same row with its decision is stored
    }

    @Test
    fun `PM-FEE-015 an issued charge line cannot be updated`() {
        val entranceId = createEntrance().also { registerUnits(it) }
        val line = chargeLines.findByChargeRunId(issueRun(entranceId)).first()

        jdbc.update("UPDATE charge_line SET amount_minor = 999999 WHERE id = ?", line.id)

        assertThat(chargeLines.findById(line.id).orElseThrow().amountMinor).isEqualTo(line.amountMinor)
    }

    @Test
    fun `a second run for the same period is refused`() {
        val entranceId = createEntrance().also { registerUnits(it) }
        issueRun(entranceId)

        mvc.perform(
            post("/api/money/entrances/$entranceId/charge-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(runRequest)),
        ).andExpect(status().isConflict)
    }
}
