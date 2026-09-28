package zues.app.money

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * The payment journal, pure (PM-DEBT-008): what a recorded payment posts, and how the unit's open
 * debts are read back from the ledger. No database — runs in the local gate pack.
 */
class PaymentLedgerTest {

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val april = LocalDate.parse("2026-04-01")
    private val may = LocalDate.parse("2026-05-01")
    private val paidOn = LocalDate.parse("2026-05-20")

    private fun payment(amountMinor: Long, into: ReceivedInto = ReceivedInto.OPERATING) = PaymentRow(
        id = UUID.randomUUID(), entranceId = entranceId, unitId = unitId, amountMinor = amountMinor,
        currency = "EUR", valueDate = paidOn, receivedInto = into.name,
        allocationRule = PaymentAllocation.OLDEST_FIRST, designatedDate = null, basis = JsonbValue("{}"),
        basisHash = "h", lawVersion = "1.3", engineVersion = "0.1.0", idempotencyKey = "k",
    )

    private fun receivable(date: LocalDate, amountMinor: Long, settles: LocalDate? = null) = PostingRow(
        UUID.randomUUID(), entranceId, UUID.randomUUID(), Ledger.RECEIVABLE, unitId, amountMinor, "EUR", date, settles,
    )

    @Test
    fun `PM-DEBT-008 every leg is dated on the payment day, each credit names the debt it settled`() {
        val debts = listOf(PaymentAllocation.OutstandingDebt(april, 10_000), PaymentAllocation.OutstandingDebt(may, 10_000))
        val p = payment(15_000)
        val journal = Ledger.forPayment(p, PaymentAllocation.allocate(debts, 15_000))

        assertEquals(0, journal.sumOf { it.amountMinor })
        assertEquals(setOf(p.id), journal.map { it.journalId }.toSet())
        assertEquals(setOf(paidOn), journal.map { it.valueDate }.toSet())
        assertEquals("BANK:OPERATING", journal.single { it.amountMinor > 0 }.account)
        val credits = journal.filter { it.account == Ledger.RECEIVABLE }.associate { it.settlesValueDate to it.amountMinor }
        assertEquals(mapOf(april to -10_000L, may to -5_000L), credits)
    }

    @Test
    fun `PM-DEBT-008 an overpayment is credited to the unit's advance`() {
        val debts = listOf(PaymentAllocation.OutstandingDebt(april, 4_000))
        val journal = Ledger.forPayment(payment(6_000, ReceivedInto.CASH), PaymentAllocation.allocate(debts, 6_000))

        assertEquals(0, journal.sumOf { it.amountMinor })
        assertEquals("CASH", journal.single { it.amountMinor > 0 }.account)
        val advance = journal.single { it.account == Ledger.ADVANCE }
        assertEquals(-2_000, advance.amountMinor)
        assertEquals(unitId, advance.unitId)
    }

    @Test
    fun `PM-DEBT-008 open debts net each credit against the debt it names, so a designation is not replayed oldest-first`() {
        // May was designated and paid in full on 05-10; April is still wholly owed.
        val ledger = listOf(receivable(april, 10_000), receivable(may, 10_000), receivable(LocalDate.parse("2026-05-10"), -10_000, may))
        assertEquals(listOf(PaymentAllocation.OutstandingDebt(april, 10_000)), Ledger.openDebts(ledger, paidOn))
    }

    @Test
    fun `PM-DEBT-008 a debt raised after the payment date is not open to it`() {
        val june = LocalDate.parse("2026-06-01")
        val ledger = listOf(receivable(april, 10_000), receivable(june, 10_000))
        assertEquals(listOf(PaymentAllocation.OutstandingDebt(april, 10_000)), Ledger.openDebts(ledger, paidOn))
    }

    @Test
    fun `PM-DEBT-008 the allocation is read back from the journal with the rule applied`() {
        val debts = listOf(PaymentAllocation.OutstandingDebt(april, 3_000), PaymentAllocation.OutstandingDebt(may, 3_000))
        val allocation = PaymentAllocation.allocate(debts, 4_000, may)
        val p = payment(4_000).copy(allocationRule = allocation.rule, designatedDate = may)
        val view = PaymentView.of(p, Ledger.forPayment(p, allocation))

        assertEquals(PaymentAllocation.DESIGNATED, view.allocationRule)
        assertEquals("2026-05-01", view.designatedDebtDate)
        assertEquals(listOf(AllocatedPart("2026-04-01", 1_000), AllocatedPart("2026-05-01", 3_000)), view.allocation)
        assertEquals(0, view.unallocatedMinor)
    }
}
