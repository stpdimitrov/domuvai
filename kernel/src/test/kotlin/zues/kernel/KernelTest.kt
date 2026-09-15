package zues.kernel

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KernelTest {

    @Test
    fun `PM-FEE-016 money is integer minor units, and adds exactly`() {
        assertEquals(4250L, eur(4250).amountMinor)
        assertEquals(800L, (eur(500) + eur(300)).amountMinor)
        assertEquals("42.50 €", eur(4250).format())
        // eur(42.5) does not compile — the Long type forbids a fractional cent.
    }

    @Test
    fun `PM-ORG-002 ideal parts are exact decimals, never floats`() {
        assertEquals(12_345_600, IdealParts.of("12.345600").ppmPct)
        assertEquals("0.000001", IdealParts.of("0.000001").format())
        assertThrows(IllegalArgumentException::class.java) { IdealParts.of("12.3456001") }
        // 0.1 + 0.2 is exactly 0.3 in integer millionths, where floats would drift.
        assertEquals(
            IdealParts.of("0.3").ppmPct,
            IdealParts.of("0.1").ppmPct + IdealParts.of("0.2").ppmPct,
        )
    }

    @Test
    fun `PM-ORG-002 the sum per entrance must equal exactly 100 percent`() {
        assertDoesNotThrow {
            assertPartsSumTo100(listOf(IdealParts.of("50"), IdealParts.of("50")))
        }
        val ex = assertThrows(IllegalStateException::class.java) {
            assertPartsSumTo100(listOf(IdealParts.of("50"), IdealParts.of("49.999999")))
        }
        assertTrue(ex.message!!.contains("must be 100.000000%"))
    }

    @Test
    fun `PM-FEE-004 allocateByWeight never invents or loses a minor unit`() {
        val totals = listOf(1L, 7L, 99L, 100_000L, 10_001L)
        val weightings = listOf(
            listOf(1L, 1L, 1L), listOf(1L, 2L, 3L), listOf(0L, 0L, 1L), listOf(5L, 5L),
        )
        for (total in totals) {
            for (ws in weightings) {
                val parts = allocateByWeight(eur(total), ws)
                assertEquals(total, parts.sumOf { it.amountMinor })
            }
        }
    }
}
