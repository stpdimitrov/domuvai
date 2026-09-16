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
import org.springframework.test.web.servlet.ResultActions
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
 * Ownership persistence against real PostgreSQL. Proves the liable party resolves **as of** the
 * date across a sale (PM-ORG-011) and that the owners list never carries an identity number
 * (PM-BOOK-011). Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class OwnershipPersistenceIT {

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

    private fun registerSingleUnit(entranceId: UUID): UUID {
        val response = mvc.perform(
            post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON)
                .content("""{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("unitIds").get(0).asText())
    }

    private fun registerParty(fullName: String, idType: String, idValue: String): UUID {
        val response = mvc.perform(
            post("/api/registry/parties").contentType(MediaType.APPLICATION_JSON)
                .content("""{"fullName":"$fullName","idType":"$idType","idValue":"$idValue"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("partyId").asText())
    }

    private fun assignTitle(
        entranceId: UUID, unitId: UUID, partyId: UUID, validFrom: String, validTo: String?,
    ): ResultActions {
        val to = validTo?.let { "\"$it\"" } ?: "null"
        return mvc.perform(
            post("/api/registry/entrances/$entranceId/units/$unitId/titles").contentType(MediaType.APPLICATION_JSON)
                .content("""{"partyId":"$partyId","titleRole":"OWN","share":"1","validFrom":"$validFrom","validTo":$to}"""),
        )
    }

    @Test
    fun `PM-ORG-011 the owner resolves as of the date, across a sale`() {
        val entranceId = createEntrance()
        val unitId = registerSingleUnit(entranceId)
        val oldOwner = registerParty("Old Owner", "EGN", "7501010010")
        val newOwner = registerParty("New Owner", "EGN", "8002020020")
        assignTitle(entranceId, unitId, oldOwner, "2026-01-01", "2026-06-01").andExpect(status().isCreated)
        assignTitle(entranceId, unitId, newOwner, "2026-06-01", null).andExpect(status().isCreated)

        mvc.perform(get("/api/registry/entrances/$entranceId/owners").param("on", "2026-05-01"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].partyName").value("Old Owner"))

        mvc.perform(get("/api/registry/entrances/$entranceId/owners").param("on", "2026-07-01"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].partyName").value("New Owner"))
    }

    @Test
    fun `PM-BOOK-011 the owners list carries the name but not the identity number`() {
        val entranceId = createEntrance()
        val unitId = registerSingleUnit(entranceId)
        val party = registerParty("Иван Петров", "EGN", "7501010010")
        assignTitle(entranceId, unitId, party, "2026-01-01", null).andExpect(status().isCreated)

        val body = mvc.perform(get("/api/registry/entrances/$entranceId/owners").param("on", "2026-05-01"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(body).contains("Иван Петров")
        assertThat(body).doesNotContain("7501010010")   // the ЕГН never reaches a resident-visible list
    }
}
