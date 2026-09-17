package zues.app.intake

/**
 * A firm's fee sheet, parsed. Intake owns no rules; it enforces the existing ones on the way in
 * (ADR-003). This first cut reads a clean CSV export — a header naming the four columns, then a
 * row per unit — so the dry-run can recompute each fee and compare it to the firm's own (Gate 1).
 *
 * Money is minor units and ideal parts use a dot decimal (ADR-006, PM-FEE-016), so the sheet
 * carries no locale-formatted numbers yet; quoting, embedded delimiters and XLSX are later work.
 */
data class FeeRow(
    val designation: String,
    val idealParts: String,     // exact decimal percent, dot decimal
    val occupants: Int,
    val theirFeeMinor: Long,    // the fee the firm charges this unit, integer minor units
)

data class ParsedSheet(val rows: List<FeeRow>, val violations: List<String>)

object FeeSheet {
    private val REQUIRED = listOf("designation", "ideal_parts", "occupants", "fee_minor")

    fun parse(csv: String): ParsedSheet {
        val lines = csv.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ParsedSheet(emptyList(), listOf("the sheet is empty"))

        val header = lines.first().split(",").map { it.trim().lowercase() }
        val at = REQUIRED.associateWith { header.indexOf(it) }
        val missing = REQUIRED.filter { at.getValue(it) < 0 }
        if (missing.isNotEmpty()) {
            return ParsedSheet(emptyList(), listOf("missing column(s): ${missing.joinToString()}"))
        }

        val rows = mutableListOf<FeeRow>()
        val violations = mutableListOf<String>()
        lines.drop(1).forEachIndexed { i, line ->
            val cells = line.split(",")
            val rowNo = i + 2   // 1-based, past the header
            runCatching {
                val designation = cells[at.getValue("designation")].trim()
                require(designation.isNotBlank()) { "blank designation" }
                FeeRow(
                    designation = designation,
                    idealParts = cells[at.getValue("ideal_parts")].trim(),
                    occupants = cells[at.getValue("occupants")].trim().toInt(),
                    theirFeeMinor = cells[at.getValue("fee_minor")].trim().toLong(),
                )
            }.onSuccess { rows.add(it) }
                .onFailure { violations.add("row $rowNo could not be read: ${line.trim()}") }
        }
        return ParsedSheet(rows, violations)
    }
}
