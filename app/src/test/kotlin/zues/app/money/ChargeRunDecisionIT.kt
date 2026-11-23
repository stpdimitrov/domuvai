package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
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
 * The issue through the real port, over real rows: a run bills the tariff version in force for its period, recorded on a
 * decision `assembly` stores for the entrance (PM-FEE-012). TariffVersionIT has the recording; a stored run's rows are
 * ChargeRunPersistenceIT's.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ChargeRunDecisionIT {

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

    /** Each write under a fresh `Idempotency-Key`, which the keyed ones (a paper decision among them) require (PM-SYS-014); the others ignore it. */
    private fun send(path: String, body: String) = mvc.perform(post(path).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun entrance(): UUID {
        val made = send("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""")
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val entrance = UUID.fromString(json.readTree(made).get("entranceId").asText())
        send(
            "/api/registry/entrances/$entrance/units",
            """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""",
        ).andExpect(status().isCreated)
        return entrance
    }

    private fun paperDecision(entrance: UUID): String {
        val recorded = send(
            "/api/assembly/entrances/$entrance/paper-decisions",
            """{"decisionText":"Вноска за поддръжка","protocolDate":"2026-03-12","protocolRef":"Протокол 1","archiveLocation":"Папка","custodian":"Мария Иванова"}""",
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(recorded).get("id").asText()
    }

    private fun adopt(entrance: UUID, decisionId: String, from: String, totalMinor: Long, to: String? = null): String = json.readTree(
        send(
            "/api/money/entrances/$entrance/tariff-versions",
            json.writeValueAsString(RecordTariffVersion(decisionId, from, to, listOf(AdoptedLine("MAINTENANCE", "BY_IDEAL_PARTS", totalMinor = totalMinor)))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString,
    ).get("id").asText()

    /** A run of [period] whose body also carries lines of its own, as an older client sent them: they bill nothing. */
    private fun issue(entrance: UUID, period: String) = send(
        "/api/money/entrances/$entrance/charge-runs",
        """{"period":"$period","legalDate":"$period-01","lines":[{"stream":"MAINTENANCE","key":"BY_IDEAL_PARTS","decisionId":"made-up","totalMinor":99999}]}""",
    )

    private fun billed(entrance: UUID, period: String): Long =
        json.readTree(issue(entrance, period).andExpect(status().isCreated).andReturn().response.contentAsString).get("totalMinor").asLong()

    @Test
    fun `PM-FEE-012 an issued run bills the version in force on a paper decision, not the caller's lines, and its basis names the version and its decision`() {
        val entrance = entrance()
        val decision = paperDecision(entrance)
        val version = adopt(entrance, decision, "2026-05", 10_000)
        val issued = json.readTree(issue(entrance, "2026-05").andExpect(status().isCreated).andReturn().response.contentAsString)
        assertThat(issued.get("totalMinor").asLong()).isEqualTo(10_000)
        val basis = chargeRuns.findById(UUID.fromString(issued.get("chargeRunId").asText())).orElseThrow().basis.json
        assertThat(basis).contains("\"versionId\":\"$version\"").contains("\"decisionId\":\"$decision\"").doesNotContain("made-up")
    }

    @Test
    fun `PM-FEE-012 a period with no version in force is refused and nothing is posted`() {
        val entrance = entrance()
        for (path in listOf("/api/money/entrances/$entrance/charge-runs/preview", "/api/money/entrances/$entrance/charge-runs")) {
            val refused = send(path, """{"period":"2026-05","legalDate":"2026-05-01"}""").andExpect(status().isBadRequest).andReturn().response.contentAsString
            assertThat(refused).contains("2026-05").contains("PM-FEE-012")
        }
        assertThat(chargeRuns.existsByEntranceIdAndPeriod(entrance, "2026-05")).isFalse()
    }

    @Test
    fun `PM-FEE-012 a run after a newer version begins bills the newer one, and a version whose validTo has passed is not used`() {
        val entrance = entrance()
        val decision = paperDecision(entrance)
        adopt(entrance, decision, "2026-01", 10_000, to = "2026-02")
        adopt(entrance, decision, "2026-04", 12_000)
        assertThat(billed(entrance, "2026-02")).isEqualTo(10_000)                                // its last month
        issue(entrance, "2026-03").andExpect(status().isBadRequest)                            // ended, and nothing falls back to it
        assertThat(chargeRuns.existsByEntranceIdAndPeriod(entrance, "2026-03")).isFalse()
        assertThat(billed(entrance, "2026-04")).isEqualTo(12_000)
    }
}
