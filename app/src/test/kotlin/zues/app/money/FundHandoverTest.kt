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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * The fund's handover statement with the repositories mocked — no Spring, no database (PM-FUND-010): what it
 * computes from the ledger and the disbursements, and that it is stored as issued and read back from what was
 * stored. FundPersistenceIT proves the table's checks and that the table never changes a statement.
 */
class FundHandoverTest {

    private val entranceId = UUID.randomUUID()
    private val chair = UUID.randomUUID()
    private val successor = UUID.randomUUID()
    private val fund = FundAccountRow(UUID.randomUUID(), entranceId, "BG80BNBG96611020345678", "REPAIR_RENEWAL", "Иван Петров", "MANAGER", chair)

    private fun bank(amount: Long, on: String) =
        PostingRow(UUID.randomUUID(), entranceId, UUID.randomUUID(), "BANK:REPAIR_RENEWAL", null, amount, "EUR", LocalDate.parse(on))

    private val ledger = listOf(
        bank(50_000, "2026-08-01"),      // before the period: the opening balance
        bank(1_000, "2026-09-01"),       // on the period's first day: received
        bank(10_000, "2026-09-05"),
        bank(-20_000, "2026-09-10"),     // paid out
        bank(-500, "2026-09-15"),        // on the handover day: paid out
        bank(5_000, "2026-09-20"),       // after the handover: not on the statement
    )

    private fun disbursement(amount: Long, committed: String, paid: String? = null, cancelled: String? = null) = FundDisbursementRow(
        UUID.randomUUID(), entranceId, fund.id, amount, "EUR", "WORKS", "GA-2026-7", null, null, chair,
        if (paid != null) "PAID" else if (cancelled != null) "CANCELLED" else "COMMITTED", LocalDate.parse(committed),
        paidOn = paid?.let(LocalDate::parse), paidBy = paid?.let { chair },
        cancelledOn = cancelled?.let(LocalDate::parse), cancelledBy = cancelled?.let { chair }, cancelReason = cancelled?.let { "revoked" },
    )

    private fun handover(on: String = "2026-09-15", from: String? = "2026-09-01", bankBalance: Long = 40_500) =
        IssueHandover(LocalDate.parse(on), from?.let(LocalDate::parse), chair, successor, bankBalance)

    private val issuedOn = LocalDate.parse("2026-09-29")

    @Test
    fun `PM-FUND-010 the handover statement reconciles opening, receipts and payouts to the closing balance, beside the bank's`() {
        val statement = HandoverStatement.of(fund, ledger, emptyList(), handover(), issuedOn)
        assertThat(listOf(statement.openingMinor, statement.receivedMinor, statement.paidOutMinor, statement.closingMinor))
            .containsExactly(50_000L, 11_000L, 20_500L, 40_500L)
        assertThat(statement.reconciled).isTrue()
        assertThat(statement.differenceMinor).isZero()
        val short = HandoverStatement.of(fund, ledger, emptyList(), handover(bankBalance = 39_500), issuedOn)
        assertThat(short.reconciled).isFalse()
        assertThat(short.differenceMinor).isEqualTo(-1_000)                                   // the bank shows 1 000 less
        val whole = HandoverStatement.of(fund, ledger, emptyList(), handover(from = null), issuedOn)
        assertThat(listOf(whole.openingMinor, whole.receivedMinor, whole.paidOutMinor, whole.closingMinor))
            .containsExactly(0L, 61_000L, 20_500L, 40_500L)                                   // from the fund's first record
    }

    @Test
    fun `PM-FUND-010 the incoming side inherits what was signed off and neither paid out nor cancelled by the handover`() {
        val open = disbursement(3_000, committed = "2026-09-01")
        val paidLater = disbursement(4_000, committed = "2026-09-02", paid = "2026-09-20")
        val paidThatDay = disbursement(700, committed = "2026-09-03", paid = "2026-09-15")
        val cancelled = disbursement(1_000, committed = "2026-09-04", cancelled = "2026-09-12")
        val cancelledLater = disbursement(2_000, committed = "2026-09-05", cancelled = "2026-09-16")
        val signedThatDay = disbursement(500, committed = "2026-09-15")
        val signedLater = disbursement(9_000, committed = "2026-09-16")
        val statement = HandoverStatement.of(
            fund, ledger, listOf(signedLater, cancelledLater, paidThatDay, open, cancelled, signedThatDay, paidLater), handover(), issuedOn,
        )
        assertThat(statement.inherited.map { it.disbursementId })
            .containsExactly(open.id, paidLater.id, cancelledLater.id, signedThatDay.id)          // oldest first
        assertThat(statement.committedMinor).isEqualTo(9_500)
        assertThat(statement.availableMinor).isEqualTo(40_500 - 9_500)
    }

    @Test
    fun `PM-FUND-010 the basis reads back as the statement it was, so its hash is what both sides sign`() {
        val json = jacksonObjectMapper().registerModule(JavaTimeModule())
        val statement = HandoverStatement.of(fund, ledger, listOf(disbursement(3_000, committed = "2026-09-01")), handover(), issuedOn)
        val basis = statement.basisJson()
        val readBack = json.readValue(basis, HandoverStatement::class.java)
        assertThat(readBack).isEqualTo(statement)
        assertThat(readBack.basisJson()).isEqualTo(basis)                                        // byte-stable, so the same hash
        assertThat(basis).startsWith("{\"availableMinor\":")                                     // keys sorted
    }

    // The service: validation, storing as issued, reading back from what was stored.

    private val aggregates: JdbcAggregateTemplate = mock()
    private val accounts: FundAccountRepository = mock()
    private val disbursements: FundDisbursementRepository = mock()
    private val postings: PostingRepository = mock()
    private val statements: FundHandoverRepository = mock()
    private val lateNight = Clock.fixed(Instant.parse("2026-09-28T21:30:00Z"), ZoneOffset.UTC)   // 00:30 on 29 September in Sofia
    private val service = FundHandoverService(
        aggregates, accounts, disbursements, postings, statements, jacksonObjectMapper().registerModule(JavaTimeModule()), lateNight,
    )
    private val stored = mutableListOf<FundHandoverRow>()

    @BeforeEach
    fun fundOnRecord() {
        whenever(accounts.findByEntranceId(entranceId)).thenReturn(listOf(fund))
        whenever(postings.findByEntranceIdAndAccount(entranceId, "BANK:REPAIR_RENEWAL")).thenReturn(ledger)
        whenever(disbursements.findByEntranceId(entranceId)).thenReturn(listOf(disbursement(3_000, committed = "2026-09-01")))
        whenever(aggregates.insert(any<FundHandoverRow>())).thenAnswer { (it.arguments[0] as FundHandoverRow).also { row -> stored += row } }
    }

    @Test
    fun `PM-FUND-010 a statement is stored as issued, with the hash of its basis, and read back from what was stored`() {
        val issued = service.issue(entranceId, handover())
        val row = stored.single()
        assertThat(row.basis.json).isEqualTo(issued.statement.basisJson())
        assertThat(row.basisHash).isEqualTo(BasisJson.hash(row.basis.json)).isEqualTo(issued.basisHash)
        assertThat(listOf(row.openingMinor, row.receivedMinor, row.paidOutMinor, row.closingMinor, row.bankMinor, row.committedMinor))
            .containsExactly(50_000L, 11_000L, 20_500L, 40_500L, 40_500L, 3_000L)
        assertThat(listOf(row.fundAccountId, row.outgoingParty, row.incomingParty)).containsExactly(fund.id, chair, successor)
        assertThat(listOf(row.periodFrom, row.handoverOn, row.issuedOn))
            .containsExactly(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-15"), LocalDate.parse("2026-09-29"))   // issued on the Sofia day
        whenever(statements.findById(row.id)).thenReturn(Optional.of(row))
        whenever(postings.findByEntranceIdAndAccount(entranceId, "BANK:REPAIR_RENEWAL")).thenReturn(ledger + bank(-40_000, "2026-09-02"))
        assertThat(service.find(entranceId, row.id)).isEqualTo(issued)                          // a late, backdated payout changes nothing
        assertThatThrownBy { service.find(UUID.randomUUID(), row.id) }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { service.find(entranceId, UUID.randomUUID()) }.isInstanceOf(NoSuchElementException::class.java)
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
        whenever(accounts.findByEntranceId(entranceId)).thenReturn(emptyList())
        assertThatThrownBy { service.issue(entranceId, handover()) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `PM-FUND-010 a statement reads the fund from one snapshot, so a payout cannot fall between its reads`() {
        val issue = FundHandoverService::class.java.getMethod("issue", UUID::class.java, IssueHandover::class.java)
        assertThat(issue.getAnnotation(Transactional::class.java).isolation).isEqualTo(Isolation.REPEATABLE_READ)
    }
}
