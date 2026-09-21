package zues.app.intake

/**
 * The domain fields a firm's fee sheet can carry — the **target** of any import, fixed by our rules,
 * not by any one spreadsheet (ADR-012). A sheet's own columns are mapped onto these; what is unknown
 * and variable is a firm's column *names*, never this set.
 *
 * `required` marks the minimum an import needs to reproduce and compare a fee — designation and ideal
 * parts (PM-BOOK-002, PM-ORG-002), the occupant headcount (PM-FEE-008) and the firm's own fee (for
 * the reproduce-to-the-cent check). The rest are recorded when present: built area and owner name
 * (PM-BOOK-002), children under six (PM-FEE-005), animals (PM-FEE-009), days of non-use (PM-FEE-006)
 * and business use (PM-FEE-010 / PM-ORG-009).
 *
 * `aliases` are lower-cased header spellings — English and Bulgarian — the profiler recognises. They
 * only drive a *proposal*: a human confirms or corrects it, so an unrecognised or mis-guessed column
 * is caught at the confirm step, never adopted silently (STAGE1-ADDENDUM §1).
 */
enum class IntakeField(val required: Boolean, val aliases: List<String>) {
    DESIGNATION(true, listOf("designation", "unit", "apartment", "обект", "апартамент", "ап.", "№")),
    IDEAL_PARTS(true, listOf("ideal_parts", "ideal parts", "идеални части", "ид.части")),
    OCCUPANTS(true, listOf("occupants", "persons", "people", "живущи", "брой лица", "лица")),
    FEE_MINOR(true, listOf("fee_minor", "fee_amount", "amount", "такса", "сума", "дължимо")),
    BUILT_AREA(false, listOf("built_area", "area", "area_m2", "площ", "кв.м", "квадратура")),
    OWNER_NAME(false, listOf("owner", "owner_name", "собственик", "име на собственик")),
    CHILDREN_UNDER_6(false, listOf("children", "children_under_6", "деца", "деца под 6")),
    ANIMALS(false, listOf("animals", "pets", "животни", "домашни любимци")),
    ABSENT_DAYS(false, listOf("absent_days", "absence", "отсъствие", "дни отсъствие")),
    BUSINESS_USE(false, listOf("business", "business_use", "стопанска дейност", "бизнес")),
    ;

    companion object {
        /** The fields an import cannot reproduce a fee without. */
        val REQUIRED: List<IntakeField> = entries.filter { it.required }
    }
}
