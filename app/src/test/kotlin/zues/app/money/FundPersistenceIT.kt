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
 * while a cancellation frees what was committed (PM-FUND-007, PM-FUND-009), a handover statement is stored as
 * issued and never changed (PM-FUND-010), and each of the tables' own checks refuses its violation. Docker-gated.
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
        for ((values, constraint) in listOf(
            "0, 'WORKS'" to "fund_disbursement_amount_minor_check",
            "100, 'PARTY'" to "fund_disbursement_purpose_check",
        )) {
            assertThatThrownBy {
                jdbc.update(
                    "INSERT INTO fund_disbursement (id, entrance_id, fund_account_id, amount_minor, purpose, decision_id, authorised_by, status, committed_on) " +
                        "VALUES (gen_random_uuid(), '$entranceId', '$fundAccountId', $values, 'GA-2026-7', '$chair', 'COMMITTED', DATE '2026-09-29')",
                )
            }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining(constraint)
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
            .andExpect(jsonPath("$.paidBy").value(chair.toString()))
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
        val day = "DATE '$signedOn'"
        fun update(set: String) = jdbc.update("UPDATE fund_disbursement SET $set WHERE id = '$open'")
        for ((set, constraint) in listOf(
            "status = 'SETTLED'" to "fund_disbursement_status_check",
            "paid_on = $day, paid_by = '$chair'" to "fund_disbursement_paid_dated",                       // a date, but not PAID
            "status = 'PAID'" to "fund_disbursement_paid_dated",                                           // PAID, but no date
            "status = 'PAID', paid_on = $day" to "fund_disbursement_paid_by_recorded",
            "status = 'PAID', paid_on = $day - 1, paid_by = '$chair'" to "fund_disbursement_paid_after_signed",
            "status = 'CANCELLED'" to "fund_disbursement_cancelled_dated",
            "status = 'CANCELLED', cancelled_on = $day, cancel_reason = 'revoked'" to "fund_disbursement_cancelled_by_recorded",
            "status = 'CANCELLED', cancelled_on = $day, cancelled_by = '$chair'" to "fund_disbursement_cancel_reason_recorded",
            "status = 'CANCELLED', cancelled_on = $day, cancelled_by = '$chair', cancel_reason = ' '" to "fund_disbursement_cancel_reason_not_blank",
            "status = 'CANCELLED', cancelled_on = $day - 1, cancelled_by = '$chair', cancel_reason = 'revoked'" to "fund_disbursement_cancelled_after_signed",
            "amount_minor = 99" to "stands as signed off, and is not rewritten",
        )) {
            assertThatThrownBy { update(set) }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining(constraint)
        }
        update("status = 'CANCELLED', cancelled_on = $day, cancelled_by = '$chair', cancel_reason = 'revoked'")   // fully recorded: accepted
        assertThatThrownBy { update("status = 'COMMITTED', cancelled_on = NULL, cancelled_by = NULL, cancel_reason = NULL") }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("is already CANCELLED, and closes once")
    }

    @Test
    fun `PM-FUND-010 a handover statement is stored as issued, reconciled against the bank or not, and never changed`() {
        val (entranceId, unitId, chair) = entranceWithFund()
        val successor = postFor("/api/registry/parties", """{"fullName":"Мария Иванова"}""", "partyId")
        pay(entranceId, unitId, 50_000, "REPAIR_RENEWAL")
        val (works, signedOn) = signedOff(entranceId, """{"amountMinor":20000,"purpose":"WORKS","authorisedBy":"$chair","decisionId":"GA-2026-7"}""")
        act(entranceId, works, "pay", """{"paidOn":"$signedOn","paidBy":"$chair"}""").andExpect(status().isOk)
        signedOff(entranceId, """{"amountMinor":5000,"purpose":"WORKS","authorisedBy":"$chair","decisionId":"GA-2026-8"}""")   // unpaid: inherited
        val statements = "/api/money/entrances/$entranceId/fund/handover-statements"
        fun issue(incoming: UUID, bank: Long) = mvc.perform(
            post(statements).contentType(MediaType.APPLICATION_JSON)
                .content("""{"handoverOn":"$signedOn","outgoingPartyId":"$chair","incomingPartyId":"$incoming","bankBalanceMinor":$bank}"""),
        )

        val first = issue(successor, 30_000)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.statement.receivedMinor").value(50_000))
            .andExpect(jsonPath("$.statement.paidOutMinor").value(20_000))
            .andExpect(jsonPath("$.statement.closingMinor").value(30_000))
            .andExpect(jsonPath("$.statement.reconciled").value(true))
            .andExpect(jsonPath("$.statement.committedMinor").value(5_000))
            .andExpect(jsonPath("$.statement.inherited.length()").value(1))
            .andReturn().response.contentAsString
        val second = issue(successor, 29_000)                                            // the bank shows less: stored all the same
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.statement.reconciled").value(false))
            .andExpect(jsonPath("$.statement.differenceMinor").value(-1_000))
            .andReturn().response.contentAsString
        issue(UUID.randomUUID(), 30_000)                                                // an unregistered incoming side
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("the statement conflicts with the fund's records, or a party named on it is not registered"))
        val firstId = UUID.fromString(json.readTree(first).get("id").asText())
        val secondId = json.readTree(second).get("id").asText()
        val hash = json.readTree(first).get("basisHash").asText()

        jdbc.update("UPDATE fund_handover_statement SET closing_minor = 0, received_minor = 0 WHERE id = ?", firstId)   // ignored
        jdbc.update("DELETE FROM fund_handover_statement WHERE id = ?", firstId)                                         // ignored
        val readBack = mvc.perform(get("$statements/$firstId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.basisHash").value(hash))
            .andExpect(jsonPath("$.statement.closingMinor").value(30_000))
            .andReturn().response.contentAsString
        assertThat(BasisJson.hash(json.readTree(readBack).get("basis").asText())).isEqualTo(hash)   // anyone can check the hash
        assertThat(jdbc.queryForObject("SELECT closing_minor FROM fund_handover_statement WHERE id = ?", Long::class.java, firstId)).isEqualTo(30_000)
        mvc.perform(get(statements))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(secondId))                             // newest first: the correction
            .andExpect(jsonPath("$[1].id").value(firstId.toString()))
    }

    @Test
    fun `PM-FUND-010 the table refuses a handover statement that does not reconcile or name two sides, one check at a time`() {
        val (entranceId, _, chair, fundAccountId) = entranceWithFund()
        val successor = postFor("/api/registry/parties", """{"fullName":"Мария Иванова"}""", "partyId")
        val valid = linkedMapOf(
            "id" to "gen_random_uuid()", "entrance_id" to "'$entranceId'", "fund_account_id" to "'$fundAccountId'", "period_from" to "NULL",
            "handover_on" to "DATE '2026-09-15'", "outgoing_party" to "'$chair'", "incoming_party" to "'$successor'",
            "opening_minor" to "0", "received_minor" to "100", "paid_out_minor" to "40", "closing_minor" to "60", "bank_minor" to "60",
            "committed_minor" to "0", "basis" to "'{}'", "basis_hash" to "'h'", "law_version" to "'1.3'", "engine_version" to "'0.2.0'",
            "issued_on" to "DATE '2026-09-29'", "issued_at" to "now()",
        )
        fun insert(overrides: Map<String, String>) = (valid + overrides).let { row ->
            jdbc.update("INSERT INTO fund_handover_statement (${row.keys.joinToString()}) VALUES (${row.values.joinToString()})")
        }
        for ((overrides, constraint) in listOf(
            mapOf("incoming_party" to "'$chair'") to "fund_handover_statement_parties_differ",
            mapOf("closing_minor" to "61") to "fund_handover_statement_reconciles",
            mapOf("closing_minor" to "59") to "fund_handover_statement_reconciles",
            mapOf("period_from" to "DATE '2026-09-16'") to "fund_handover_statement_period",
            mapOf("issued_on" to "DATE '2026-09-14'") to "fund_handover_statement_not_ahead",
            mapOf("paid_out_minor" to "-1", "closing_minor" to "101") to "fund_handover_statement_paid_out_not_negative",
            mapOf("committed_minor" to "-1") to "fund_handover_statement_committed_not_negative",
        )) {
            assertThatThrownBy { insert(overrides) }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining(constraint)
        }
        insert(emptyMap())                                                                // reconciled, between two sides: accepted
        insert(mapOf("period_from" to "DATE '2026-09-15'"))                              // a one-day period
        insert(mapOf("received_minor" to "-10", "closing_minor" to "-50"))               // net receipts may be negative: a reversal
    }
}
