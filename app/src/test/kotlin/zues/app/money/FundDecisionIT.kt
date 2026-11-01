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
 * The sign-off through the real port, over real rows: the decision a disbursement cites is one `assembly` stores for the
 * entrance (PM-FUND-007), and an emergency cites none (PM-FUND-008). GaDecisionsTest has the outcomes; the fund's rows
 * are FundPersistenceIT's.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class FundDecisionIT {

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
    @Autowired lateinit var disbursements: FundDisbursementRepository

    private fun send(path: String, body: String) = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun created(path: String, body: String, field: String): String {
        val response = send(path, body).andExpect(status().isCreated).andReturn().response.contentAsString
        val node = json.readTree(response).get(field)
        return (if (node.isArray) node.get(0) else node).asText()
    }

    /** An entrance with one flat, and money in a repair fund account held by its chair. */
    private fun entranceWithFund(): UUID {
        val entrance = created("/api/registry/entrances", """{"address":"ул. Оборище 12","label":"А","managementForm":"GA"}""", "entranceId")
        val unit = created(
            "/api/registry/entrances/$entrance/units",
            """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""", "unitIds",
        )
        val chair = created("/api/registry/parties", """{"fullName":"Иван Петров"}""", "partyId")
        val iban = "BG80BNBG${"%014d".format(System.nanoTime() % 100_000_000_000_000)}"
        created(
            "/api/money/entrances/$entrance/fund-accounts",
            """{"iban":"$iban","purpose":"REPAIR_RENEWAL","holderName":"Иван Петров","holderKind":"ASSOCIATION","holderPartyId":"$chair"}""", "fundAccountId",
        )
        mvc.perform(
            post("/api/money/entrances/$entrance/payments").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"unitId":"$unit","amountMinor":50000,"valueDate":"2026-09-01","receivedInto":"REPAIR_RENEWAL"}"""),
        ).andExpect(status().isCreated)
        return UUID.fromString(entrance)
    }

    private fun paperDecision(entrance: UUID) = created(
        "/api/assembly/entrances/$entrance/paper-decisions",
        """{"decisionText":"Ремонт на покрива","protocolDate":"2026-03-12","protocolRef":"Протокол 1","archiveLocation":"Папка","custodian":"Мария Иванова"}""",
        "id",
    )

    private fun disburse(entrance: UUID, body: String, key: String = UUID.randomUUID().toString()) = mvc.perform(
        post("/api/money/entrances/$entrance/fund/disbursements").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body),
    )

    private fun onDecision(id: String) = """{"amountMinor":20000,"purpose":"WORKS","decisionId":"$id"}"""

    @Test
    fun `PM-FUND-007 a disbursement is signed off on a paper decision of its entrance`() {
        val entrance = entranceWithFund()
        val decision = paperDecision(entrance)
        disburse(entrance, onDecision(decision)).andExpect(status().isCreated)
        assertThat(disbursements.findByEntranceId(entrance).single().decisionId).isEqualTo(decision)
    }

    @Test
    fun `PM-FUND-007 a made-up id, an unknown one and another entrance's decision are refused by name and nothing is recorded`() {
        val entrance = entranceWithFund()
        val elsewhere = paperDecision(entranceWithFund())
        for (cited in listOf("GA-2026-03-12-3", UUID.randomUUID().toString(), elsewhere)) {
            val refused = disburse(entrance, onDecision(cited)).andExpect(status().isBadRequest).andReturn().response.contentAsString
            assertThat(refused).contains(cited).contains("PM-FUND-007")
        }
        assertThat(disbursements.findByEntranceId(entrance)).isEmpty()
    }

    @Test
    fun `PM-FUND-007 a refusal keeps no Idempotency-Key, so the key serves the corrected request`() {
        val entrance = entranceWithFund()
        disburse(entrance, onDecision("GA-2026-03-12-3"), key = "k-1").andExpect(status().isBadRequest)
        disburse(entrance, onDecision(paperDecision(entrance)), key = "k-1").andExpect(status().isCreated)
    }

    @Test
    fun `PM-FUND-008 an emergency is signed off on no decision at all`() {
        val entrance = entranceWithFund()
        disburse(entrance, """{"amountMinor":20000,"purpose":"WORKS","emergencyJustification":"a burst riser floods the stairwell"}""")
            .andExpect(status().isCreated)
        assertThat(disbursements.findByEntranceId(entrance).single().decisionId).isNull()
    }
}
