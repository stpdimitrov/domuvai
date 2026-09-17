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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * A unit's statement against real PostgreSQL: issue a run, then read what a unit owes and the
 * itemised charges behind it (PM-FEE-018). The balance is the sum of the receivable postings the
 * run wrote. Docker-gated — skips locally, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class StatementIT {

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

    private fun createEntrance(): UUID {
        val response = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON)
                .content("""{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("entranceId").asText())
    }

    private fun registerUnits(entranceId: UUID): List<UUID> {
        val response = mvc.perform(
            post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON).content(
                """{"units":[
                     {"designation":"ап. 1","unitType":"FLAT","idealParts":"60.0000","separateEntrance":false},
                     {"designation":"ап. 2","unitType":"FLAT","idealParts":"40.0000","separateEntrance":false}
                   ]}""",
            ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(response).get("unitIds").map { UUID.fromString(it.asText()) }
    }

    @Test
    fun `PM-FEE-018 a unit's statement shows its ledger balance and itemised charges`() {
        val entranceId = createEntrance()
        val units = registerUnits(entranceId)
        mvc.perform(
            post("/api/money/entrances/$entranceId/charge-runs").contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(
                    StoredChargeRunRequest(
                        period = "2026-05", legalDate = "2026-05-01",
                        lines = listOf(
                            TariffLineRequest("MANAGEMENT", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000),
                            TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 20_000),
                        ),
                    ),
                ),
            ),
        ).andExpect(status().isCreated)

        // ап. 1 holds 60%: MANAGEMENT 6000 + MAINTENANCE 12000 = 18000
        mvc.perform(get("/api/money/units/${units[0]}/statement"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanceMinor").value(18_000))
            .andExpect(jsonPath("$.lines.length()").value(2))
            .andExpect(jsonPath("$.lines[0].period").value("2026-05"))
            .andExpect(jsonPath("$.lines[0].derivation").isNotEmpty())
    }
}
