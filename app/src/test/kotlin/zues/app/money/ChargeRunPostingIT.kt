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
 * Postings against real PostgreSQL: issuing a run writes a journal whose deferred trigger
 * (ADR-006) accepts it only if it balances to zero. A 201 plus a zero-sum journal proves the
 * double-entry end to end. Docker-gated — skips locally, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ChargeRunPostingIT {

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
    @Autowired lateinit var postings: PostingRepository

    private fun createEntrance(): UUID {
        val response = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON)
                .content("""{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("entranceId").asText())
    }

    private fun registerUnits(entranceId: UUID) {
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON).content(
                """{"units":[
                     {"designation":"ап. 1","unitType":"FLAT","idealParts":"60.0000","separateEntrance":false},
                     {"designation":"ап. 2","unitType":"FLAT","idealParts":"40.0000","separateEntrance":false}
                   ]}""",
            ),
        ).andExpect(status().isCreated)
    }

    @Test
    fun `issuing a run writes a balanced journal`() {
        val entranceId = createEntrance().also { registerUnits(it) }
        val request = json.writeValueAsString(
            StoredChargeRunRequest(
                period = "2026-05", legalDate = "2026-05-01",
                lines = listOf(
                    TariffLineRequest("MANAGEMENT", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000),
                    TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 20_000),
                ),
            ),
        )
        val response = mvc.perform(
            post("/api/money/entrances/$entranceId/charge-runs").contentType(MediaType.APPLICATION_JSON).content(request),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val runId = UUID.fromString(json.readTree(response).get("chargeRunId").asText())

        val journal = postings.findByJournalId(runId)
        assertThat(journal).isNotEmpty()
        assertThat(journal.sumOf { it.amountMinor }).isZero()   // the deferred trigger already required this
        assertThat(journal.filter { it.amountMinor < 0 }.map { it.account })
            .containsExactlyInAnyOrder("INCOME:MANAGEMENT", "INCOME:MAINTENANCE")
    }
}
