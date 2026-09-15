package zues.law

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import zues.kernel.deadline
import zues.kernel.isWeekend

class SysDeadlinesTest {

    private val on: LegalDate = "2026-09-13"

    @Test
    fun `PM-SYS-005 a statutory holiday on a weekday is still a non-working day`() {
        // 1 Jan 2026 is a Thursday — a working weekday, but Нова година.
        assertFalse(isWeekend("2026-01-01"))
        assertTrue(isStatutoryNonWorkingDay("2026-01-01", on))
        assertEquals("Нова година", statutoryHolidaysOn(on)["01-01"])
    }

    @Test
    fun `PM-SYS-005 the statutory deadline rolls off a holiday to the next working day`() {
        // 31 Dec 2025 (Wed) + 1 = 1 Jan 2026 (Thu, Нова година) → 2 Jan 2026 (Fri).
        assertEquals("2026-01-02", statutoryDeadline("2025-12-31", 1, on))
    }

    @Test
    fun `PM-SYS-005 statutoryDeadline is the kernel deadline wired with the statutory calendar`() {
        val from = "2026-01-01" // Нова година, a Thursday
        assertEquals(
            deadline(from, 0) { d -> isStatutoryNonWorkingDay(d, on) },
            statutoryDeadline(from, 0, on),
        )
        // differs from a weekend-only reading, because 1 Jan is a holiday but not a weekend.
        assertNotEquals(deadline(from, 0, ::isWeekend), statutoryDeadline(from, 0, on))
    }

    @Test
    fun `PM-SYS-005 the non-working-day set is unconfirmed and carries its legal TODO`() {
        val meta = nonWorkingDaysMeta()
        assertFalse(meta.verified)
        assertTrue(meta.todoLegal!!.contains("PM-SYS-005"))
        assertTrue(meta.source.contains("чл. 154"))
    }
}
