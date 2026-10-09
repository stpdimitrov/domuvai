package zues.app.assembly

import zues.kernel.addCalendarDays
import zues.kernel.toSofiaDate
import zues.law.numberOn
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Whether a notice posted at [postedAt] is in time for a meeting at [scheduledAt], under the law in force
 * on the day of posting. Ordinarily the period is whole calendar days between the two Sofia days
 * (PM-SYS-004); for an urgent assembly it is hours between the two instants. Both numbers are `law`'s.
 */
// Rule: PM-GA-004
// Rule: PM-GA-005
fun noticeInTime(postedAt: Instant, scheduledAt: Instant, urgent: Boolean): Boolean {
    val on = toSofiaDate(postedAt)
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
