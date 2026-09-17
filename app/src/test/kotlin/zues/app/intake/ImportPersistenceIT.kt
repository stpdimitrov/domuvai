package zues.app.intake

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
 * Import persistence against real PostgreSQL: record a fee sheet and read the import back with its
 * verdict and provenance. The sheet carries its own units, so no registry units are needed — the
 * entrance is only the record's owner. Docker-gated — skips locally, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ImportPersistenceIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            val schemas = "registry,identity_org,assembly,money,maintenance,compliance,evidence,app,intake,public"
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

    private fun body(feeA: Long, feeB: Long) =
        """{"period":"2026-05","legalDate":"2026-05-01",
            "lines":[{"stream":"MAINTENANCE","key":"BY_IDEAL_PARTS","decisionId":"GA-2026-1","totalMinor":10000}],
            "csv":"designation,ideal_parts,occupants,fee_minor\nап. 1,60.0000,2,$feeA\nап. 2,40.0000,1,$feeB"}"""

    @Test
    fun `a reproduced import is recorded and read back`() {
        val entranceId = createEntrance()
        val created = mvc.perform(
            post("/api/intake/entrances/$entranceId/imports").contentType(MediaType.APPLICATION_JSON).content(body(6000, 4000)),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.report.reproduced").value(true))
            .andReturn().response.contentAsString
        val importId = json.readTree(created).get("importId").asText()

        mvc.perform(get("/api/intake/imports/$importId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REPRODUCED"))
            .andExpect(jsonPath("$.rowsParsed").value(2))
            .andExpect(jsonPath("$.differing").value(0))
            .andExpect(jsonPath("$.sourceSha").isNotEmpty())
    }

    @Test
    fun `an import that is one cent off is recorded as NEEDS_REVIEW`() {
        val entranceId = createEntrance()
        val created = mvc.perform(
            post("/api/intake/entrances/$entranceId/imports").contentType(MediaType.APPLICATION_JSON).content(body(6001, 4000)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val importId = json.readTree(created).get("importId").asText()

        mvc.perform(get("/api/intake/imports/$importId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
            .andExpect(jsonPath("$.differing").value(1))
    }
}
