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
 * The issue through the real port, over real rows: the decision a run cites is one `assembly` stores for the entrance
 * (PM-FEE-012). GaDecisionsTest has the outcomes; a stored run's rows are ChargeRunPersistenceIT's.
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

    private fun send(path: String, body: String) = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))

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

    private fun issue(entrance: UUID, decisionId: String) = send(
        "/api/money/entrances/$entrance/charge-runs",
        json.writeValueAsString(
            StoredChargeRunRequest(
                "2026-05", "2026-05-01",
                lines = listOf(TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", decisionId, totalMinor = 10_000)),
            ),
        ),
    )

    @Test
    fun `PM-FEE-012 a run is issued on a paper decision of its entrance`() {
        val entrance = entrance()
        issue(entrance, paperDecision(entrance)).andExpect(status().isCreated)
        assertThat(chargeRuns.existsByEntranceIdAndPeriod(entrance, "2026-05")).isTrue()
    }

    @Test
    fun `PM-FEE-012 a run on a made-up id or on another entrance's decision is refused and nothing is issued`() {
        val entrance = entrance()
        val elsewhere = paperDecision(entrance())
        for (cited in listOf("GA-2026-03-12-4", UUID.randomUUID().toString(), elsewhere)) {
            val refused = issue(entrance, cited).andExpect(status().isBadRequest).andReturn().response.contentAsString
            assertThat(refused).contains(cited).contains("PM-FEE-012")
        }
        assertThat(chargeRuns.existsByEntranceIdAndPeriod(entrance, "2026-05")).isFalse()
    }
}
