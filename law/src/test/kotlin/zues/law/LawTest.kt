package zues.law

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LawTest {

    private val on: LegalDate = "2026-09-13"

    @Test
    fun `PM-SYS-002 a constant resolves at the legal date, not today`() {
        assertEquals(1.95583, numberOn("EUR_BGN_RATE", "2026-06-01"), 1e-9)
        // the euro rate is not in force before adoption on 2026-01-01
        assertThrows(NoSuchElementException::class.java) { numberOn("EUR_BGN_RATE", "2025-12-31") }
    }

    @Test
    fun `PM-SYS-001 an unknown constant stops rather than guessing`() {
        val ex = assertThrows(NoSuchElementException::class.java) { numberOn("NOT_A_REAL_CONSTANT", on) }
        assertTrue(ex.message!!.contains("stop and ask"))
    }

    @Test
    fun `PM-FEE-006 the absence number is unconfirmed and comes from configuration`() {
        val c = constantOn("ABSENCE_EXEMPTION_DAYS", on)
        assertFalse(c.verified)
        assertNotNull(c.todoLegal)
        assertTrue(unverified().map { it.code }.contains("ABSENCE_EXEMPTION_DAYS"))
    }

    @Test
    fun `PM-FEE-004 the repair fund is fixed to ideal parts and management defaults per person`() {
        assertEquals(AllocationKey.BY_IDEAL_PARTS, defaultKey(CostStream.REPAIR_FUND))
        assertEquals(AllocationKey.PER_PERSON, defaultKey(CostStream.MANAGEMENT))
        assertFalse(keyIsChangeableByAssembly(CostStream.REPAIR_FUND))
        assertTrue(keyIsChangeableByAssembly(CostStream.MANAGEMENT))
    }
}
