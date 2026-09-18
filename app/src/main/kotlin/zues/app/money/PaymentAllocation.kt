package zues.app.money

import java.time.LocalDate

/**
 * PM-DEBT-008 — where a payment lands. A payment settles a unit's **oldest debt first** unless the
 * payer designates a particular one, and the rule applied is part of the result so it can be shown
 * (ЗЗД чл. 76: "allocation is explainable per payment"). Pure — no clock, no I/O, no database — so
 * the rule is proved without any of them; the money it moves is a later slice.
 *
 * A unit's debts are its charges, each dated. Outstanding is **derived, never stored** (ADR-006):
 * [netOutstanding] reduces the dated debts by the payments already made, oldest first, so the next
 * payment sees the correct remaining balance without anyone having written one down.
 */
object PaymentAllocation {
    const val OLDEST_FIRST = "OLDEST_FIRST"
    const val DESIGNATED = "DESIGNATED"

    /** One dated debt still owed — a charge's value date and what remains on it, in minor units. */
    data class OutstandingDebt(val valueDate: LocalDate, val outstandingMinor: Long)

    /** How much of the payment went to the debt of a given date — the explainable breakdown. */
    data class AllocationEntry(val debtDate: LocalDate, val amountMinor: Long)

    /**
     * The result: the rule applied (visible, PM-DEBT-008), what each debt received, and any part of
     * the payment left over once every debt is clear (an overpayment).
     */
    data class Allocation(val rule: String, val entries: List<AllocationEntry>, val unallocatedMinor: Long)

    /**
     * The debts still owed, after the payments already made are applied **oldest first** (ЗЗД чл. 76,
     * the same order a new payment follows). [priorCreditsMinor] is the total of prior payments as a
     * positive number. A debt fully covered by earlier payments drops out; one partly covered is
     * reduced. Debits need not arrive sorted.
     */
    fun netOutstanding(debits: List<OutstandingDebt>, priorCreditsMinor: Long): List<OutstandingDebt> {
        require(priorCreditsMinor >= 0) { "prior credits are a total, never negative" }
        var credit = priorCreditsMinor
        val out = mutableListOf<OutstandingDebt>()
        for (debt in debits.sortedBy { it.valueDate }) {
            val applied = minOf(credit, debt.outstandingMinor)
            credit -= applied
            val remaining = debt.outstandingMinor - applied
            if (remaining > 0) out.add(OutstandingDebt(debt.valueDate, remaining))
        }
        return out
    }

    /**
     * Allocate one payment across the [outstanding] debts. With no designation it is oldest debt
     * first; with a [designateDate] the debt of that date is settled first and the remainder then
     * falls to oldest-first. The rule that decided it is returned so a receipt can state it. A
     * payment larger than the total owed clears every debt and reports the rest as unallocated.
     */
    fun allocate(outstanding: List<OutstandingDebt>, paymentMinor: Long, designateDate: LocalDate? = null): Allocation {
        require(paymentMinor > 0) { "a payment must be a positive amount" }
        var remaining = paymentMinor
        val entries = mutableListOf<AllocationEntry>()
        for (debt in order(outstanding, designateDate)) {
            if (remaining <= 0) break
            val pay = minOf(remaining, debt.outstandingMinor)
            if (pay > 0) {
                entries.add(AllocationEntry(debt.valueDate, pay))
                remaining -= pay
            }
        }
        val rule = if (designateDate != null) DESIGNATED else OLDEST_FIRST
        return Allocation(rule, entries, remaining)
    }

    /** Oldest debt first; a designated date, if it is actually owed, goes to the front. */
    private fun order(outstanding: List<OutstandingDebt>, designateDate: LocalDate?): List<OutstandingDebt> {
        val oldestFirst = outstanding.filter { it.outstandingMinor > 0 }.sortedBy { it.valueDate }
        if (designateDate == null) return oldestFirst
        val (designated, rest) = oldestFirst.partition { it.valueDate == designateDate }
        return designated + rest
    }
}
