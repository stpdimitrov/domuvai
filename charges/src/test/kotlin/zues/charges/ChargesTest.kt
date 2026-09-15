package zues.charges

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import zues.kernel.IdealParts
import zues.law.AllocationKey
import zues.law.CostStream
import zues.law.defaultKey
import zues.law.numberOn

class ChargesTest {

    private val on = "2026-09-13"

    private fun unit(
        designation: String,
        idealParts: String,
        occupants: Int = 2,
        childrenUnder6: Int = 0,
        animals: Int = 0,
        absentDays: Int = 0,
        businessUse: Boolean = false,
    ) = PropertyUnit(designation, designation, IdealParts.of(idealParts), occupants, childrenUnder6, animals, absentDays, businessUse)

    private fun defaultLines() = listOf(
        TariffLine(CostStream.MANAGEMENT, AllocationKey.PER_PERSON, "d1", rateMinor = 500L),
        TariffLine(CostStream.MAINTENANCE, AllocationKey.PER_PERSON, "d1", rateMinor = 300L),
        TariffLine(CostStream.REPAIR_FUND, AllocationKey.BY_IDEAL_PARTS, "d2", totalMinor = 10_000L),
    )

    private fun tariff(lines: List<TariffLine> = defaultLines(), businessMultiplier: Int? = null) =
        Tariff("e1", "2026-09", on, lines, businessMultiplier)

    @Test
    fun `PM-FEE-002 management and maintenance are allocated per person by default`() {
        assertEquals(AllocationKey.PER_PERSON, defaultKey(CostStream.MANAGEMENT))
        val run = computeChargeRun("e1", listOf(unit("A", "50", occupants = 1), unit("B", "50", occupants = 3)), tariff())
        // 8.00 € per person: A pays 8.00, B pays 24.00 — floor area is irrelevant
        assertEquals(800L, run.charges[0].lines[0].amount.amountMinor + run.charges[0].lines[1].amount.amountMinor)
        assertEquals(2400L, run.charges[1].lines[0].amount.amountMinor + run.charges[1].lines[1].amount.amountMinor)
    }

    @Test
    fun `PM-FEE-004 PM-FUND-003 the repair fund is by ideal parts and the assembly cannot move it`() {
        assertEquals(AllocationKey.BY_IDEAL_PARTS, defaultKey(CostStream.REPAIR_FUND))
        val bad = tariff(lines = listOf(TariffLine(CostStream.REPAIR_FUND, AllocationKey.PER_PERSON, "d2", totalMinor = 100L)))
        val ex = assertThrows(IllegalStateException::class.java) { computeChargeRun("e1", listOf(unit("A", "100")), bad) }
        assertTrue(ex.message!!.contains("must be allocated BY_IDEAL_PARTS"))
    }

    @Test
    fun `PM-FEE-010 a business unit pays a multiple, and only inside the statutory range`() {
        val units = listOf(unit("A", "50"), unit("B", "50", businessUse = true))
        val run = computeChargeRun("e1", units, tariff(businessMultiplier = 3))
        fun mgmt(i: Int) = run.charges[i].lines[0].amount.amountMinor
        assertEquals(mgmt(0) * 3, mgmt(1))
        assertThrows(IllegalArgumentException::class.java) { computeChargeRun("e1", units, tariff(businessMultiplier = 9)) }
    }

    @Test
    fun `PM-FEE-010 the business multiplier does not touch the repair fund`() {
        val units = listOf(unit("A", "50"), unit("B", "50", businessUse = true))
        val run = computeChargeRun("e1", units, tariff(businessMultiplier = 5))
        assertEquals(run.charges[0].lines[2].amount.amountMinor, run.charges[1].lines[2].amount.amountMinor)
    }

    @Test
    fun `PM-FEE-012 a tariff line with no assembly decision cannot be billed`() {
        val bad = tariff(lines = listOf(TariffLine(CostStream.MANAGEMENT, AllocationKey.PER_PERSON, "", rateMinor = 500L)))
        val ex = assertThrows(IllegalStateException::class.java) { computeChargeRun("e1", listOf(unit("A", "100")), bad) }
        assertTrue(ex.message!!.contains("no GA decision"))
    }

    @Test
    fun `PM-FEE-014 an allocated pot sums to the pot exactly, no cent invented or lost`() {
        val units = listOf("33.333333", "33.333333", "33.333334").mapIndexed { i, p -> unit("U$i", p) }
        val run = computeChargeRun(
            "e1", units,
            tariff(lines = listOf(TariffLine(CostStream.REPAIR_FUND, AllocationKey.BY_IDEAL_PARTS, "d2", totalMinor = 10_001L))),
        )
        assertEquals(10_001L, run.total.amountMinor)
    }

    @Test
    fun `PM-FEE-014 the run carries the basis, law version and engine version`() {
        val run = computeChargeRun("e1", listOf(unit("A", "100")), tariff())
        assertEquals("1.3", run.lawVersion)
        assertTrue(run.engineVersion.isNotBlank())
        assertEquals(on, run.basis.legalDate)
        assertEquals(30.0, run.basis.constants["ABSENCE_EXEMPTION_DAYS"]!!)
    }

    @Test
    fun `PM-FEE-014 the same basis recomputes to the same figures`() {
        val units = listOf(unit("A", "40", occupants = 2), unit("B", "60", occupants = 3, animals = 1))
        assertEquals(computeChargeRun("e1", units, tariff()).charges, computeChargeRun("e1", units, tariff()).charges)
    }

    @Test
    fun `PM-FEE-018 every line states how the number was derived`() {
        val run = computeChargeRun("e1", listOf(unit("A", "100", occupants = 2)), tariff())
        run.charges[0].lines.forEach { assertTrue(it.derivation.length > 5) }
        assertTrue(run.charges[0].lines[0].derivation.contains("2 person(s)"))
    }

    @Test
    fun `PM-FEE-005 children under six are not counted`() {
        assertEquals(2, chargeablePersons(unit("1", "100", occupants = 4, childrenUnder6 = 2), on))
    }

    @Test
    fun `PM-FEE-009 each animal adds one occupant equivalent`() {
        assertEquals(5, chargeablePersons(unit("1", "100", occupants = 2, animals = 3), on))
    }

    @Test
    fun `PM-FEE-006 absence beyond the statutory window exempts the unit`() {
        val days = numberOn("ABSENCE_EXEMPTION_DAYS", on).toInt()
        assertEquals(2, chargeablePersons(unit("1", "100", occupants = 2, absentDays = days), on))
        assertEquals(0, chargeablePersons(unit("1", "100", occupants = 2, absentDays = days + 1), on))
    }
}
