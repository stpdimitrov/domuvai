package zues.app.money

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import zues.law.CATALOGUE_VERSION
import zues.law.ENGINE_VERSION
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * The fund's handover statement with the repositories mocked — no Spring, no database (PM-FUND-010): what it
 * computes from the ledger and the disbursements, and that it is stored as issued and read back from what was
 * stored, checked against its hash. FundPersistenceIT proves the table's checks and that it never changes a statement.
 */
class FundHandoverTest {

    private val entranceId = UUID.randomUUID()
    private val chair = UUID.randomUUID()
    private val successor = UUID.randomUUID()
    private val fund = FundAccountRow(UUID.randomUUID(), entranceId, "BG80BNBG96611020345678", "REPAIR_RENEWAL", "Иван Петров", "MANAGER", chair)
    private val operating = FundAccountRow(UUID.randomUUID(), entranceId, "BG18RZBB91550123456789", "OPERATING", "Иван Петров", "MANAGER", chair)

    private fun disbursement(
        amount: Long, committed: String, paid: String? = null, cancelled: String? = null,
        decision: String? = "GA-2026-7", emergency: String? = null, purpose: String = "WORKS", measure: String? = null,
    ) = FundDisbursementRow(
        UUID.randomUUID(), entranceId, fund.id, amount, "EUR", purpose, decision, measure, emergency, chair,
        if (paid != null) "PAID" else if (cancelled != null) "CANCELLED" else "COMMITTED", LocalDate.parse(committed),
        paidOn = paid?.let(LocalDate::parse), paidBy = paid?.let { chair },
        cancelledOn = cancelled?.let(LocalDate::parse), cancelledBy = cancelled?.let { chair }, cancelReason = cancelled?.let { "revoked" },
    )

    // The handover is on 15 September, the period from 1 September.
    private val earlyPaid = disbursement(7_000, committed = "2026-08-10", paid = "2026-08-20")
    private val paid = disbursement(20_000, committed = "2026-09-03", paid = "2026-09-10")
    private val paidThatDay = disbursement(500, committed = "2026-09-03", paid = "2026-09-15")
    private val paidLater = disbursement(4_000, committed = "2026-09-02", paid = "2026-09-20")
    private val open = disbursement(3_000, committed = "2026-09-01", decision = null, emergency = "the roof leaks")
    private val cancelled = disbursement(1_000, committed = "2026-09-04", cancelled = "2026-09-12")
    private val cancelledThatDay = disbursement(900, committed = "2026-09-04", cancelled = "2026-09-15")
    private val cancelledLater = disbursement(2_000, committed = "2026-09-05", cancelled = "2026-09-16")
    private val twins = listOf(
        disbursement(600, committed = "2026-09-06", purpose = "PASSPORT_MEASURE", measure = "ТП-2024 т. 3.2"),
        disbursement(400, committed = "2026-09-06"),
    ).sortedBy { it.id }
    private val signedThatDay = disbursement(800, committed = "2026-09-15")
    private val signedLater = disbursement(9_000, committed = "2026-09-16")
    private val all = listOf(signedLater, cancelledLater, paid, open, cancelledThatDay, earlyPaid, cancelled, signedThatDay, paidThatDay, paidLater) + twins.reversed()

    private fun leg(amount: Long, on: String, journal: UUID = UUID.randomUUID()) =
        PostingRow(UUID.randomUUID(), entranceId, journal, "BANK:REPAIR_RENEWAL", null, amount, "EUR", LocalDate.parse(on))

    private val ledger = listOf(
        leg(50_000, "2026-08-01"),                      // received before the period
        leg(-7_000, "2026-08-20", earlyPaid.id),        // paid out before the period: opening 43 000
        leg(1_000, "2026-09-01"),                       // received on the period's first day
        leg(10_000, "2026-09-05"),
        leg(-20_000, "2026-09-10", paid.id),            // paid out
        leg(-300, "2026-09-11"),                        // a reversed receipt: counts against received, not as paid out
        leg(-500, "2026-09-15", paidThatDay.id),        // paid out on the handover day
        leg(-4_000, "2026-09-20", paidLater.id),        // after the handover: not on the statement
        leg(5_000, "2026-09-20"),
    )

    private fun handover(on: String = "2026-09-15", from: String? = "2026-09-01", bankBalance: Long = 33_200) =
        IssueHandover(LocalDate.parse(on), from?.let(LocalDate::parse), chair, successor, bankBalance)

    private val lateNight = Instant.parse("2026-09-28T21:30:00Z")                        // 00:30 on 29 September in Sofia

    private fun statement(command: IssueHandover = handover()) = HandoverStatement.of(fund, ledger, all, command, lateNight)

    @Test
    fun `PM-FUND-010 the handover statement reconciles opening, receipts and payouts to the closing balance, beside the bank's`() {
        val matched = statement()
        assertThat(listOf(matched.openingMinor, matched.receivedMinor, matched.paidOutMinor, matched.closingMinor))
            .containsExactly(43_000L, 10_700L, 20_500L, 33_200L)
        assertThat(matched.reconciled).isTrue()
        assertThat(matched.differenceMinor).isZero()
        val short = statement(handover(bankBalance = 33_000))
        assertThat(listOf(short.reconciled, short.differenceMinor, short.closingMinor, short.availableMinor)).containsExactly(false, -200L, 33_200L, 22_400L)
        val over = statement(handover(bankBalance = 33_500))
        assertThat(listOf(over.reconciled, over.differenceMinor)).containsExactly(false, 300L)          // the bank shows more
        val whole = statement(handover(from = null))
        assertThat(listOf(whole.openingMinor, whole.receivedMinor, whole.paidOutMinor, whole.closingMinor))
            .containsExactly(0L, 60_700L, 27_500L, 33_200L)                                             // from the fund's first record
    }

    @Test
    fun `PM-FUND-010 the incoming side inherits what was signed off and neither paid out nor cancelled by the handover`() {
        val s = statement()
        assertThat(s.inherited.map { it.disbursementId })
            .containsExactly(open.id, paidLater.id, cancelledLater.id, twins[0].id, twins[1].id, signedThatDay.id)   // oldest first, then by id
        assertThat(s.inherited.first()).isEqualTo(
            InheritedDisbursement(open.id, 3_000, "WORKS", null, "the roof leaks", null, LocalDate.parse("2026-09-01")),
        )
        assertThat(s.inherited.single { it.purpose == "PASSPORT_MEASURE" }.passportMeasure).isEqualTo("ТП-2024 т. 3.2")
        assertThat(s.committedMinor).isEqualTo(10_800)
        assertThat(s.availableMinor).isEqualTo(33_200 - 10_800)
        assertThat(listOf(s.entranceId, s.fundAccountId, s.outgoingPartyId, s.incomingPartyId)).containsExactly(entranceId, fund.id, chair, successor)
        assertThat(listOf(s.iban, s.holderName)).containsExactly(fund.iban, fund.holderName)
        assertThat(listOf(s.from, s.handoverOn)).containsExactly(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-15"))
        assertThat(listOf(s.issuedOn, s.issuedAt)).containsExactly(LocalDate.parse("2026-09-29"), lateNight)   // the Sofia day
    }

    @Test
    fun `PM-FUND-010 the basis reads back as the statement it was, so its hash is what both sides sign`() {
        val json = jacksonObjectMapper().registerModule(JavaTimeModule())
        val unreconciled = statement(handover(bankBalance = 33_000))
        val basis = unreconciled.basisJson()
        val readBack = json.readValue(basis, HandoverStatement::class.java)
        assertThat(readBack).isEqualTo(unreconciled)
        assertThat(readBack.basisJson()).isEqualTo(basis)                                        // byte-stable, so the same hash
        assertThat(basis).startsWith("{\"availableMinor\":22400,\"bankBalanceMinor\":33000,")   // keys sorted
    }

    // The service: validation, storing as issued, reading back from what was stored.

    private val aggregates: JdbcAggregateTemplate = mock()
    private val accounts: FundAccountRepository = mock()
    private val disbursements: FundDisbursementRepository = mock()
    private val postings: PostingRepository = mock()
    private val statements: FundHandoverRepository = mock()
    private val json = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val service = FundHandoverService(aggregates, accounts, disbursements, postings, statements, json, Clock.fixed(lateNight, ZoneOffset.UTC))
    private val stored = mutableListOf<FundHandoverRow>()

    @BeforeEach
    fun fundOnRecord() {
        whenever(accounts.findByEntranceId(entranceId)).thenReturn(listOf(operating, fund))     // the operating account comes first
        whenever(postings.findByEntranceIdAndAccount(entranceId, "BANK:REPAIR_RENEWAL")).thenReturn(ledger)
        whenever(disbursements.findByEntranceId(entranceId)).thenReturn(all)
        whenever(aggregates.insert(any<FundHandoverRow>())).thenAnswer { (it.arguments[0] as FundHandoverRow).also { row -> stored += row } }
    }

    @Test
    fun `PM-FUND-010 a statement is stored as issued, with the hash of its basis, and read back from what was stored`() {
        val issued = service.issue(entranceId, handover(bankBalance = 33_000))
        val row = stored.single()
        assertThat(row.basis.json).isEqualTo(issued.basis).isEqualTo(issued.statement.basisJson())
        assertThat(row.basisHash).isEqualTo(BasisJson.hash(issued.basis)).isEqualTo(issued.basisHash)
        assertThat(listOf(row.openingMinor, row.receivedMinor, row.paidOutMinor, row.closingMinor, row.bankMinor, row.committedMinor))
            .containsExactly(43_000L, 10_700L, 20_500L, 33_200L, 33_000L, 10_800L)                   // the ledger's closing, the bank's own
        assertThat(listOf(row.fundAccountId, row.outgoingParty, row.incomingParty)).containsExactly(fund.id, chair, successor)
        assertThat(listOf(row.periodFrom, row.handoverOn, row.issuedOn))
            .containsExactly(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-15"), LocalDate.parse("2026-09-29"))
        assertThat(listOf(row.lawVersion, row.engineVersion, row.issuedAt)).containsExactly(CATALOGUE_VERSION, ENGINE_VERSION, lateNight)
        assertThat(issued.statement.reconciled).isFalse()                                        // stored all the same (D3)

        whenever(statements.findById(row.id)).thenReturn(Optional.of(row))
        whenever(statements.findByEntranceIdOrderByIssuedAtDesc(entranceId)).thenReturn(listOf(row))
        whenever(postings.findByEntranceIdAndAccount(entranceId, "BANK:REPAIR_RENEWAL")).thenReturn(ledger + leg(-40_000, "2026-09-02"))
        assertThat(service.find(entranceId, row.id)).isEqualTo(issued)                          // a late, backdated posting changes nothing
        assertThat(service.list(entranceId)).containsExactly(issued)
        assertThatThrownBy { service.find(UUID.randomUUID(), row.id) }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { service.find(entranceId, UUID.randomUUID()) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `PM-FUND-010 a stored statement that no longer matches its hash is refused, not served`() {
        service.issue(entranceId, handover())
        val row = stored.single()
        val altered = row.copy(basis = JsonbValue(row.basis.json.replace("\"closingMinor\":33200", "\"closingMinor\":99999")))
        whenever(statements.findById(row.id)).thenReturn(Optional.of(altered))
        assertThatThrownBy { service.find(entranceId, row.id) }.isInstanceOf(StatementTampered::class.java)
        val dropped = row.copy(basis = JsonbValue(row.basis.json.replace("\"receivedMinor\":10700,", "")))   // a missing figure would read as 0
        whenever(statements.findById(row.id)).thenReturn(Optional.of(dropped))
        assertThatThrownBy { service.find(entranceId, row.id) }.isInstanceOf(StatementTampered::class.java)
    }

    @Test
    fun `PM-FUND-010 a handover is dated no later than today, its period starts by then, and two different parties hand over`() {
        assertThat(service.issue(entranceId, handover(on = "2026-09-29")).statement.handoverOn)
            .isEqualTo(LocalDate.parse("2026-09-29"))                                          // today in Sofia, still the 28th in UTC
        assertThatThrownBy { service.issue(entranceId, handover(on = "2026-09-30")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(service.issue(entranceId, handover(from = "2026-09-15")).statement.from).isEqualTo(LocalDate.parse("2026-09-15"))
        assertThatThrownBy { service.issue(entranceId, handover(from = "2026-09-16")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.issue(entranceId, handover().copy(incomingPartyId = chair)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(stored).hasSize(2)                                                          // a refused statement is not stored
        whenever(accounts.findByEntranceId(entranceId)).thenReturn(listOf(operating))
        assertThatThrownBy { service.issue(entranceId, handover()) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `PM-FUND-010 a statement reads the fund from one snapshot, so a payout cannot fall between its reads`() {
        val issue = FundHandoverService::class.java.getMethod("issue", UUID::class.java, IssueHandover::class.java)
        assertThat(issue.getAnnotation(Transactional::class.java).isolation).isEqualTo(Isolation.REPEATABLE_READ)
    }
}
