package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
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
import java.time.LocalDate
import java.util.UUID

/**
 * The repair fund against real PostgreSQL: money paid into the fund's account is its balance, a
 * signed-off disbursement is committed and no longer available (PM-FUND-009), an emergency beyond the
 * available balance is refused (PM-FUND-008), and the table itself refuses a disbursement with neither a
 * decision nor an emergency (PM-FUND-007). Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class FundPersistenceIT {

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
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun postFor(path: String, body: String, field: String): UUID {
        val response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val node = json.readTree(response).get(field)
        return UUID.fromString((if (node.isArray) node.get(0) else node).asText())
    }

    private fun disburse(entranceId: UUID, body: String) =
        mvc.perform(post("/api/money/entrances/$entranceId/fund/disbursements").contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `PM-FUND-007 PM-FUND-008 PM-FUND-009 money in is the balance, a signed-off disbursement is committed, an emergency is capped`() {
        val entranceId = postFor("/api/registry/entrances", """{"address":"ул. Оборище 12","label":"А","managementForm":"GA"}""", "entranceId")
        val unitId = postFor(
            "/api/registry/entrances/$entranceId/units",
            """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""", "unitIds",
        )
        val chair = postFor("/api/registry/parties", """{"fullName":"Иван Петров"}""", "partyId")
        val iban = "BG80BNBG${"%014d".format(System.nanoTime() % 100_000_000_000_000)}"
        postFor(
            "/api/money/entrances/$entranceId/fund-accounts",
            """{"iban":"$iban","purpose":"REPAIR_RENEWAL","holderName":"Иван Петров","holderKind":"MANAGER","holderPartyId":"$chair"}""", "fundAccountId",
        )
        mvc.perform(
            post("/api/money/entrances/$entranceId/payments").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"unitId":"$unitId","amountMinor":50000,"valueDate":"2026-09-01","receivedInto":"REPAIR_RENEWAL"}"""),
        ).andExpect(status().isCreated)

        disburse(entranceId, """{"amountMinor":20000,"purpose":"WORKS","authorisedBy":"$chair","decisionId":"GA-2026-7"}""")
            .andExpect(status().isCreated)
        disburse(entranceId, """{"amountMinor":30001,"purpose":"WORKS","authorisedBy":"$chair","emergencyJustification":"the roof leaks"}""")
            .andExpect(status().isConflict)                                          // 30 000 is available
        disburse(entranceId, """{"amountMinor":30000,"purpose":"WORKS","authorisedBy":"$chair","emergencyJustification":"the roof leaks"}""")
            .andExpect(status().isCreated)

        mvc.perform(get("/api/money/entrances/$entranceId/fund"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanceMinor").value(50_000))
            .andExpect(jsonPath("$.committedMinor").value(50_000))
            .andExpect(jsonPath("$.availableMinor").value(0))
            .andExpect(jsonPath("$.disbursements.length()").value(2))

        val fundAccountId = jdbc.queryForObject("SELECT fund_account_id FROM fund_disbursement WHERE entrance_id = ? LIMIT 1", UUID::class.java, entranceId)
        assertThatThrownBy {                                                          // neither a decision nor an emergency
            jdbc.update(
                "INSERT INTO fund_disbursement (id, entrance_id, fund_account_id, amount_minor, purpose, authorised_by, status, committed_on) " +
                    "VALUES (?, ?, ?, 100, 'WORKS', ?, 'COMMITTED', ?)",
                UUID.randomUUID(), entranceId, fundAccountId, chair, LocalDate.parse("2026-09-29"),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
