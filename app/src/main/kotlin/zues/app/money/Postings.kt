package zues.app.money

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import zues.charges.ChargeRun
import java.time.LocalDate
import java.util.UUID

/**
 * A double-entry posting (ADR-006): a signed amount against an account, in a journal that
 * must sum to zero. Immutable — a posted line is never rewritten (a `DO INSTEAD NOTHING`
 * rule on the table). `posted_at` is left to the database default. A receivable credit from a
 * payment names the debt it settled in `settlesValueDate`; every other leg leaves it null.
 */
@Table("posting")
data class PostingRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val journalId: UUID,
    val account: String,
    val unitId: UUID?,
    val amountMinor: Long,
    val currency: String,
    val valueDate: LocalDate,
    val settlesValueDate: LocalDate? = null,
)

interface PostingRepository : ListCrudRepository<PostingRow, UUID> {
    fun findByJournalId(journalId: UUID): List<PostingRow>
    fun findByUnitIdAndAccount(unitId: UUID, account: String): List<PostingRow>
}

/**
 * Turns a computed run into the postings that record it. Pure — no clock, no I/O — so the
 * double-entry can be proved without a database. Each unit's charge is a debit to its
 * receivable; the income is credited to the condominium's ledger, split by cost stream and
 * carrying no unit (Rule: PM-FEE-020). Debits and credits are equal, so the journal balances
 * to zero (ADR-006), which the database enforces again with a deferred trigger.
 */
object Ledger {
    const val RECEIVABLE = "RECEIVABLE"
    const val ADVANCE = "ADVANCE"
    fun incomeAccount(stream: String) = "INCOME:$stream"

    /** The account a payment landed in: one of the entrance's bank accounts, or the cash box. */
    fun receivedAccount(into: ReceivedInto) = if (into == ReceivedInto.CASH) "CASH" else "BANK:${into.name}"

    /** The debt a receivable posting belongs to: a charge's own date, or the one a credit settled. */
    fun debtDate(posting: PostingRow): LocalDate = posting.settlesValueDate ?: posting.valueDate

    /**
     * A unit's debts still open for a payment made [on] that date (Rule: PM-DEBT-008): each charge
     * raised by then, less every credit already recorded against it. Each payment credit names the debt it settled, so
     * this is exact whichever rule allocated it — no replay needed. A debt raised after [on] did not
     * exist when the money arrived, so a payment never reaches it.
     */
    fun openDebts(receivables: List<PostingRow>, on: LocalDate): List<PaymentAllocation.OutstandingDebt> =
        receivables.filter { it.amountMinor < 0 || !it.valueDate.isAfter(on) }
            .groupBy(::debtDate)
            .filterKeys { !it.isAfter(on) }
            .map { (date, ps) -> PaymentAllocation.OutstandingDebt(date, ps.sumOf { it.amountMinor }) }
            .filter { it.outstandingMinor > 0 }

    /**
     * Turns an allocated payment into its journal (Rule: PM-DEBT-008). Every leg is dated on the
     * payment's value date — a journal has one date. The money received is debited to the account
     * it landed in; each settled debt gets a RECEIVABLE credit naming that debt, so arrears ageing
     * (PM-DEBT-001) bands it with its debt; an overpayment is credited to the unit's ADVANCE. The
     * journal id is the payment's id, and the legs balance to zero (ADR-006).
     */
    fun forPayment(payment: PaymentRow, allocation: PaymentAllocation.Allocation): List<PostingRow> {
        fun leg(account: String, unitId: UUID?, amountMinor: Long, settles: LocalDate? = null) = PostingRow(
            id = UUID.randomUUID(),
            entranceId = payment.entranceId,
            journalId = payment.id,
            account = account,
            unitId = unitId,
            amountMinor = amountMinor,
            currency = "EUR",
            valueDate = payment.valueDate,
            settlesValueDate = settles,
        )
        val received = leg(receivedAccount(ReceivedInto.valueOf(payment.receivedInto)), null, payment.amountMinor)
        val settled = allocation.entries.map { leg(RECEIVABLE, payment.unitId, -it.amountMinor, settles = it.debtDate) }
        val advance = allocation.unallocatedMinor.takeIf { it > 0 }
            ?.let { listOf(leg(ADVANCE, payment.unitId, -it)) } ?: emptyList()
        return listOf(received) + settled + advance
    }

    fun forRun(run: ChargeRun, entranceId: UUID, journalId: UUID, valueDate: LocalDate): List<PostingRow> {
        val debits = run.charges.flatMap { charge ->
            charge.lines.map { line ->
                PostingRow(
                    id = UUID.randomUUID(),
                    entranceId = entranceId,
                    journalId = journalId,
                    account = RECEIVABLE,
                    unitId = UUID.fromString(charge.unitId),
                    amountMinor = line.amount.amountMinor,
                    currency = "EUR",
                    valueDate = valueDate,
                )
            }
        }
        val credits = run.charges.flatMap { it.lines }
            .groupBy { it.stream.name }
            .map { (stream, lines) ->
                PostingRow(
                    id = UUID.randomUUID(),
                    entranceId = entranceId,
                    journalId = journalId,
                    account = incomeAccount(stream),
                    unitId = null,
                    amountMinor = -lines.sumOf { it.amount.amountMinor },
                    currency = "EUR",
                    valueDate = valueDate,
                )
            }
        return debits + credits
    }
}
