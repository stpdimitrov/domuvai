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
 * Arrears ageing against real PostgreSQL: issue a run, then read a unit's arrears as of a date and
 * see the outstanding land in the right band (PM-DEBT-001). Docker-gated — skips locally, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ArrearsIT {

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
    fun `PM-DEBT-001 a unit's outstanding is aged as of a date`() {
        val entranceId = createEntrance()
        val units = registerUnits(entranceId)
        // charge raised at value date 2026-05-01; due 14 days later, on 2026-05-15
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

        // 2026-05-20 is 5 days past due -> the whole 18000 (ап. 1, 60%) sits in the 0-30 band
        mvc.perform(get("/api/money/units/${units[0]}/arrears").param("asOf", "2026-05-20"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(18_000))
            .andExpect(jsonPath("$.buckets[1].band").value("0-30"))
            .andExpect(jsonPath("$.buckets[1].amountMinor").value(18_000))
            .andExpect(jsonPath("$.buckets[4].amountMinor").value(0))
    }
}
