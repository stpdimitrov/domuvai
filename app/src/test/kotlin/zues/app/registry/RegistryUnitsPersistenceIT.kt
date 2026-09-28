package zues.app.registry

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
 * Units persisted against real PostgreSQL: HTTP → service → Spring Data JDBC → the schema
 * (Flyway V1), including the `numeric(7,4)` ideal-parts column and the entrance FK.
 * `disabledWithoutDocker = true` skips it where no Docker daemon exists (this machine, so
 * the gate pack stays green locally); it runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class RegistryUnitsPersistenceIT {

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

    private fun createEntrance(): String {
        val body = json.writeValueAsString(RegisterEntranceRequest("ул. Раковски 1", "А", "GA"))
        val response = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(response).get("entranceId").asText()
    }

    private fun register(request: RegisterEntranceRequest) = json.readTree(
        mvc.perform(post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
            .andExpect(status().isCreated).andReturn().response.contentAsString,
    )

    @Test
    fun `PM-ORG-001 a building with three entrances keeps three independent entrances, each with its own 100%`() {
        val first = register(RegisterEntranceRequest("ул. Шипка 14", "А", "GA"))
        val condominiumId = UUID.fromString(first.get("condominiumId").asText())
        val entrances = listOf(first.get("entranceId").asText()) +
            listOf("Б", "В").map { register(RegisterEntranceRequest(null, it, "GA", condominiumId)).get("entranceId").asText() }

        // Each entrance holds its own 100% (PM-ORG-002 per entrance): three full sets are lawful.
        entrances.forEach { entranceId ->
            mvc.perform(
                post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON).content(
                    json.writeValueAsString(
                        RegisterUnitsRequest(listOf(NewUnitRequest("ап. 1", "FLAT", null, "60.0000", false), NewUnitRequest("ап. 2", "FLAT", null, "40.0000", false))),
                    ),
                ),
            ).andExpect(status().isCreated)
        }
        mvc.perform(get("/api/registry/entrances")).andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.condominiumId == '$condominiumId')]", org.hamcrest.Matchers.hasSize<Any>(3)))

        // A label is unique within its building: a second "Б" is refused, not a server error.
        mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(RegisterEntranceRequest(null, "Б", "GA", condominiumId))),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `a valid unit set persists and round-trips through the schema`() {
        val entranceId = createEntrance()
        val units = RegisterUnitsRequest(
            listOf(
                NewUnitRequest("ап. 1", "FLAT", null, "60.0000", false),
                NewUnitRequest("ап. 2", "FLAT", null, "40.0000", true),
            ),
        )
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(units)),
        ).andExpect(status().isCreated)

        mvc.perform(get("/api/registry/entrances/$entranceId/units"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].idealPartsPct").exists())
    }
}
