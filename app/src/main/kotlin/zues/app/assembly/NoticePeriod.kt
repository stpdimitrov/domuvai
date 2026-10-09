package zues.app.assembly

import zues.kernel.addCalendarDays
import zues.kernel.toSofiaDate
import zues.law.numberOn
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Whether a notice posted at [postedAt] is in time for a meeting at [scheduledAt], under the law in force
 * on the day of posting. Ordinarily the period is whole calendar days between the two Sofia days
 * (PM-SYS-004); for an urgent assembly it is elapsed hours between the two instants. Both numbers are `law`'s.
 *
 * Two readings, to be confirmed by counsel: a notice posted on day D admits a meeting from day D + the period —
 * the catalogue's "6 days out is blocked" (PM-GA-004), and the least strict count; and the minimum is not rolled
 * off a weekend or holiday (PM-SYS-005) — rolling the last day to post forward would shorten the notice.
 */
// Rule: PM-GA-004
// Rule: PM-GA-005
fun noticeInTime(postedAt: Instant, scheduledAt: Instant, urgent: Boolean): Boolean {
    val on = toSofiaDate(postedAt)
    // TODO(legal): PM-GA-004 — confirm whether the day of posting counts towards the period
    if (urgent) {
        // TODO(legal): PM-GA-005 — the shortened period is unconfirmed
        return !scheduledAt.isBefore(postedAt.plus(numberOn("GA_URGENT_NOTICE_HOURS", on).toLong(), ChronoUnit.HOURS))
    }
    return toSofiaDate(scheduledAt) >= addCalendarDays(on, numberOn("GA_NOTICE_DAYS", on).toInt())
}

/** Why a meeting is too soon after [postedAt], stating the period the law has in force that day. */
fun tooSoon(postedAt: Instant, urgent: Boolean): String {
    val on = toSofiaDate(postedAt)
    return if (urgent) {
        "an urgent assembly meets at least ${numberOn("GA_URGENT_NOTICE_HOURS", on).toInt()} hours after its notice is posted (PM-GA-005)"
    } else {
        "an assembly meets at least ${numberOn("GA_NOTICE_DAYS", on).toInt()} days after its notice is posted — " +
            "not before ${addCalendarDays(on, numberOn("GA_NOTICE_DAYS", on).toInt())} (PM-GA-004)"
    }
}
