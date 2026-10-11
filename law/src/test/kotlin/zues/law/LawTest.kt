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
    fun `PM-FEE-007 the absence-declaration grace window is unconfirmed and comes from configuration`() {
        val c = constantOn("ABSENCE_DECLARATION_GRACE_DAYS", on)
        assertFalse(c.verified)
        assertNotNull(c.todoLegal)
        assertTrue(unverified().map { it.code }.contains("ABSENCE_DECLARATION_GRACE_DAYS"))
    }

    @Test
    fun `PM-DEBT-002 the default payment term is fourteen days and confirmed`() {
        val c = constantOn("PAYMENT_TERM_DAYS", on)
        assertTrue(c.verified)
        assertEquals(14.0, numberOn("PAYMENT_TERM_DAYS", on))
    }

    @Test
    fun `PM-FEE-004 the repair fund is fixed to ideal parts and management defaults per person`() {
        assertEquals(AllocationKey.BY_IDEAL_PARTS, defaultKey(CostStream.REPAIR_FUND))
        assertEquals(AllocationKey.PER_PERSON, defaultKey(CostStream.MANAGEMENT))
        assertFalse(keyIsChangeableByAssembly(CostStream.REPAIR_FUND))
        assertTrue(keyIsChangeableByAssembly(CostStream.MANAGEMENT))
    }

    @Test
    fun `PM-GOV-004 the board's and the manager's mandate is at most 2 years, a confirmed figure, in force since the statute`() {
        assertEquals("2", constantOn("MANDATE_TERM_YEARS", "2009-01-01").value)
        assertEquals("2", constantOn("MANDATE_TERM_YEARS", "2026-10-11").value)
        assertEquals("PM-GOV-004", constantOn("MANDATE_TERM_YEARS", "2026-10-11").rule)
        assertTrue(constantOn("MANDATE_TERM_YEARS", "2026-10-11").verified)
        assertTrue(runCatching { constantOn("MANDATE_TERM_YEARS", "2008-12-31") }.isFailure)   // no term before the statute: stop and ask
    }

    @Test
    fun `PM-GOV-007 the controller's term is 2 years, a confirmed figure — and the cashier's is not confirmed`() {
        assertEquals("2", constantOn("CONTROL_TERM_YEARS", "2026-10-11").value)
        assertEquals("PM-GOV-007", constantOn("CONTROL_TERM_YEARS", "2026-10-11").rule)
        assertTrue(constantOn("CONTROL_TERM_YEARS", "2026-10-11").verified)
        assertTrue(unverified().map { it.code }.contains("CASHIER_TERM_YEARS"))
        assertTrue(constantOn("CASHIER_TERM_YEARS", "2026-10-11").rule == null)
    }

    @Test
    fun `PM-GOV-004 the election reminder is 60 days before a term ends — the catalogue's own figure, not the statute's, and not confirmed`() {
        val c = constantOn("MANDATE_ELECTION_REMINDER_DAYS", "2026-10-11")
        assertEquals("60", c.value)
        assertEquals("PM-GOV-004", c.rule)
        assertFalse(c.verified)
        assertNotNull(c.todoLegal)
        assertTrue(unverified().map { it.code }.contains("MANDATE_ELECTION_REMINDER_DAYS"))
    }
}
