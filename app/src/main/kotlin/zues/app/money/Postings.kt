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
 * rule on the table). `posted_at` is left to the database default.
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
)

interface PostingRepository : ListCrudRepository<PostingRow, UUID> {
    fun findByJournalId(journalId: UUID): List<PostingRow>
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
    fun incomeAccount(stream: String) = "INCOME:$stream"

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
