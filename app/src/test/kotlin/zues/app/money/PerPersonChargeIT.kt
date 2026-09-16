package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * The occupancy chain end to end against real PostgreSQL: register a household in registry,
 * then a per-person charge in money bills on that headcount, read back through the port.
 * Docker-gated — skips locally, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PerPersonChargeIT {

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

    private fun post(path: String, body: String) =
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun createEntrance(): UUID {
        val response = post("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""")
            .andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("entranceId").asText())
    }

    private fun registerSingleUnit(entranceId: UUID): UUID {
        val response = post(
            "/api/registry/entrances/$entranceId/units",
            """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""",
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("unitIds").get(0).asText())
    }

    @Test
    fun `a per-person charge bills on the registered household headcount`() {
        val entranceId = createEntrance()
        val unitId = registerSingleUnit(entranceId)
        post(
            "/api/registry/entrances/$entranceId/units/$unitId/household",
            """{"members":[
                {"isChildUnder6":false,"validFrom":"2026-01-01"},
                {"isChildUnder6":false,"validFrom":"2026-01-01"},
                {"isChildUnder6":false,"validFrom":"2026-01-01"}
            ]}""",
        ).andExpect(status().isCreated)

        val request = json.writeValueAsString(
            StoredChargeRunRequest(
                period = "2026-05", legalDate = "2026-05-01",
                lines = listOf(TariffLineRequest("MANAGEMENT", "PER_PERSON", "GA-2026-1", rateMinor = 500)),
            ),
        )
        post("/api/money/entrances/$entranceId/charge-runs/preview", request)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(1500))   // 500 × 3 occupants
    }
}
