package zues.kernel

import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/** One figure of a report: a stable key and an amount in integer minor units. Rule: PM-SYS-012 */
data class ReportLine(val key: String, val amount: Money) {
    init {
        require(key.isNotBlank() && key.none { it == '\t' || it == '\n' }) {
            "a report line's key is non-blank and has no tab or newline (PM-SYS-012)"
        }
    }
}

/**
 * A report as it was exported: its period, its lines in key order, when it was exported, and the SHA-256 of the
 * canonical form of its period and lines. The same figures give the same hash whatever order they arrive in, and
 * whenever they are exported. Rule: PM-SYS-012
 */
class ReportSnapshot private constructor(
    val period: String,
    val lines: List<ReportLine>,
    val exportedAt: Instant,
    val hash: String,
) {

    companion object {
        /** Rule: PM-SYS-012 */
        fun of(period: String, lines: List<ReportLine>, exportedAt: Instant): ReportSnapshot {
            require(period.isNotBlank() && period.none { it == '\n' }) { "a report names its period (PM-SYS-012)" }
            val sorted = lines.sortedBy { it.key }
            val dup = sorted.zipWithNext().firstOrNull { (a, b) -> a.key == b.key }
            require(dup == null) { "report line \"${dup?.first?.key}\" appears twice (PM-SYS-012)" }
            val canonical = buildString {
                append(period).append('\n')
                sorted.forEach { append(it.key).append('\t').append(it.amount.amountMinor).append('\n') }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return ReportSnapshot(period, sorted, exportedAt, HexFormat.of().formatHex(digest))
        }
    }
}

/** A documented correction to a period's figures: the lines it changed, why, who and when. Rule: PM-SYS-012 */
data class ReportCorrection(
    val period: String,
    val changedKeys: Set<String>,
    val reason: String,
    val recordedBy: String,
    val recordedAt: Instant,
) {
    init {
        require(changedKeys.isNotEmpty()) { "a correction names the lines it changed (PM-SYS-012)" }
        require(reason.isNotBlank()) { "a correction needs a reason (PM-SYS-012)" }
        require(recordedBy.isNotBlank()) { "a correction names who recorded it (PM-SYS-012)" }
    }
}

/** A re-export whose figures differ from the original with no documented correction covering them. Rule: PM-SYS-012 */
class ReportNotReproduced(val period: String, val unexplained: Set<String>) :
    IllegalStateException("re-export of $period differs on $unexplained with no documented correction (PM-SYS-012)")

/**
 * Accepts a re-export of [original]'s period only if it has the same figures, or every line that differs —
 * changed, added or gone — is named by a correction for that period recorded after the original was exported: a
 * correction that intervened. Rule: PM-SYS-012
 */
fun verifyReexport(
    original: ReportSnapshot,
    reexport: ReportSnapshot,
    corrections: List<ReportCorrection> = emptyList(),
): ReportSnapshot {
    require(original.period == reexport.period) {
        "a re-export of ${original.period} cannot be checked against ${reexport.period} (PM-SYS-012)"
    }
    if (original.hash == reexport.hash) return reexport
    val before = original.lines.associate { it.key to it.amount }
    val after = reexport.lines.associate { it.key to it.amount }
    val changed = (before.keys + after.keys).filterTo(mutableSetOf()) { before[it] != after[it] }
    val covered = corrections.filter { it.period == original.period && it.recordedAt.isAfter(original.exportedAt) }.flatMapTo(mutableSetOf()) { it.changedKeys }
    val unexplained = changed - covered
    if (unexplained.isNotEmpty()) throw ReportNotReproduced(original.period, unexplained)
    return reexport
}
