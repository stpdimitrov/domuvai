package zues.app.assembly

import zues.kernel.toSofiaDate
import java.time.Instant
import java.time.LocalDate

/**
 * Fixture SQL dated by today in Sofia — the day the app asks the policy about (PM-SYS-004) — not by Postgres's
 * `current_date`, which is the session's day (UTC in CI) and runs a day behind Sofia's from 21:00 or 22:00 UTC (#213).
 * Every `current_date` in [sql] becomes that day as a date literal, so `current_date + 1` stays tomorrow in Sofia.
 */
internal fun sofiaDated(sql: String): String = sql.replace("current_date", "DATE '${LocalDate.parse(toSofiaDate(Instant.now()))}'")
