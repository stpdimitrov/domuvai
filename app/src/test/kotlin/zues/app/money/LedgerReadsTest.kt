package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.util.UUID

/**
 * The ledger's reads with every repository mocked — no Spring, no database. Proves a journal is read whole and
 * balanced, in date order, with what wrote it (PM-FUND-005, PM-PMC-008), and that the operating account's balance is
 * the ledger's for that account alone (PM-FUND-004, PM-FEE-019).
 */
class LedgerReadsTest {

    private val postings: PostingRepository = mock()
    private val accounts: FundAccountRepository = mock()
    private val chargeRuns: ChargeRunRepository = mock()
    private val payments: PaymentRepository = mock()
    private val disbursements: FundDisbursementRepository = mock()
    private val ledger = LedgerReads(postings, accounts, chargeRuns, payments, disbursements)

    private val entranceId = UUID.randomUUID()
    private val unit = UUID.randomUUID()
    private val from = LocalDate.of(2026, 9, 1)
    private val to = LocalDate.of(2026, 9, 30)

    // ids chosen so the order by id is known: run < payment < payout
    private val run = UUID(0, 1)
    private val payment = UUID(0, 2)
    private val payout = UUID(0, 3)

    private fun leg(journal: UUID, account: String, amount: Long, on: LocalDate, unitId: UUID? = null) =
        PostingRow(UUID.randomUUID(), entranceId, journal, account, unitId, amount, "EUR", on)

    private val posted = listOf(
        // a payout on the 20th, written first: the read orders by date, not by how the rows come back
        leg(payout, "BANK:REPAIR_RENEWAL", -20_000, LocalDate.of(2026, 9, 20)),
        leg(payout, "EXPENSE:REPAIR_FUND", 20_000, LocalDate.of(2026, 9, 20)),
        // a payment on the 10th into the operating account, settling part of the run
        leg(payment, "RECEIVABLE", -4_000, LocalDate.of(2026, 9, 10), unit),
        leg(payment, "BANK:OPERATING", 4_000, LocalDate.of(2026, 9, 10)),
        // the run on the 1st: one unit charged on two streams
        leg(run, "INCOME:REPAIR_FUND", -3_000, from),
        leg(run, "RECEIVABLE", 3_000, from, unit),          // the smaller debit comes back first: the read orders the legs itself
        leg(run, "INCOME:MANAGEMENT", -6_000, from),
        leg(run, "RECEIVABLE", 6_000, from, unit),
    )

    @BeforeEach
    fun stub() {
        whenever(postings.findByEntranceIdAndValueDateBetween(entranceId, from, to)).thenReturn(posted)
        // the rows are made first: a mock stubbed inside another stubbing is not finished
        val runRow = mock<ChargeRunRow> { on { id } doReturn run }
        val paymentRow = mock<PaymentRow> { on { id } doReturn payment }
        val payoutRow = mock<FundDisbursementRow> { on { id } doReturn payout }
        whenever(chargeRuns.findAllById(any())).thenReturn(listOf(runRow))
        whenever(payments.findAllById(any())).thenReturn(listOf(paymentRow))
        whenever(disbursements.findAllById(any())).thenReturn(listOf(payoutRow))
    }

    @Test
    fun `PM-FUND-005 PM-PMC-008 an entrance's journal is read whole, oldest first, each journal balanced and naming what wrote it`() {
        val view = ledger.journal(entranceId, from, to)

        assertThat(view.journals.map { Triple(it.journalId, it.valueDate, it.source) }).containsExactly(
            Triple(run, from, JournalSource.CHARGE_RUN), Triple(payment, LocalDate.of(2026, 9, 10), JournalSource.PAYMENT),
            Triple(payout, LocalDate.of(2026, 9, 20), JournalSource.FUND_PAYOUT),
        )
        assertThat(view.journals).allSatisfy { assertThat(it.legs.sumOf { leg -> leg.amountMinor }).isZero() }   // ADR-006
        assertThat(view.journals.first().legs).containsExactly(                                                  // debits, then credits
            JournalLeg("RECEIVABLE", unit, 6_000), JournalLeg("RECEIVABLE", unit, 3_000),
            JournalLeg("INCOME:MANAGEMENT", null, -6_000), JournalLeg("INCOME:REPAIR_FUND", null, -3_000),
        )
        assertThat(listOf(view.entranceId, view.from, view.to, view.account)).containsExactly(entranceId, from, to, null)
    }

    @Test
    fun `PM-FUND-005 asked for one account, the journal keeps only the journals that touch it — each still whole`() {
        val view = ledger.journal(entranceId, from, to, "BANK:OPERATING")

        assertThat(view.account).isEqualTo("BANK:OPERATING")
        assertThat(view.journals.map { it.journalId }).containsExactly(payment)
        assertThat(view.journals.single().legs).containsExactly(JournalLeg("BANK:OPERATING", null, 4_000), JournalLeg("RECEIVABLE", unit, -4_000))
        assertThat(ledger.journal(entranceId, from, to, "CASH").journals).isEmpty()
    }

    @Test
    fun `PM-PMC-008 a journal nothing in money claims is still read, with no source — and an entrance with no postings reads as empty`() {
        whenever(chargeRuns.findAllById(any())).thenReturn(emptyList())
        assertThat(ledger.journal(entranceId, from, to).journals.map { it.source }).containsExactly(null, JournalSource.PAYMENT, JournalSource.FUND_PAYOUT)

        val unknown = UUID.randomUUID()
        whenever(postings.findByEntranceIdAndValueDateBetween(unknown, from, to)).thenReturn(emptyList())
        assertThat(ledger.journal(unknown, from, to).journals).isEmpty()
    }

    @Test
    fun `a range that ends before it starts is refused, and one day is a range`() {
        assertThatThrownBy { ledger.journal(entranceId, to, from) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("before")
        whenever(postings.findByEntranceIdAndValueDateBetween(entranceId, from, from)).thenReturn(posted.filter { it.valueDate == from })
        assertThat(ledger.journal(entranceId, from, from).journals.map { it.journalId }).containsExactly(run)
    }

    private fun account(purpose: String, iban: String) = FundAccountRow(UUID.randomUUID(), entranceId, iban, purpose, "Мария Иванова", "MANAGER")

    @Test
    fun `PM-FUND-004 PM-FEE-019 the operating account's balance is what the ledger holds for it, apart from the fund's — and it says outflows are not recorded`() {
        whenever(accounts.findByEntranceId(entranceId))
            .thenReturn(listOf(account("REPAIR_RENEWAL", "BG11FUND"), account("OPERATING", "BG22OPER")))
        whenever(postings.findByEntranceIdAndAccount(entranceId, "BANK:OPERATING"))
            .thenReturn(listOf(leg(payment, "BANK:OPERATING", 4_000, from), leg(UUID.randomUUID(), "BANK:OPERATING", 2_500, to)))
        whenever(postings.findByEntranceIdAndAccount(entranceId, "BANK:REPAIR_RENEWAL"))
            .thenReturn(listOf(leg(payout, "BANK:REPAIR_RENEWAL", 90_000, from)))                               // never part of it

        assertThat(ledger.operatingAccount(entranceId))
            .isEqualTo(OperatingAccountView(entranceId, "BG22OPER", "Мария Иванова", 6_500, outflowsRecorded = false))
    }

    @Test
    fun `PM-FUND-004 an entrance with only the fund's account has no operating account to read`() {
        whenever(accounts.findByEntranceId(entranceId)).thenReturn(listOf(account("REPAIR_RENEWAL", "BG11FUND")))
        assertThatThrownBy { ledger.operatingAccount(entranceId) }
            .isInstanceOf(NoSuchElementException::class.java).hasMessageContaining("operating account")
    }

    @Test
    fun `PM-PMC-008 two journals of one day, and two like legs, come in the same order on every read`() {
        val first = UUID(0, 4)
        val second = UUID(0, 5)
        fun payment(id: UUID, amount: Long) = listOf(leg(id, "BANK:OPERATING", amount, from), leg(id, "RECEIVABLE", -amount, from, unit))
        val rows = payment(second, 2_000) + payment(first, 500) + payment(first, 1_000)                         // `first` settles two debts
        whenever(postings.findByEntranceIdAndValueDateBetween(entranceId, from, from)).thenReturn(rows)

        val forward = ledger.journal(entranceId, from, from)
        whenever(postings.findByEntranceIdAndValueDateBetween(entranceId, from, from)).thenReturn(rows.reversed())

        assertThat(forward.journals.map { it.journalId }).containsExactly(first, second)                        // by id within a day
        assertThat(forward.journals.first().legs.map { it.amountMinor }).containsExactly(1_000, 500, -1_000, -500)   // the larger first
        assertThat(ledger.journal(entranceId, from, from)).isEqualTo(forward)
    }

    @Test
    fun `PM-FUND-005 a journal the range or the entrance would cut stops the read — never shown as if it were whole`() {
        val cut = UUID.randomUUID()
        whenever(postings.findByEntranceIdAndValueDateBetween(entranceId, from, to))
            .thenReturn(listOf(leg(cut, "BANK:OPERATING", 4_000, from)))                                         // its other leg is elsewhere
        assertThatThrownBy { ledger.journal(entranceId, from, to) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining(cut.toString())

        whenever(postings.findByEntranceIdAndValueDateBetween(entranceId, from, to))
            .thenReturn(listOf(leg(cut, "BANK:OPERATING", 4_000, from), leg(cut, "RECEIVABLE", -4_000, to, unit)))   // balanced, dated apart
        assertThatThrownBy { ledger.journal(entranceId, from, to) }.isInstanceOf(IllegalStateException::class.java)
    }
}
