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
 * Fund-account persistence against real PostgreSQL. Proves the table's own guards: one account
 * per entrance per purpose and one IBAN across all entrances, so the fund is distinct from the
 * operating account (PM-FUND-004) and monies are not commingled (PM-FUND-005). Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class FundAccountPersistenceIT {

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

    private val ibanA = "BG80BNBG96611020345678"
    private val ibanB = "BG18RZBB91550123456789"

    private fun createEntrance(): UUID {
        val response = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON)
                .content("""{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("entranceId").asText())
    }

    private fun register(entranceId: UUID, iban: String, purpose: String) =
        mvc.perform(
            post("/api/money/entrances/$entranceId/fund-accounts").contentType(MediaType.APPLICATION_JSON)
                .content("""{"iban":"$iban","purpose":"$purpose","holderName":"Иван Петров","holderKind":"MANAGER"}"""),
        )

    @Test
    fun `PM-FUND-001 a repair-and-renewal fund account is established and read back`() {
        val entranceId = createEntrance()
        register(entranceId, ibanA, "REPAIR_RENEWAL").andExpect(status().isCreated)

        mvc.perform(get("/api/money/entrances/$entranceId/fund-accounts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].purpose").value("REPAIR_RENEWAL"))
            .andExpect(jsonPath("$[0].iban").value(ibanA))
    }

    @Test
    fun `PM-FUND-005 a second account of the same purpose for the entrance is refused`() {
        val entranceId = createEntrance()
        register(entranceId, ibanA, "REPAIR_RENEWAL").andExpect(status().isCreated)
        register(entranceId, ibanB, "REPAIR_RENEWAL").andExpect(status().isConflict)
    }

    @Test
    fun `PM-FUND-004 the fund cannot reuse the operating account's IBAN, but coexists on its own`() {
        val entranceId = createEntrance()
        register(entranceId, ibanA, "OPERATING").andExpect(status().isCreated)
        register(entranceId, ibanA, "REPAIR_RENEWAL").andExpect(status().isConflict)   // same IBAN — commingling
        register(entranceId, ibanB, "REPAIR_RENEWAL").andExpect(status().isCreated)    // distinct account, fine
    }
}
