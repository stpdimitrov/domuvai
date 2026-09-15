package zues.kernel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Civil-date arithmetic for legal deadlines. Pure: no clock, no I/O — every
 * function takes its date as an argument, so a deadline never depends on when
 * the code happens to run. Rule: PM-SYS-004
 *
 * A CivilDate is a Europe/Sofia calendar day in ISO `YYYY-MM-DD`. Deadlines are
 * reasoned about as civil days (java.time.LocalDate), never as instants, so no
 * timezone hour can slip across a DST change. Rule: PM-SYS-004
 */
typealias CivilDate = String

private val SOFIA = ZoneId.of("Europe/Sofia")

private fun parse(d: CivilDate): LocalDate =
    try {
        LocalDate.parse(d)
    } catch (e: Exception) {
        throw IllegalArgumentException("date must be a valid ISO YYYY-MM-DD, got \"$d\" (PM-SYS-004)", e)
    }

/**
 * Add whole calendar days on the civil calendar. LocalDate never touches
 * wall-clock time, so a span crossing a daylight-saving change is still exactly
 * `days`. `days` is an `Int`, so a fractional day cannot compile.
 * Rule: PM-SYS-004, PM-SYS-005
 */
fun addCalendarDays(from: CivilDate, days: Int): CivilDate =
    parse(from).plusDays(days.toLong()).toString()

/** Day of week for the civil date. */
fun weekday(d: CivilDate): java.time.DayOfWeek = parse(d).dayOfWeek

/** Saturday or Sunday — a civil-calendar fact, not a statutory number. */
fun isWeekend(d: CivilDate): Boolean {
    val w = parse(d).dayOfWeek
    return w == java.time.DayOfWeek.SATURDAY || w == java.time.DayOfWeek.SUNDAY
}

/**
 * A predicate naming the days a legal deadline may not fall on. Statutory
 * holidays are injected (from @zues/law) so this stays pure and law-free.
 * Rule: PM-SYS-005
 */
typealias NonWorkingDay = (CivilDate) -> Boolean

/** Move forward to the first working day on or after `d`. Rule: PM-SYS-005 */
fun rollToWorkingDay(d: CivilDate, isNonWorking: NonWorkingDay): CivilDate {
    var cur: CivilDate = parse(d).toString() // normalise + validate
    var guard = 0
    while (isNonWorking(cur)) {
        cur = addCalendarDays(cur, 1)
        if (++guard > 366) throw IllegalStateException("no working day within a year of $d — check the predicate (PM-SYS-005)") // not-legal: loop safety bound
    }
    return cur
}

/**
 * The one deadline utility. Add `days` calendar days (PM-SYS-005: calendar days
 * unless the statute says otherwise), then, if it lands on a non-working day,
 * roll to the next working day. Use this everywhere; do not re-derive it.
 * Rule: PM-SYS-004, PM-SYS-005
 */
fun deadline(from: CivilDate, days: Int, isNonWorking: NonWorkingDay): CivilDate =
    rollToWorkingDay(addCalendarDays(from, days), isNonWorking)

/**
 * The Europe/Sofia calendar day of an instant. Instants are stored in UTC; legal
 * reasoning happens on the Sofia civil day (PM-SYS-004). Pure: the instant is an
 * argument, never `Instant.now()`. Rule: PM-SYS-004
 */
fun toSofiaDate(instant: Instant): CivilDate =
    instant.atZone(SOFIA).toLocalDate().toString()
