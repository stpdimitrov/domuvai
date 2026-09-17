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
 * in order, so the shape is stable for a caller.
 */
data class UnitArrears(
    val unitId: UUID,
    val asOf: String,
    val totalMinor: Long,
    val buckets: List<AgeingBucket>,
)

/**
 * Arrears ageing for a unit (Rule: PM-DEBT-001). Reads only the unit's receivable postings — every
 * one is owed until a payment posts its credit (ADR-006). A charge falls due `PAYMENT_TERM_DAYS`
 * after its value date (Rule: PM-DEBT-002); the value date stands in for the decision's
 * announcement until the assembly module records one. Overdue days are counted from that due date.
 */
@Service
class ArrearsService(private val postings: PostingRepository) {

    @Transactional(readOnly = true)
    fun forUnit(unitId: UUID, asOf: LocalDate): UnitArrears {
        val term = numberOn("PAYMENT_TERM_DAYS", asOf.toString()).toLong()
        val rows = postings.findByUnitIdAndAccount(unitId, Ledger.RECEIVABLE)
        val byBand = rows
            .groupBy { Ageing.band(ChronoUnit.DAYS.between(it.valueDate.plusDays(term), asOf)) }
            .mapValues { (_, ps) -> ps.sumOf { it.amountMinor } }
        return UnitArrears(
            unitId = unitId,
            asOf = asOf.toString(),
            totalMinor = rows.sumOf { it.amountMinor },
            buckets = Ageing.BANDS.map { AgeingBucket(it, byBand[it] ?: 0) },
        )
    }
}
