package zues.app.registry

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
 * Household persistence against real PostgreSQL, verified through the same port money reads:
 * registering members updates the occupant and children counts the adapter derives from the
 * `household_member` rows. Docker-gated — skips locally, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class HouseholdPersistenceIT {

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
    @Autowired lateinit var units: Units

    private fun createEntrance(): UUID {
        val body = """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""
        val response = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("entranceId").asText())
    }

    private fun registerSingleUnit(entranceId: UUID): UUID {
        val body = """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}"""
        val response = mvc.perform(
            post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("unitIds").get(0).asText())
    }

    @Test
    fun `registering household members updates the occupancy the port exposes`() {
        val entranceId = createEntrance()
        val unitId = registerSingleUnit(entranceId)

        val members = """{"members":[{"isChildUnder6":false},{"isChildUnder6":false},{"isChildUnder6":true}]}"""
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units/$unitId/household")
                .contentType(MediaType.APPLICATION_JSON).content(members),
        ).andExpect(status().isCreated)

        val unit = units.forEntrance(entranceId).single { it.unitId == unitId }
        assertThat(unit.occupants).isEqualTo(3)
        assertThat(unit.childrenUnder6).isEqualTo(1)
    }
}
