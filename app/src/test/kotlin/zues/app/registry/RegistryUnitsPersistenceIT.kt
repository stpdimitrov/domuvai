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
    @Autowired lateinit var dataSource: javax.sql.DataSource

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

    @Test
    fun `PM-ORG-009 business use and a separate entrance persist as two facts of a unit`() {
        val entranceId = createEntrance()
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON).content(
                """{"units":[
                     {"designation":"магазин 1","unitType":"FLAT","idealParts":"30.0000","businessUse":true},
                     {"designation":"магазин 2","unitType":"FLAT","idealParts":"30.0000","businessUse":true,"separateEntrance":true},
                     {"designation":"ап. 1","unitType":"FLAT","idealParts":"20.0000","separateEntrance":true},
                     {"designation":"ап. 2","unitType":"FLAT","idealParts":"20.0000"}
                   ]}""",
            ),
        ).andExpect(status().isCreated)

        fun fact(designation: String, name: String) = "$[?(@.designation == '$designation')].$name"
        mvc.perform(get("/api/registry/entrances/$entranceId/units"))
            .andExpect(status().isOk)
            .andExpect(jsonPath(fact("магазин 1", "businessUse")).value(true))       // through the common parts
            .andExpect(jsonPath(fact("магазин 1", "separateEntrance")).value(false))
            .andExpect(jsonPath(fact("магазин 2", "businessUse")).value(true))       // with its own street entrance
            .andExpect(jsonPath(fact("магазин 2", "separateEntrance")).value(true))
            .andExpect(jsonPath(fact("ап. 1", "businessUse")).value(false))      // its own entrance, no business
            .andExpect(jsonPath(fact("ап. 1", "separateEntrance")).value(true))
            .andExpect(jsonPath(fact("ап. 2", "businessUse")).value(false))
            .andExpect(jsonPath(fact("ап. 2", "separateEntrance")).value(false))
    }

    private fun postUnits(entranceId: String, units: String) = mvc.perform(
        post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON).content("""{"units":[$units]}"""),
    )

    @Test
    fun `PM-ORG-003 units without ideal parts get them from the area ratio, marked DERIVED, summing to 100`() {
        val entranceId = createEntrance()
        postUnits(
            entranceId,
            """{"designation":"ап. 1","unitType":"FLAT","areaM2":70},{"designation":"ап. 2","unitType":"FLAT","areaM2":70},""" +
                """{"designation":"ап. 3","unitType":"FLAT","areaM2":70}""",
        ).andExpect(status().isCreated)

        fun unit(designation: String, field: String) = "$[?(@.designation == '$designation')].$field"
        mvc.perform(get("/api/registry/entrances/$entranceId/units"))
            .andExpect(status().isOk)
            .andExpect(jsonPath(unit("ап. 1", "idealPartsPct")).value(33.3334))
            .andExpect(jsonPath(unit("ап. 2", "idealPartsPct")).value(33.3333))
            .andExpect(jsonPath(unit("ап. 3", "idealPartsPct")).value(33.3333))
            .andExpect(jsonPath("$[*].idealPartsSource").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("DERIVED"))))
    }

    @Test
    fun `PM-ORG-003 declared ideal parts stay DECLARED`() {
        val entranceId = createEntrance()
        postUnits(entranceId, """{"designation":"ап. 1","unitType":"FLAT","areaM2":70,"idealParts":"100.0000"}""").andExpect(status().isCreated)
        mvc.perform(get("/api/registry/entrances/$entranceId/units"))
            .andExpect(jsonPath("$[0].idealPartsSource").value("DECLARED"))
            .andExpect(jsonPath("$[0].idealPartsPct").value(100.0))
    }

    @Test
    fun `PM-ORG-003 no derivation is guessed for a mixed set, a unit without area, or an entrance that already has units`() {
        val mixed = createEntrance()
        postUnits(mixed, """{"designation":"ап. 1","unitType":"FLAT","areaM2":70,"idealParts":"50.0000"},{"designation":"ап. 2","unitType":"FLAT","areaM2":70}""")
            .andExpect(status().isBadRequest)
        val noArea = createEntrance()
        postUnits(noArea, """{"designation":"ап. 1","unitType":"FLAT","areaM2":70},{"designation":"ап. 2","unitType":"FLAT"}""")
            .andExpect(status().isBadRequest)
        val filled = createEntrance()
        postUnits(filled, """{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000"}""").andExpect(status().isCreated)
        postUnits(filled, """{"designation":"ап. 2","unitType":"FLAT","areaM2":70}""").andExpect(status().isBadRequest)

        listOf(mixed, noArea).forEach {
            mvc.perform(get("/api/registry/entrances/$it/units")).andExpect(jsonPath("$.length()").value(0))
        }
        mvc.perform(get("/api/registry/entrances/$filled/units")).andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `PM-ORG-002 a unit set waits for any other write holding its entrance, so two sets cannot both make 100%`() {
        val entranceId = createEntrance()
        dataSource.connection.use { other ->
            other.autoCommit = false
            // the lock another unit-set write holds; it does not block a foreign-key check, so only
            // the service's own lock can make the write below wait
            other.prepareStatement("SELECT id FROM registry.entrance WHERE id = ?::uuid FOR NO KEY UPDATE").use {
                it.setString(1, entranceId); it.executeQuery()
            }
            val write = java.util.concurrent.CompletableFuture.supplyAsync {
                postUnits(entranceId, """{"designation":"ап. 1","unitType":"FLAT","areaM2":70}""").andReturn().response.status
            }
            Thread.sleep(1_500)
            org.assertj.core.api.Assertions.assertThat(write.isDone).describedAs("the write must wait for the entrance lock").isFalse()
            other.rollback()
            org.assertj.core.api.Assertions.assertThat(write.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(201)
        }
    }
}
