package zues.kernel

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant

class SysTimeTest {

    // ---------------------------------------------------------------- PM-SYS-003
    @Test
    fun `PM-SYS-003 statutory documents are produced in Bulgarian, English optional`() {
        assertEquals(Language.BG, STATUTORY_LANGUAGE)
        assertEquals(Language.BG, DEFAULT_UI_LANGUAGE)
        assertDoesNotThrow { assertStatutoryLanguage(Language.BG) }
        val ex = assertThrows(IllegalArgumentException::class.java) { assertStatutoryLanguage(Language.EN) }
        assertTrue(ex.message!!.contains("must be produced in BG"))
    }

    // ---------------------------------------------------------------- PM-SYS-004
    @Test
    fun `PM-SYS-004 seven calendar days across the DST change is still seven days`() {
        // Europe/Sofia springs forward on 2026-03-29; the civil span must not slip.
        assertEquals("2026-04-01", addCalendarDays("2026-03-25", 7))
        assertEquals("2026-03-01", addCalendarDays("2026-02-28", 1)) // 2026 is not a leap year
    }

    @Test
    fun `PM-SYS-004 an instant stored in UTC resolves to its Europe Sofia civil day`() {
        // 22:30Z on New Year's Eve is already 00:30 on 1 Jan in Sofia (UTC+2 in winter).
        assertEquals("2026-01-02", toSofiaDate(Instant.parse("2026-01-01T22:30:00Z")))
        // 21:30Z at midsummer is 00:30 next day in Sofia (UTC+3 in summer).
        assertEquals("2026-07-01", toSofiaDate(Instant.parse("2026-06-30T21:30:00Z")))
    }

    @Test
    fun `PM-SYS-004 a malformed or impossible date is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { addCalendarDays("2026-02-30", 1) }
        assertThrows(IllegalArgumentException::class.java) { addCalendarDays("01-01-2026", 1) }
    }

    // ---------------------------------------------------------------- PM-SYS-005
    @Test
    fun `PM-SYS-005 a deadline landing on a weekend rolls to the next working day`() {
        assertEquals(DayOfWeek.SATURDAY, weekday("2026-01-03"))
        assertTrue(isWeekend("2026-01-03"))
        assertFalse(isWeekend("2026-01-05")) // Monday
        // 2 Jan (Fri) + 1 day = 3 Jan (Sat) → rolls to 5 Jan (Mon).
        assertEquals("2026-01-05", deadline("2026-01-02", 1, ::isWeekend))
        // a working-day deadline is untouched.
        assertEquals("2026-01-06", deadline("2026-01-05", 1, ::isWeekend))
    }

    @Test
    fun `PM-SYS-005 a predicate that never yields a working day cannot loop forever`() {
        // A fractional number of days is a compile error — days is an Int.
        assertThrows(IllegalStateException::class.java) { rollToWorkingDay("2026-01-01") { true } }
    }
}
