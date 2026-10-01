package zues.app.intake

import zues.charges.PropertyUnit
import zues.charges.Tariff
import zues.charges.TariffLine
import zues.charges.computeChargeRun
import zues.kernel.IdealParts
import zues.law.AllocationKey
import zues.law.CostItem
import zues.law.CostStream

/** One tariff line the firm's fees were built on — the same shape the engine bills from. */
data class TariffInput(
    val stream: String,          // MANAGEMENT | MAINTENANCE | REPAIR_FUND
    val key: String,             // PER_PERSON | BY_IDEAL_PARTS | PER_UNIT
    val decisionId: String,      // the GA decision that adopted it (PM-FEE-012)
    val rateMinor: Long? = null,
    val totalMinor: Long? = null,
    val item: String? = null,    // CONCIERGE — a cost named within its stream (PM-FEE-011); none for the stream's own line.
                                 // A metered cost (WATER, HEATING) is never a keyed line: named here, it is reported (PM-FEE-017)
)

/** A unit whose recomputed fee does not match the firm's — the whole point of the dry-run. */
data class UnitDiff(
    val designation: String,
    val theirMinor: Long,
    val ourMinor: Long,
    val deltaMinor: Long,        // ours − theirs
)

/**
 * The dry-run verdict. `reproduced` is Gate 1: the sheet parsed cleanly and every recomputed fee
 * equals the firm's, to the cent. Otherwise `differences` names each unit that diverged and
 * `violations` names what could not be read or reconciled.
 */
data class DryRunReport(
    val rowsParsed: Int,
    val matched: Int,
    val differing: Int,
    val differences: List<UnitDiff>,
    val violations: List<String>,
    val reproduced: Boolean,
)

/**
 * Recompute a firm's fee sheet through the same engine that will bill it, and compare to the
 * cent (Gate 1, PM-FEE-014). Pure: no clock, no I/O. The engine enforces the statutory guards —
 * ideal parts summing to a full share (PM-ORG-002), a tariff line needing a GA decision, a concierge
 * line allocated as maintenance is (PM-FEE-011) — and a failure to compute becomes a reported
 * violation, not a thrown error, because a dry-run's job is to surface problems rather than raise them.
 */
object IntakeDryRun {

    fun of(
        entranceId: String,
        period: String,
        legalDate: String,
        businessMultiplier: Int?,
        lines: List<TariffInput>,
        sheet: ParsedSheet,
    ): DryRunReport {
        // The tariff is the caller's specification, not the sheet's: a malformed one — a stream, key or
        // cost the law does not know — is their error, whatever the sheet holds.
        // Rule: PM-FEE-011 — a line's named cost reaches the engine, so a concierge line is checked as one
        // and not as an unnamed maintenance line.
        val tariff = Tariff(
            entranceId = entranceId,
            period = period,
            legalDate = legalDate,
            lines = lines.map {
                TariffLine(
                    CostStream.valueOf(it.stream), AllocationKey.valueOf(it.key), it.decisionId, it.rateMinor, it.totalMinor,
                    it.item?.let { item -> CostItem.valueOf(item) },
                )
            },
            businessMultiplier = businessMultiplier,
        )

        val rows = sheet.rows
        val violations = sheet.violations.toMutableList()
        if (rows.isEmpty()) {
            return DryRunReport(0, 0, 0, emptyList(), (violations + "no rows to reproduce").distinct(), false)
        }

        // Unit construction and the run can fail on the sheet's own data (bad ideal parts, a sum
        // that is not 100%): report it rather than throw. Row order is preserved, so the recomputed
        // charges line up with the rows positionally.
        val computed = try {
            val units = rows.mapIndexed { i, r ->
                PropertyUnit(i.toString(), r.designation, IdealParts.of(r.idealParts), r.occupants)
            }
            computeChargeRun(entranceId, units, tariff)
        } catch (e: RuntimeException) {
            return DryRunReport(rows.size, 0, 0, emptyList(), violations + (e.message ?: "could not compute"), false)
        }

        val differences = rows.zip(computed.charges).mapNotNull { (row, charge) ->
            val ours = charge.total.amountMinor
            if (ours != row.theirFeeMinor) UnitDiff(row.designation, row.theirFeeMinor, ours, ours - row.theirFeeMinor)
            else null
        }
        return DryRunReport(
            rowsParsed = rows.size,
            matched = rows.size - differences.size,
            differing = differences.size,
            differences = differences,
            violations = violations,
            reproduced = violations.isEmpty() && differences.isEmpty(),
        )
    }
}
