package zues.app.intake

/**
 * A firm's fee sheet, parsed — **mapping-driven** (ADR-012): the sheet's own columns are mapped onto
 * our domain fields, so any layout is read, not just one canonical header. A confirmed column→field
 * mapping is used when supplied; otherwise the profiler proposes one from the header, so a sheet
 * whose columns are recognised parses on its own and any other parses once a human confirms its
 * mapping. Intake owns no rules; it enforces the existing ones on the way in (ADR-003).
 *
 * Money is minor units and ideal parts use a dot decimal (ADR-006, PM-FEE-016); quoting, embedded
 * delimiters, locale numbers and XLSX are later work behind the same mapping seam.
 */
data class FeeRow(
    val designation: String,
    val idealParts: String,     // exact decimal percent, dot decimal
    val occupants: Int,
    val theirFeeMinor: Long,    // the fee the firm charges this unit, integer minor units
)

data class ParsedSheet(val rows: List<FeeRow>, val violations: List<String>)

object FeeSheet {

    /**
     * Parse a fee sheet into rows. [mapping] (column header → field) is used when given; otherwise
     * [MappingProfiler] proposes one from the header. Each required field — designation, ideal parts,
     * occupants and the firm's fee — must resolve to a column, or the whole sheet is a violation.
     */
    fun parse(csv: String, mapping: Map<String, IntakeField>? = null): ParsedSheet {
        val lines = csv.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ParsedSheet(emptyList(), listOf("the sheet is empty"))

        val header = lines.first().split(",").map { it.trim() }
        val effective = mapping ?: MappingProfiler.profile(header).mapping
        val columns = IntakeField.REQUIRED.associateWith { field ->
            val column = effective.entries.firstOrNull { it.value == field }?.key
            column?.let { c -> header.indexOfFirst { it.equals(c, ignoreCase = true) } } ?: -1
        }
        val missing = IntakeField.REQUIRED.filter { columns.getValue(it) < 0 }
        if (missing.isNotEmpty()) {
            return ParsedSheet(emptyList(), listOf("missing column(s) for: ${missing.joinToString { it.name.lowercase() }}"))
        }

        val rows = mutableListOf<FeeRow>()
        val violations = mutableListOf<String>()
        lines.drop(1).forEachIndexed { i, line ->
            val cells = line.split(",")
            val rowNo = i + 2   // 1-based, past the header
            runCatching {
                val designation = cells[columns.getValue(IntakeField.DESIGNATION)].trim()
                require(designation.isNotBlank()) { "blank designation" }
                FeeRow(
                    designation = designation,
                    idealParts = cells[columns.getValue(IntakeField.IDEAL_PARTS)].trim(),
                    occupants = cells[columns.getValue(IntakeField.OCCUPANTS)].trim().toInt(),
                    theirFeeMinor = cells[columns.getValue(IntakeField.FEE_MINOR)].trim().toLong(),
                )
            }.onSuccess { rows.add(it) }
                .onFailure { violations.add("row $rowNo could not be read: ${line.trim()}") }
        }
        return ParsedSheet(rows, violations)
    }
}
