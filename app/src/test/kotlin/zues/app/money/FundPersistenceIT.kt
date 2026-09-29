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
 * The repair fund against real PostgreSQL: money paid into the fund's account is its balance and cash is
 * not, a signed-off disbursement is committed and no longer available (PM-FUND-009), an emergency beyond
 * the available balance is refused (PM-FUND-008), a payout lowers the balance and what is committed alike
 * while a cancellation frees what was committed (PM-FUND-007, PM-FUND-009), and each of the table's own
 * checks refuses its violation (PM-FUND-006…009). Docker-gated.
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

    private fun pay(entranceId: UUID, unitId: UUID, amountMinor: Long, into: String) =
        mvc.perform(
            post("/api/money/entrances/$entranceId/payments").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"unitId":"$unitId","amountMinor":$amountMinor,"valueDate":"2026-09-01","receivedInto":"$into"}"""),
        ).andExpect(status().isCreated)

    private data class Seeded(val entranceId: UUID, val unitId: UUID, val chair: UUID, val fundAccountId: UUID)

    /** An entrance with one flat and a repair fund account held by its chair. */
    private fun entranceWithFund(): Seeded {
        val entranceId = postFor("/api/registry/entrances", """{"address":"ул. Оборище 12","label":"А","managementForm":"GA"}""", "entranceId")
        val unitId = postFor(
            "/api/registry/entrances/$entranceId/units",
            """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""", "unitIds",
        )
        val chair = postFor("/api/registry/parties", """{"fullName":"Иван Петров"}""", "partyId")
        val iban = "BG80BNBG${"%014d".format(System.nanoTime() % 100_000_000_000_000)}"
        val fundAccountId = postFor(
            "/api/money/entrances/$entranceId/fund-accounts",
            """{"iban":"$iban","purpose":"REPAIR_RENEWAL","holderName":"Иван Петров","holderKind":"MANAGER","holderPartyId":"$chair"}""", "fundAccountId",
        )
        return Seeded(entranceId, unitId, chair, fundAccountId)
    }

    @Test
    fun `PM-FUND-007 PM-FUND-008 PM-FUND-009 money in is the balance, a signed-off disbursement is committed, an emergency is capped`() {
        val (entranceId, unitId, chair) = entranceWithFund()
        pay(entranceId, unitId, 50_000, "REPAIR_RENEWAL")
        pay(entranceId, unitId, 7_000, "CASH")                                         // cash is not in the fund's account

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
    }

    @Test
    fun `PM-FUND-006 PM-FUND-007 PM-FUND-008 the table refuses a disbursement without its basis, one check at a time`() {
        val (entranceId, _, chair, fundAccountId) = entranceWithFund()
        fun insert(purpose: String, decision: String?, emergency: String?, measure: String?) = jdbc.update(
            "INSERT INTO fund_disbursement (id, entrance_id, fund_account_id, amount_minor, purpose, decision_id, emergency_justification, " +
                "passport_measure, authorised_by, status, committed_on) VALUES (?, ?, ?, 100, ?, ?, ?, ?, ?, 'COMMITTED', ?)",
            UUID.randomUUID(), entranceId, fundAccountId, purpose, decision, emergency, measure, chair, LocalDate.parse("2026-09-29"),
        )
        for ((row, constraint) in listOf(
            listOf("WORKS", null, null, null) to "fund_disbursement_decision_or_emergency",
            listOf("WORKS", "GA-2026-7", "the roof leaks", null) to "fund_disbursement_decision_or_emergency",
            listOf("GA_PURPOSE", null, "the roof leaks", null) to "fund_disbursement_emergency_is_works",
            listOf("PASSPORT_MEASURE", "GA-2026-7", null, null) to "fund_disbursement_measure_named",
            listOf("WORKS", "   ", null, null) to "fund_disbursement_decision_not_blank",
            listOf("WORKS", null, " ", null) to "fund_disbursement_justification_not_blank",
            listOf("PASSPORT_MEASURE", "GA-2026-7", null, "\t") to "fund_disbursement_measure_not_blank",
        )) {
            assertThatThrownBy { insert(row[0]!!, row[1], row[2], row[3]) }
                .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining(constraint)
        }
        insert("WORKS", null, "the roof leaks", null)                                    // an emergency repair, justified, is accepted
    }

    private fun signedOff(entranceId: UUID, body: String): Pair<UUID, String> {
        val response = disburse(entranceId, body).andExpect(status().isCreated).andReturn().response.contentAsString
        val node = json.readTree(response)
        return UUID.fromString(node.get("id").asText()) to node.get("committedOn").asText()
    }

    private fun act(entranceId: UUID, disbursementId: UUID, verb: String, body: String) = mvc.perform(
        post("/api/money/entrances/$entranceId/fund/disbursements/$disbursementId/$verb").contentType(MediaType.APPLICATION_JSON).content(body),
    )

    @Test
    fun `PM-FUND-007 PM-FUND-009 a payout lowers the balance and what is committed alike, a cancellation frees what was committed`() {
        val (entranceId, unitId, chair) = entranceWithFund()
        pay(entranceId, unitId, 50_000, "REPAIR_RENEWAL")
        val (works, signedOn) = signedOff(entranceId, """{"amountMinor":20000,"purpose":"WORKS","authorisedBy":"$chair","decisionId":"GA-2026-7"}""")
        val (roof, _) = signedOff(entranceId, """{"amountMinor":10000,"purpose":"WORKS","authorisedBy":"$chair","emergencyJustification":"the roof leaks"}""")

        act(entranceId, works, "pay", """{"paidOn":"$signedOn","paidBy":"$chair"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.paidOn").value(signedOn))
        act(entranceId, works, "pay", """{"paidOn":"$signedOn","paidBy":"$chair"}""").andExpect(status().isConflict)   // once
        act(entranceId, roof, "cancel", """{"cancelledBy":"$chair","reason":"the roofer found no leak"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
        act(entranceId, roof, "pay", """{"paidOn":"$signedOn","paidBy":"$chair"}""").andExpect(status().isConflict)    // cancelled stays cancelled

        mvc.perform(get("/api/money/entrances/$entranceId/fund"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanceMinor").value(30_000))
            .andExpect(jsonPath("$.committedMinor").value(0))
            .andExpect(jsonPath("$.availableMinor").value(30_000))
        val legs = jdbc.queryForList("SELECT account, amount_minor FROM posting WHERE journal_id = ?", works)
            .associate { it["account"] as String to (it["amount_minor"] as Number).toLong() }
        assertThat(legs).isEqualTo(mapOf("BANK:REPAIR_RENEWAL" to -20_000L, "EXPENSE:REPAIR_FUND" to 20_000L))
    }

    @Test
    fun `PM-FUND-007 PM-FUND-009 the table refuses a payout or a cancellation it cannot account for, one check at a time`() {
        val (entranceId, _, chair) = entranceWithFund()
        val (open, signedOn) = signedOff(entranceId, """{"amountMinor":100,"purpose":"WORKS","authorisedBy":"$chair","decisionId":"GA-2026-7"}""")
        val day = LocalDate.parse(signedOn)
        for ((sql, constraint) in listOf(
            "UPDATE fund_disbursement SET paid_on = ? WHERE id = ?" to "fund_disbursement_paid_dated",
            "UPDATE fund_disbursement SET status = 'PAID', paid_on = ?::date - 1 WHERE id = ?" to "fund_disbursement_paid_after_signed",
            "UPDATE fund_disbursement SET status = 'CANCELLED', cancelled_on = ? WHERE id = ?" to "fund_disbursement_cancel_recorded",
        )) {
            assertThatThrownBy { jdbc.update(sql, day, open) }
                .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining(constraint)
        }
        assertThatThrownBy {
            jdbc.update(
                "UPDATE fund_disbursement SET status = 'CANCELLED', cancelled_on = ?, cancelled_by = ?, cancel_reason = ' ' WHERE id = ?", day, chair, open,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("fund_disbursement_cancel_reason_not_blank")
        jdbc.update(                                                                       // a cancellation, fully recorded, is accepted
            "UPDATE fund_disbursement SET status = 'CANCELLED', cancelled_on = ?, cancelled_by = ?, cancel_reason = 'revoked' WHERE id = ?", day, chair, open,
        )
    }
}
