package zues.app.money

import org.springframework.beans.factory.annotation.Autowired
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
 * in order, so the shape is stable for a caller. `oldestDebt` is the oldest debt still open — the one
 * that fell due first — absent when nothing is owed. `advanceMinor` is the credit the unit holds from
 * overpayments made by the read date, and `netMinor` what it still owes after that credit — never below
 * zero; `totalMinor` and the buckets stay gross, because an advance settles no particular debt.
 */
data class UnitArrears(
    val unitId: UUID,
    val asOf: String,
    val totalMinor: Long,
    val buckets: List<AgeingBucket>,
    val oldestDebt: OldestDebt? = null,
    val advanceMinor: Long = 0,
    val netMinor: Long = totalMinor,
)

/** A unit's oldest debt still open (Rule: PM-DEBT-002): the day it fell due, and how far past it the read date is — 0 while not yet due. */
data class OldestDebt(val dueOn: LocalDate, val overdueDays: Long)

/**
 * An entrance's arrears in one read (Rule: PM-DEBT-001): every unit that owes something on the read
 * date, aged as its own read ages it, largest first, and what they owe together — gross, the credit
 * their advances cover, and net, so `totalMinor − advanceMinor = netMinor`. A unit's advance covers only
 * that unit's debt (money never moves between units, PM-PMC-008): a surplus shows on the unit's own
 * figure, not here. A unit with an open debt is listed even when its advance covers it; a unit owing
 * nothing is left out, whatever advance it holds.
 */
data class EntranceArrears(
    val entranceId: UUID,
    val asOf: String,
    val totalMinor: Long,
    val units: List<UnitArrears>,
    val advanceMinor: Long = 0,
    val netMinor: Long = totalMinor,
)

/**
 * Arrears ageing for a unit (Rule: PM-DEBT-001). Reads the unit's receivable postings made by the
 * read date — and its advance postings, for the credit it holds — — a charge is owed until a payment posts its credit (ADR-006), and a payment made
 * after the read date had not reduced it yet. A charge falls due `PAYMENT_TERM_DAYS` after its
 * value date (Rule: PM-DEBT-002); the value date stands in for the decision's announcement until
 * the assembly module records one. The term is the one in force on the debt's own date, not on the
 * read date (Rule: PM-SYS-002): the read date picks which postings count, never when a debt fell
 * due. Overdue days are counted from that due date, and a payment's credit is banded with the debt
 * it settled.
 *
 * [termOn] is the payment term in force on a date. It is passed in so that a test can supply two
 * terms; the running service reads it from the law catalogue, the only home of the number.
 */
@Service
class ArrearsService(private val postings: PostingRepository, private val termOn: (LocalDate) -> Long) {

    @Autowired
    constructor(postings: PostingRepository) : this(postings, ::paymentTermOn)

    @Transactional(readOnly = true)
    fun forUnit(unitId: UUID, asOf: LocalDate): UnitArrears =
        aged(unitId, postings.findByUnitIdAndAccount(unitId, Ledger.RECEIVABLE), postings.findByUnitIdAndAccount(unitId, Ledger.ADVANCE), asOf)

    /** Rule: PM-DEBT-001 — the entrance's receivable and advance postings, unit by unit, each aged exactly as [forUnit] ages it. */
    @Transactional(readOnly = true)
    fun forEntrance(entranceId: UUID, asOf: LocalDate): EntranceArrears {
        val advances = postings.findByEntranceIdAndAccount(entranceId, Ledger.ADVANCE).groupBy { it.unitId }
        val owing = postings.findByEntranceIdAndAccount(entranceId, Ledger.RECEIVABLE)
            .filter { it.unitId != null }
            .groupBy { it.unitId!! }
            .map { (unitId, rows) -> aged(unitId, rows, advances[unitId].orEmpty(), asOf) }
            .filter { it.totalMinor > 0 }
            .sortedWith(compareByDescending<UnitArrears> { it.totalMinor }.thenBy { it.unitId })
        return EntranceArrears(
            entranceId, asOf.toString(), owing.sumOf { it.totalMinor }, owing,
            advanceMinor = owing.sumOf { minOf(it.advanceMinor, it.totalMinor) }, netMinor = owing.sumOf { it.netMinor },
        )
    }

    private fun aged(unitId: UUID, receivables: List<PostingRow>, advances: List<PostingRow>, asOf: LocalDate): UnitArrears {
        val rows = receivables.filter { !it.valueDate.isAfter(asOf) }
        // Rule: PM-DEBT-001 — the unit's credit from overpayments made by the read date (an ADVANCE credit is negative).
        val advanceMinor = -advances.filter { !it.valueDate.isAfter(asOf) }.sumOf { it.amountMinor }
        val totalMinor = rows.sumOf { it.amountMinor }
        // Rule: PM-SYS-002, PM-DEBT-002 — each debt falls due the term in force on its own date after it. Only the debts
        // raised by the read date are looked up, open or settled: a read date before any term has none, and reads as
        // nothing owed; a debt dated before any term stops the read — the unit's and its entrance's — naming the
        // missing constant.
        val dueOn = rows.map { Ledger.debtDate(it) }.distinct().associateWith { it.plusDays(termOn(it)) }
        val byBand = rows
            .groupBy { Ageing.band(ChronoUnit.DAYS.between(dueOn.getValue(Ledger.debtDate(it)), asOf)) }
            .mapValues { (_, ps) -> ps.sumOf { it.amountMinor } }
        // The oldest debt still open: a charge's date whose postings — the charge and the credits that settled it — net
        // above zero. The oldest is the one that fell due first, which a change of term can make a later charge.
        val oldestDueOn = rows.groupBy { Ledger.debtDate(it) }
            .filterValues { debt -> debt.sumOf { it.amountMinor } > 0 }
            .keys.minOfOrNull { dueOn.getValue(it) }
        return UnitArrears(
            unitId = unitId,
            asOf = asOf.toString(),
            totalMinor = totalMinor,
            buckets = Ageing.BANDS.map { AgeingBucket(it, byBand[it] ?: 0) },
            oldestDebt = oldestDueOn?.let { OldestDebt(it, maxOf(0, ChronoUnit.DAYS.between(it, asOf))) },
            advanceMinor = advanceMinor,
            netMinor = maxOf(0, totalMinor - advanceMinor),
        )
    }
}

/** Rule: PM-DEBT-002 — the payment term in force on a date, from dated configuration (Rule: PM-SYS-002). */
private fun paymentTermOn(date: LocalDate): Long = numberOn("PAYMENT_TERM_DAYS", date.toString()).toLong()
