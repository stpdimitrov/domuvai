package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import zues.app.money.PaymentAllocation.AllocationEntry
import zues.app.money.PaymentAllocation.OutstandingDebt
import java.time.LocalDate

/**
 * PM-DEBT-008 proved without a database: allocation is pure, so oldest-first, designation and
 * explainability are all decided here. Amounts are minor units (ADR-006); dates stand in for the
 * charge periods they came from.
 */
class PaymentAllocationTest {

    private val mar = LocalDate.of(2026, 3, 1)
    private val apr = LocalDate.of(2026, 4, 1)
    private val may = LocalDate.of(2026, 5, 1)

    private fun debts(vararg d: Pair<LocalDate, Long>) = d.map { OutstandingDebt(it.first, it.second) }

    @Test
    fun `PM-DEBT-008 a payment settles the oldest debt first`() {
        val a = PaymentAllocation.allocate(debts(apr to 4000, mar to 6000), paymentMinor = 8000)
        assertThat(a.rule).isEqualTo(PaymentAllocation.OLDEST_FIRST)
        assertThat(a.entries).containsExactly(AllocationEntry(mar, 6000), AllocationEntry(apr, 2000))
        assertThat(a.unallocatedMinor).isZero()
    }

    @Test
    fun `PM-DEBT-008 a partial payment reaches only as far as it can, oldest first`() {
        val a = PaymentAllocation.allocate(debts(mar to 6000, apr to 4000), paymentMinor = 3000)
        assertThat(a.entries).containsExactly(AllocationEntry(mar, 3000))   // apr untouched
        assertThat(a.unallocatedMinor).isZero()
    }

    @Test
    fun `PM-DEBT-008 the payer may designate a debt, settled before the rest`() {
        val a = PaymentAllocation.allocate(debts(mar to 6000, apr to 4000), paymentMinor = 5000, designateDate = apr)
        assertThat(a.rule).isEqualTo(PaymentAllocation.DESIGNATED)
        assertThat(a.entries).containsExactly(AllocationEntry(apr, 4000), AllocationEntry(mar, 1000))
    }

    @Test
    fun `PM-DEBT-008 the rule applied is part of the result`() {
        assertThat(PaymentAllocation.allocate(debts(mar to 6000), 1000).rule).isEqualTo(PaymentAllocation.OLDEST_FIRST)
        assertThat(PaymentAllocation.allocate(debts(mar to 6000), 1000, designateDate = mar).rule)
            .isEqualTo(PaymentAllocation.DESIGNATED)
    }

    @Test
    fun `PM-DEBT-008 an overpayment clears every debt and reports the remainder`() {
        val a = PaymentAllocation.allocate(debts(mar to 6000, apr to 4000), paymentMinor = 12000)
        assertThat(a.entries).containsExactly(AllocationEntry(mar, 6000), AllocationEntry(apr, 4000))
        assertThat(a.unallocatedMinor).isEqualTo(2000)   // 12000 − 10000 owed
    }

    @Test
    fun `PM-DEBT-008 a payment with nothing owed is entirely unallocated`() {
        val a = PaymentAllocation.allocate(emptyList(), paymentMinor = 5000)
        assertThat(a.entries).isEmpty()
        assertThat(a.unallocatedMinor).isEqualTo(5000)
    }

    @Test
    fun `PM-DEBT-008 prior payments have already settled the oldest debts, oldest first`() {
        // 7000 already paid against March 6000 + April 4000 → March clears, April down to 3000.
        val remaining = PaymentAllocation.netOutstanding(debts(mar to 6000, apr to 4000), priorCreditsMinor = 7000)
        assertThat(remaining).containsExactly(OutstandingDebt(apr, 3000))
    }

    @Test
    fun `PM-DEBT-008 a later payment then settles what prior payments left, oldest first`() {
        val remaining = PaymentAllocation.netOutstanding(debts(mar to 6000, apr to 4000, may to 5000), priorCreditsMinor = 6000)
        val a = PaymentAllocation.allocate(remaining, paymentMinor = 6000)   // apr 4000 fully, may 2000
        assertThat(a.entries).containsExactly(AllocationEntry(apr, 4000), AllocationEntry(may, 2000))
    }

    @Test
    fun `a payment must be a positive amount`() {
        assertThatThrownBy { PaymentAllocation.allocate(debts(mar to 6000), 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
