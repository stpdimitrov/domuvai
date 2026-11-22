package zues.app.money

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

/**
 * One line of a unit's statement — a charged amount with the reasoning behind it (Rule:
 * PM-FEE-018): the period, the cost stream, the allocation key, the count/quantity the key used,
 * and the derivation in words. Immutable: it is read straight from the issued charge line.
 */
data class StatementLine(
    val period: String,
    val component: String,        // MANAGEMENT | MAINTENANCE | REPAIR_FUND
    val allocationKey: String,    // PER_PERSON | BY_IDEAL_PARTS | PER_UNIT — or METERED, a consumption line (PM-FEE-017)
    val quantity: BigDecimal,     // the key's count, or a metered line's reading
    val amountMinor: Long,
    val derivation: String,
    val item: String? = null,     // CONCIERGE, WATER, HEATING — or none for the stream's own line
    /** The per-count rate billed (a metered line's price per unit of measure); null for a share of a total. */
    val rateMinor: Long? = null,
    /** The derivation in Bulgarian, from the run's stored basis (PM-FEE-018); null when the basis has no line to trace it to. */
    val derivationBg: String? = null,
)

/**
 * What a unit owes and why. `balanceMinor` is **derived from the ledger** — the sum of the unit's
 * receivable postings (ADR-006), so once payments post their credits the balance falls without
 * touching the immutable charge lines. `lines` itemise the charges behind it (PM-FEE-018).
 */
data class UnitStatement(
    val unitId: UUID,
    val balanceMinor: Long,
    val lines: List<StatementLine>,
)

/**
 * A resident's itemised statement (Rule: PM-FEE-018). Reads only `money`'s own tables — the
 * receivable postings for the balance and the issued charge lines for the itemisation, dated by
 * their run. A unit with nothing billed owes nothing: an empty statement, not an error.
 */
@Service
class StatementService(
    private val postings: PostingRepository,
    private val chargeLines: ChargeLineRepository,
    private val chargeRuns: ChargeRunRepository,
) {
    @Transactional(readOnly = true)
    fun forUnit(unitId: UUID): UnitStatement {
        val balanceMinor = postings.findByUnitIdAndAccount(unitId, Ledger.RECEIVABLE).sumOf { it.amountMinor }
        val lines = chargeLines.findByUnitId(unitId)
        val runs = chargeRuns.findAllById(lines.map { it.chargeRunId }.distinct())
        val periodByRun = runs.associate { it.id to it.period }
        val basisByRun = runs.associate { it.id to DerivationBg.parse(it.basis.json) }
        val statementLines = lines
            .sortedWith(compareBy({ periodByRun[it.chargeRunId] ?: "?" }, { it.component }, { it.item ?: "" }))
            .map {
                val (rateMinor, derivationBg) = basisByRun[it.chargeRunId]?.let { b -> DerivationBg.of(it, b) } ?: (null to null)
                StatementLine(
                    period = periodByRun[it.chargeRunId] ?: "?",
                    component = it.component,
                    allocationKey = it.allocationKey,
                    quantity = it.quantity,
                    amountMinor = it.amountMinor,
                    derivation = it.derivation,
                    item = it.item,
                    rateMinor = rateMinor,
                    derivationBg = derivationBg,
                )
            }
        return UnitStatement(unitId, balanceMinor, statementLines)
    }
}
