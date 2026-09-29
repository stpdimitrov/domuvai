package zues.charges

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import zues.kernel.IdealParts
import zues.law.AllocationKey
import zues.law.CostItem
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
    fun `PM-FEE-011 a concierge line uses maintenance's key and inherits its exemptions and multiplier`() {
        val units = listOf(
            unit("A", "40", occupants = 3, childrenUnder6 = 1),              // two chargeable persons
            unit("B", "30", occupants = 2, absentDays = 40),                 // absent past the window: none
            unit("C", "30", occupants = 1, businessUse = true),              // business use: a multiple
        )
        val lines = listOf(
            TariffLine(CostStream.MAINTENANCE, AllocationKey.PER_PERSON, "d1", rateMinor = 300L),
            TariffLine(CostStream.MAINTENANCE, AllocationKey.PER_PERSON, "d3", rateMinor = 200L, item = CostItem.CONCIERGE),
        )
        val run = computeChargeRun("e1", units, tariff(lines, businessMultiplier = 3))
        run.charges.forEach { c ->
            val (concierge, maintenance) = c.lines.partition { it.item == CostItem.CONCIERGE }
            assertEquals(maintenance.single().amount.amountMinor / 300, concierge.single().amount.amountMinor / 200)  // the same weight
            assertEquals(CostStream.MAINTENANCE, concierge.single().stream)    // a maintenance line: still three streams (PM-FEE-001)
        }
        assertEquals(listOf(400L, 0L, 600L), run.charges.map { c -> c.lines.single { it.item == CostItem.CONCIERGE }.amount.amountMinor })
        assertTrue(run.charges.all { c -> c.lines.single { it.item == CostItem.CONCIERGE }.derivation.startsWith("concierge · ") })
        val alone = listOf(TariffLine(CostStream.MAINTENANCE, AllocationKey.PER_PERSON, "d3", rateMinor = 200L, item = CostItem.CONCIERGE))
        assertEquals(400L, computeChargeRun("e1", units, tariff(alone, businessMultiplier = 3)).charges[0].total.amountMinor)  // on maintenance's default key
    }

    @Test
    fun `PM-FEE-011 a concierge line on another key than maintenance's, or in another stream, is rejected`() {
        val units = listOf(unit("A", "100"))
        fun concierge(stream: CostStream, key: AllocationKey) = TariffLine(stream, key, "d3", rateMinor = 200L, item = CostItem.CONCIERGE)
        val otherKey = listOf(TariffLine(CostStream.MAINTENANCE, AllocationKey.BY_IDEAL_PARTS, "d1", totalMinor = 10_000L),
            concierge(CostStream.MAINTENANCE, AllocationKey.PER_PERSON))
        assertTrue(assertThrows(IllegalStateException::class.java) { computeChargeRun("e1", units, tariff(otherKey)) }.message!!.contains("PM-FEE-011"))
        assertThrows(IllegalStateException::class.java) {                     // no maintenance line: its default key, per person
            computeChargeRun("e1", units, tariff(listOf(concierge(CostStream.MAINTENANCE, AllocationKey.PER_UNIT))))
        }
        assertThrows(IllegalStateException::class.java) {                     // typed as management
            computeChargeRun("e1", units, tariff(listOf(concierge(CostStream.MANAGEMENT, AllocationKey.PER_PERSON))))
        }
        assertThrows(IllegalStateException::class.java) {                     // named twice
            computeChargeRun("e1", units, tariff(List(2) { concierge(CostStream.MAINTENANCE, AllocationKey.PER_PERSON) }))
        }
        val splitMaintenance = listOf(                                         // maintenance on two keys: nothing to follow
            TariffLine(CostStream.MAINTENANCE, AllocationKey.PER_PERSON, "d1", rateMinor = 300L),
            TariffLine(CostStream.MAINTENANCE, AllocationKey.PER_UNIT, "d2", rateMinor = 100L),
        )
        for (lines in listOf(splitMaintenance, splitMaintenance.reversed())) {
            assertThrows(IllegalStateException::class.java) {
                computeChargeRun("e1", units, tariff(lines + concierge(CostStream.MAINTENANCE, AllocationKey.PER_PERSON)))
            }
        }
    }

    @Test
    fun `PM-FEE-003 the assembly may choose any of the three keys, and every line names the decision that chose it`() {
        val units = listOf(unit("A", "40", occupants = 1), unit("B", "60", occupants = 3))
        for (key in AllocationKey.entries) {
            val lines = listOf(
                TariffLine(CostStream.MANAGEMENT, key, "GA-2026-03-12-4", totalMinor = 10_000L),
                TariffLine(CostStream.MAINTENANCE, key, "GA-2026-03-12-5", totalMinor = 6_000L),
            )
            computeChargeRun("e1", units, tariff(lines)).charges.flatMap { it.lines }.forEach { line ->
                assertEquals(key, line.key)
                assertEquals(if (line.stream == CostStream.MANAGEMENT) "GA-2026-03-12-4" else "GA-2026-03-12-5", line.decisionId)
            }
        }
    }

    @Test
    fun `PM-FEE-003 changing the key without a linked protocol is rejected`() {
        val unlinked = listOf(TariffLine(CostStream.MAINTENANCE, AllocationKey.PER_UNIT, " ", rateMinor = 300L))
        assertThrows(IllegalStateException::class.java) { computeChargeRun("e1", listOf(unit("A", "100")), tariff(unlinked)) }
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
