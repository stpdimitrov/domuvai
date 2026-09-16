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
const val ENGINE_VERSION: String = "0.1.0"

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
)

/** The value in force on the legal date — never today's value. Rule: PM-SYS-002 */
fun constantOn(code: String, on: LegalDate): Constant =
    CONSTANTS.filter { it.code == code && it.inForceFrom <= on }.maxByOrNull { it.inForceFrom }
        ?: throw NoSuchElementException("no constant $code in force on $on — stop and ask (PM-SYS-001)")

fun numberOn(code: String, on: LegalDate): Double = constantOn(code, on).value.toDouble()

/** Every constant whose number is not yet confirmed against the consolidated statute. */
fun unverified(): List<Constant> = CONSTANTS.filter { !it.verified }
