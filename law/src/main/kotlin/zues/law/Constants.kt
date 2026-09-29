package zues.law

/**
 * @zues/law — the only place a legal number exists. ADR-001.
 *
 * No clock, no HTTP. Every lookup takes the legal date as an argument, so a
 * charge run cannot have its constants change mid-flight, and reproducing a past
 * charge means resolving the value in force on that date.
 */

/** YYYY-MM-DD, a Europe/Sofia calendar day (PM-SYS-004). */
typealias LegalDate = String

const val CATALOGUE_VERSION: String = "1.3"

/** Bumped whenever the pure functions change, so a receipt pins the code too. ADR-001 amendment. */
const val ENGINE_VERSION: String = "0.2.0"   // 0.2.0: a concierge line in maintenance (PM-FEE-011); a decision on every line (PM-FEE-003)

data class Constant(
    val code: String,
    val value: String,
    val inForceFrom: LegalDate,
    val source: String,
    val verified: Boolean,
    val rule: String? = null,
    val todoLegal: String? = null,
)

private val CONSTANTS: List<Constant> = listOf(
    Constant("EUR_BGN_RATE", "1.95583", "2026-01-01", "euro adoption", true),
    Constant("CHILD_AGE_NOT_COUNTED", "6", "2009-01-01", "чл. 51 ал. 2 ЗУЕС", true, rule = "PM-FEE-005"),
    Constant(
        "ABSENCE_EXEMPTION_DAYS", "30", "2009-01-01", "чл. 51 ал. 2 ЗУЕС", false, rule = "PM-FEE-006",
        todoLegal = "Confirm whether absence gives full exemption or a reduced share, and the exact day count.",
    ),
    Constant(
        // PM-FEE-007 — "MUST NOT apply it retroactively beyond the configured window." The
        // window is a policy number, not one the statute states, so it is unverified: the
        // value below is a placeholder counsel must confirm, never an asserted legal figure.
        "ABSENCE_DECLARATION_GRACE_DAYS", "30", "2009-01-01", "чл. 51 ЗУЕС", false, rule = "PM-FEE-007",
        todoLegal = "Confirm the grace window for filing an absence declaration after the absence " +
            "ends, and whether it is set by statute or by GA decision.",
    ),
    Constant("OCCUPANT_THRESHOLD_DAYS", "30", "2009-01-01", "чл. 7 ал. 2 т. 6, чл. 51 ЗУЕС", true, rule = "PM-FEE-008"),
    Constant("ANIMAL_OCCUPANT_EQUIVALENT", "1", "2009-01-01", "чл. 51 ЗУЕС", true, rule = "PM-FEE-009"),
    Constant(
        "BUSINESS_USE_MULTIPLIER_MIN", "3", "2009-01-01", "чл. 51 ал. 3 ЗУЕС", false, rule = "PM-FEE-010",
        todoLegal = "Confirm the multiplier range and who chooses within it — GA decision or statute.",
    ),
    Constant(
        "BUSINESS_USE_MULTIPLIER_MAX", "5", "2009-01-01", "чл. 51 ал. 3 ЗУЕС", false, rule = "PM-FEE-010",
        todoLegal = "Confirm the multiplier range and who chooses within it — GA decision or statute.",
    ),
    // PM-DEBT-002 — where a decision sets no execution term, obligations fall due 14 days after
    // it is announced. A clear statutory figure, so it is confirmed.
    Constant("PAYMENT_TERM_DAYS", "14", "2009-01-01", "чл. 38 ал. 1 ЗУЕС", true, rule = "PM-DEBT-002"),
    // PM-BOOK-003 — owners and users declare for the book within 15 days of acquiring title or use.
    // The statute states the figure, so it is confirmed; it runs through statutoryDeadline (PM-SYS-005).
    Constant("BOOK_DECLARATION_DAYS", "15", "2009-01-01", "чл. 7 ал. 3 ЗУЕС", true, rule = "PM-BOOK-003"),
    // PM-BOOK-004 — the declaration is on the template the minister approves (чл. 7 ал. 7), so the
    // template is dated and versioned like any legal value, and each declaration records the version
    // in force on its filing date. Which order is current is not in this repository: the value is a
    // placeholder identifier, unconfirmed, never an asserted order number.
    Constant(
        "BOOK_DECLARATION_TEMPLATE", "ministry-template-1", "2009-01-01", "чл. 7 ал. 7 ЗУЕС", false, rule = "PM-BOOK-004",
        todoLegal = "Record the currently approved declaration template — the minister's order under чл. 7 " +
            "ал. 7 ЗУЕС (number, date, effective date) — as a dated entry; `ministry-template-1` is a placeholder.",
    ),
    // PM-BOOK-010 — the book keeps personal data only while its basis lasts, then anonymises it, with
    // a window per field group, in months from the day the stay ended. No statute sets these: the
    // owner's default (2026-09-28) is 3 months where no law says otherwise, unconfirmed until counsel
    // does. Former owners and users have no entry — a claim for charges they owe outlives their title.
    Constant(
        "BOOK_RETENTION_HOUSEHOLD_MONTHS", "3", "2026-09-28", "GDPR art. 5(1)(e); owner decision 2026-09-28", false,
        rule = "PM-BOOK-010",
        todoLegal = "Confirm no statute requires keeping a former occupant's link to a named person longer than 3 months.",
    ),
    Constant(
        "BOOK_RETENTION_ANIMAL_MONTHS", "3", "2026-09-28", "GDPR art. 5(1)(e); owner decision 2026-09-28", false,
        rule = "PM-BOOK-010",
        todoLegal = "Confirm no statute requires keeping a departed animal's veterinary passport number longer than 3 months.",
    ),
)

/** The value in force on the legal date — never today's value. Rule: PM-SYS-002 */
fun constantOn(code: String, on: LegalDate): Constant =
    CONSTANTS.filter { it.code == code && it.inForceFrom <= on }.maxByOrNull { it.inForceFrom }
        ?: throw NoSuchElementException("no constant $code in force on $on — stop and ask (PM-SYS-001)")

fun numberOn(code: String, on: LegalDate): Double = constantOn(code, on).value.toDouble()

/** Every constant whose number is not yet confirmed against the consolidated statute. */
fun unverified(): List<Constant> = CONSTANTS.filter { !it.verified }
