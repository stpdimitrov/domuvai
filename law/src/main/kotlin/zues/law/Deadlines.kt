package zues.law

import zues.kernel.CivilDate
import zues.kernel.deadline
import zues.kernel.isWeekend

/**
 * The statutory non-working-day calendar. Fixed public holidays (plus weekends,
 * from the kernel). The movable Orthodox Easter days, the КТ чл. 154 ал. 2
 * weekend-substitution rule and the ГПК чл. 60 roll for ЗУЕС deadlines are
 * unconfirmed. Rule: PM-SYS-005
 */
data class Holiday(val date: String, val name: String) // date is MM-DD

data class NonWorkingConfig(
    val verified: Boolean,
    val source: String,
    val todoLegal: String?,
    val fixed: List<Holiday>,
)

private val NON_WORKING = NonWorkingConfig(
    verified = false,
    source = "Кодекс на труда чл. 154",
    todoLegal = "PM-SYS-005 — confirm the official non-working-day list, the movable Orthodox Easter days " +
        "(Разпети петък … Велики понеделник) and the КТ чл. 154 ал. 2 weekend-substitution rule against the " +
        "consolidated Кодекс на труда, and confirm the ГПК чл. 60 roll rule as it applies to ЗУЕС deadlines.",
    fixed = listOf(
        Holiday("01-01", "Нова година"),
        Holiday("03-03", "Ден на Освобождението на България"),
        Holiday("05-01", "Ден на труда и на международната работническа солидарност"),
        Holiday("05-06", "Гергьовден, Ден на храбростта и на Българската армия"),
        Holiday("05-24", "Ден на българската просвета и култура и на славянската писменост"),
        Holiday("09-06", "Ден на Съединението на България"),
        Holiday("09-22", "Ден на независимостта на България"),
        Holiday("12-24", "Бъдни вечер"),
        Holiday("12-25", "Рождество Христово"),
        Holiday("12-26", "Рождество Христово"),
    ),
)

/** The verification state of the statutory non-working-day set. Rule: PM-SYS-005 */
fun nonWorkingDaysMeta(): NonWorkingConfig = NON_WORKING

/**
 * The statutory fixed holidays in force on a legal date, as `MM-DD` → name.
 * Rule: PM-SYS-005
 */
fun statutoryHolidaysOn(on: LegalDate): Map<String, String> {
    // TODO(legal): PM-SYS-005 — holidays are treated as always-in-force; movable
    // Easter and the weekend-substitution rule are not yet encoded. The legal
    // date is taken now so callers need not change when holidays gain temporal bounds.
    @Suppress("UNUSED_EXPRESSION") on
    return NON_WORKING.fixed.associate { it.date to it.name }
}

/** A legal deadline may not fall on a weekend or a statutory holiday. Rule: PM-SYS-005 */
fun isStatutoryNonWorkingDay(d: CivilDate, on: LegalDate): Boolean =
    isWeekend(d) || statutoryHolidaysOn(on).containsKey(d.substring(5))

/**
 * The single statutory deadline: `days` calendar days from `from`, rolled off any
 * weekend or holiday to the next working day, under the law in force on `on`.
 * The one entry point every module uses. Rule: PM-SYS-004, PM-SYS-005
 */
fun statutoryDeadline(from: CivilDate, days: Int, on: LegalDate): CivilDate =
    deadline(from, days) { d -> isStatutoryNonWorkingDay(d, on) }
