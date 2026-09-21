package zues.app.intake

/**
 * A proposed column → field mapping for one sheet, plus what the profiler could not place. Nothing is
 * dropped silently (STAGE1-ADDENDUM §1, ADR-012): an unrecognised column is surfaced for a human to
 * map or discard, and a required field with no column is named so the import cannot proceed until it
 * is mapped.
 */
data class ProposedMapping(
    val mapping: Map<String, IntakeField>,   // source column, as written → domain field
    val unmappedColumns: List<String>,
    val missingRequired: List<IntakeField>,
)

/**
 * Proposes how a sheet's columns map onto the domain fields, by matching each header against the
 * fields' known aliases (ADR-012). It only proposes — a human confirms or corrects — so a wrong guess
 * is caught at the confirm step, never committed silently. Header order does not matter, and columns
 * the profiler cannot place are surfaced rather than dropped.
 */
object MappingProfiler {

    fun profile(headers: List<String>): ProposedMapping {
        val mapping = LinkedHashMap<String, IntakeField>()
        val unmapped = mutableListOf<String>()
        for (header in headers) {
            val field = match(header)
            if (field != null && field !in mapping.values) mapping[header] = field else unmapped.add(header)
        }
        val missing = IntakeField.REQUIRED.filter { it !in mapping.values }
        return ProposedMapping(mapping, unmapped, missing)
    }

    /** The field whose aliases contain this header, both normalised (trimmed, lower-cased, spacing unified). */
    private fun match(header: String): IntakeField? {
        val h = normalise(header)
        return IntakeField.entries.firstOrNull { field -> field.aliases.any { normalise(it) == h } }
    }

    private fun normalise(s: String): String = s.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), " ")
}
