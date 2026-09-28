package zues.app.intake

import java.math.BigDecimal

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
    /** The optional fields the sheet maps (ADR-012), as written; a blank cell is absent. */
    val optional: Map<IntakeField, String> = emptyMap(),
)

data class ParsedSheet(val rows: List<FeeRow>, val violations: List<String>)

object FeeSheet {

    /**
     * Parse a fee sheet into rows. [mapping] (column header → field) is used when given; otherwise
     * [MappingProfiler] proposes one from the header. Each required field — designation, ideal parts,
     * occupants and the firm's fee — must resolve to a column, or the whole sheet is a violation.
     * Optional fields are read as written when mapped; a built area or a child count that is not a
     * number the registry can hold makes its row a violation, never a silently rounded record.
     */
    fun parse(csv: String, mapping: Map<String, IntakeField>? = null): ParsedSheet {
        val lines = csv.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ParsedSheet(emptyList(), listOf("the sheet is empty"))

        val header = lines.first().split(",").map { it.trim() }
        val effective = mapping ?: MappingProfiler.profile(header).mapping
        fun columnOf(field: IntakeField): Int {
            val column = effective.entries.firstOrNull { it.value == field }?.key
            return column?.let { c -> header.indexOfFirst { it.equals(c, ignoreCase = true) } } ?: -1
        }
        val columns = IntakeField.REQUIRED.associateWith(::columnOf)
        val optionalColumns = IntakeField.entries.filterNot { it.required }.associateWith(::columnOf).filterValues { it >= 0 }
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
                    optional = optionalColumns
                        .mapNotNull { (field, i) -> cells.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { field to it } }
                        .toMap().also(::requireStorable),
                )
            }.onSuccess { rows.add(it) }
                .onFailure { violations.add("row $rowNo could not be read: ${line.trim()}") }
        }
        return ParsedSheet(rows, violations)
    }

    /**
     * A built area fits `registry.unit.area_m2` (positive, two decimals); a child count is whole; and
     * an owner cell is a name, never an identity number — the name is shown to other residents, so
     * an ЕГН, ЛНЧ or ЕИК written into it would leak (Rule: PM-BOOK-011).
     */
    private fun requireStorable(optional: Map<IntakeField, String>) {
        optional[IntakeField.BUILT_AREA]?.let { require(BigDecimal(it) > BigDecimal.ZERO && BigDecimal(it).scale() <= 2) }
        optional[IntakeField.CHILDREN_UNDER_6]?.let { require(it.toInt() >= 0) }
        optional[IntakeField.OWNER_NAME]?.let { require(!IDENTITY_NUMBER.containsMatchIn(it)) }
    }

    /** Nine or more digits in a row: the shape of an ЕГН, ЛНЧ or ЕИК — never part of a name. */
    private val IDENTITY_NUMBER = Regex("\\d{9,}")
}
