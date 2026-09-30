package zues.app.money

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.law.numberOn
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Ageing bands for arrears (Rule: PM-DEBT-001). These are an accounting convention — 0–30 /
 * 31–60 / 61–90 / 90+ days overdue — not statutory numbers, which is why the edges are marked
 * `not-legal`. `CURRENT` is an amount not yet due.
 */
object Ageing {
    val BANDS = listOf("CURRENT", "0-30", "31-60", "61-90", "90+")
    private val EDGES = listOf(30L, 60L, 90L)   // not-legal: accounting ageing bands, a convention not a statute

    fun band(overdueDays: Long): String {
        if (overdueDays < 0) return BANDS.first()          // not yet due — CURRENT
        val i = EDGES.indexOfFirst { overdueDays <= it }
        return if (i < 0) BANDS.last() else BANDS[i + 1]
    }
}

data class AgeingBucket(val band: String, val amountMinor: Long)

/**
 * A unit's outstanding, aged (Rule: PM-DEBT-001). `totalMinor` is everything still owed; the
 * buckets split it by how overdue each charge is as of the read date. Every band is present,
 * in order, so the shape is stable for a caller. `oldestDebt` is the oldest debt still open — absent
 * when nothing is owed.
 */
data class UnitArrears(
    val unitId: UUID,
    val asOf: String,
    val totalMinor: Long,
    val buckets: List<AgeingBucket>,
    val oldestDebt: OldestDebt? = null,
)

/** A unit's oldest debt still open (Rule: PM-DEBT-002): the day it fell due, and how far past it the read date is — 0 while not yet due. */
data class OldestDebt(val dueOn: LocalDate, val overdueDays: Long)

/**
 * An entrance's arrears in one read (Rule: PM-DEBT-001): every unit that owes something on the read
 * date, aged as its own read ages it, largest first, and what they owe together. A unit owing nothing
 * is left out.
 */
data class EntranceArrears(
    val entranceId: UUID,
    val asOf: String,
    val totalMinor: Long,
    val units: List<UnitArrears>,
)

/**
 * Arrears ageing for a unit (Rule: PM-DEBT-001). Reads only the unit's receivable postings made by
 * the read date — a charge is owed until a payment posts its credit (ADR-006), and a payment made
 * after the read date had not reduced it yet. A charge falls due `PAYMENT_TERM_DAYS` after its
 * value date (Rule: PM-DEBT-002); the value date stands in for the decision's announcement until
 * the assembly module records one. Overdue days are counted from that due date, and a payment's
 * credit is banded with the debt it settled.
 */
@Service
class ArrearsService(private val postings: PostingRepository) {

    @Transactional(readOnly = true)
    fun forUnit(unitId: UUID, asOf: LocalDate): UnitArrears =
        aged(unitId, postings.findByUnitIdAndAccount(unitId, Ledger.RECEIVABLE), asOf, term(asOf))

    /** Rule: PM-DEBT-001 — the entrance's receivable postings, unit by unit, each aged exactly as [forUnit] ages it. */
    @Transactional(readOnly = true)
    fun forEntrance(entranceId: UUID, asOf: LocalDate): EntranceArrears {
        val term = term(asOf)
        val owing = postings.findByEntranceIdAndAccount(entranceId, Ledger.RECEIVABLE)
            .filter { it.unitId != null }
            .groupBy { it.unitId!! }
            .map { (unitId, rows) -> aged(unitId, rows, asOf, term) }
            .filter { it.totalMinor > 0 }
            .sortedWith(compareByDescending<UnitArrears> { it.totalMinor }.thenBy { it.unitId })
        return EntranceArrears(entranceId, asOf.toString(), owing.sumOf { it.totalMinor }, owing)
    }

    /** Rule: PM-DEBT-002 — the term in force on the read date, from dated configuration. A date before any term is a bad date, not a 500. */
    private fun term(asOf: LocalDate): Long = try {
        numberOn("PAYMENT_TERM_DAYS", asOf.toString()).toLong()
    } catch (e: NoSuchElementException) {
        throw IllegalArgumentException(e.message, e)
    }

    private fun aged(unitId: UUID, receivables: List<PostingRow>, asOf: LocalDate, term: Long): UnitArrears {
        val rows = receivables.filter { !it.valueDate.isAfter(asOf) }
        val byBand = rows
            .groupBy { Ageing.band(ChronoUnit.DAYS.between(Ledger.debtDate(it).plusDays(term), asOf)) }
            .mapValues { (_, ps) -> ps.sumOf { it.amountMinor } }
        // The oldest debt still open: a charge's date whose postings — the charge and the credits that settled it — net above zero.
        val oldestDueOn = rows.groupBy { Ledger.debtDate(it) }
            .filterValues { debt -> debt.sumOf { it.amountMinor } > 0 }
            .keys.minOrNull()?.plusDays(term)
        return UnitArrears(
            unitId = unitId,
            asOf = asOf.toString(),
            totalMinor = rows.sumOf { it.amountMinor },
            buckets = Ageing.BANDS.map { AgeingBucket(it, byBand[it] ?: 0) },
            oldestDebt = oldestDueOn?.let { OldestDebt(it, maxOf(0, ChronoUnit.DAYS.between(it, asOf))) },
        )
    }
}
