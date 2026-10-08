package zues.charges

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import zues.kernel.IdealParts
import zues.law.AllocationKey
import zues.law.CATALOGUE_VERSION
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
        assertEquals(CATALOGUE_VERSION, run.lawVersion)     // the catalogue's, whatever it is (tools/check_catalogue_version.py)
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
        for (key in AllocationKey.entries - AllocationKey.METERED) {           // the statutory keys; METERED is a consumption marker (PM-FEE-017)
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

    // Rule: PM-FEE-017 — consumption lines: each unit billed for what its own meter read.

    private val water = ConsumptionLine(CostItem.WATER, priceMinor = 100L, decisionId = "GA-2026-9")        // 1.00 € per m³

    private fun metered(consumption: List<ConsumptionLine> = listOf(water), businessMultiplier: Int? = null) =
        Tariff("e1", "2026-09", on, defaultLines(), businessMultiplier, consumption)

    @Test
    fun `PM-FEE-017 a consumption line bills each unit for its own reading, half-up to the cent, and changes no other line`() {
        val units = listOf(
            unit("A", "40", occupants = 1).copy(readings = mapOf(CostItem.WATER to 12_345L)),      // 12.345 m³ → 12.345 € → 12.35
            unit("B", "60", occupants = 3).copy(readings = mapOf(CostItem.WATER to 5L)),           // 0.005 m³ → half a cent → 0.01
        )
        val run = computeChargeRun("e1", units, metered())
        val a = run.charges[0].lines.single { it.item == CostItem.WATER }
        assertEquals(1_235L, a.amount.amountMinor)
        assertEquals(1L, run.charges[1].lines.single { it.item == CostItem.WATER }.amount.amountMinor)
        assertEquals(CostStream.MAINTENANCE, a.stream)                                          // never a fourth stream (PM-FEE-001)
        assertEquals(AllocationKey.METERED, a.key)                                              // not a statutory key
        assertEquals("GA-2026-9", a.decisionId)
        assertEquals("water · 12.345 m³ × 1.00 €/m³ (metered)", a.derivation)
        val without = computeChargeRun("e1", units.map { it.copy(readings = emptyMap()) }, tariff())
        for (i in units.indices) {                                                              // the keyed lines are untouched
            assertEquals(without.charges[i].lines, run.charges[i].lines.filter { it.item != CostItem.WATER })
        }
        assertEquals(without.total.amountMinor + 1_236L, run.total.amountMinor)
        assertTrue(run.missingReadings.isEmpty())
    }

    @Test
    fun `PM-FEE-017 an unread meter is billed nothing and listed, never estimated`() {
        val heating = ConsumptionLine(CostItem.HEATING, priceMinor = 25L, decisionId = "GA-2026-9")
        val units = listOf(
            unit("A", "40").copy(readings = mapOf(CostItem.WATER to 1_000L, CostItem.HEATING to 2_000L)),
            unit("B", "60").copy(readings = mapOf(CostItem.WATER to 3_000L)),
        )
        val run = computeChargeRun("e1", units, metered(listOf(water, heating)))
        assertEquals(listOf(MissingReading("B", CostItem.HEATING)), run.missingReadings)
        assertTrue(run.charges[1].lines.none { it.item == CostItem.HEATING })
        val heat = run.charges[0].lines.single { it.item == CostItem.HEATING }
        assertEquals(50L, heat.amount.amountMinor)                                              // 2.000 kWh × 0.25 €
        assertEquals(CostStream.MAINTENANCE, heat.stream)                                      // HEATING too is a maintenance line
        assertEquals("heating · 2.000 kWh × 0.25 €/kWh (metered)", heat.derivation)
    }

    @Test
    fun `PM-FEE-017 PM-FEE-010 a business unit pays what its meter read, not a multiple of it`() {
        val multiplier = numberOn("BUSINESS_USE_MULTIPLIER_MIN", on).toInt()
        val shop = unit("S", "100", occupants = 1, businessUse = true).copy(readings = mapOf(CostItem.WATER to 2_000L))
        val run = computeChargeRun("e1", listOf(shop), metered(businessMultiplier = multiplier))
        assertEquals(200L, run.charges[0].lines.single { it.item == CostItem.WATER }.amount.amountMinor)     // 2.000 m³ × 1.00 €
        assertEquals(300L * multiplier, run.charges[0].lines.single { it.stream == CostStream.MAINTENANCE && it.item == null }.amount.amountMinor)
    }

    @Test
    fun `PM-FEE-017 a consumption line needs its GA decision, a metered item and one price, and no statutory line is metered`() {
        val a = listOf(unit("A", "100").copy(readings = mapOf(CostItem.WATER to 1_000L)))
        fun refused(tariff: Tariff, units: List<PropertyUnit> = a, saying: String = "") {
            val e = assertThrows(IllegalStateException::class.java) { computeChargeRun("e1", units, tariff) }
            assertTrue(e.message!!.contains(saying), e.message)
        }
        refused(metered(listOf(water.copy(decisionId = " "))))                                  // no decision (PM-FEE-012)
        refused(metered(listOf(water.copy(item = CostItem.CONCIERGE))))                          // not a metered cost
        refused(metered(listOf(water.copy(priceMinor = 0))))
        refused(metered(listOf(water, water.copy(priceMinor = 120L))))                           // priced twice
        val notByKey = "in the tariff's consumption lines — not by a key"                       // refused up front, before any allocation
        refused(tariff(defaultLines() + TariffLine(CostStream.MAINTENANCE, AllocationKey.METERED, "d1", rateMinor = 100L)), saying = notByKey)
        refused(tariff(defaultLines() + TariffLine(CostStream.MAINTENANCE, AllocationKey.PER_PERSON, "d1", rateMinor = 100L, item = CostItem.WATER)), saying = notByKey)
        refused(metered(), listOf(unit("A", "100").copy(readings = mapOf(CostItem.WATER to -1L))))
        refused(metered(), listOf(unit("A", "100").copy(readings = mapOf(CostItem.CONCIERGE to 1_000L))))
        refused(tariff(), a, saying = "prices no WATER")                                        // a reading nothing prices is not dropped
        val huge = listOf(unit("A", "100").copy(readings = mapOf(CostItem.WATER to 999_999_999L)))
        refused(metered(listOf(water.copy(priceMinor = Long.MAX_VALUE / 1_000))), huge, saying = "out of range")   // never a wrapped, negative bill
    }

    @Test
    fun `PM-FEE-017 a zero reading is a reading — a line of nothing owed, not a missing meter`() {
        val units = listOf(
            unit("A", "40").copy(readings = mapOf(CostItem.WATER to 0L)),
            unit("B", "60").copy(readings = mapOf(CostItem.WATER to 5L)),
        )
        val run = computeChargeRun("e1", units, metered())
        assertEquals(0L, run.charges[0].lines.single { it.item == CostItem.WATER }.amount.amountMinor)
        assertTrue(run.missingReadings.isEmpty())
        assertEquals("water · 0.005 m³ × 1.00 €/m³ (metered)", run.charges[1].lines.single { it.item == CostItem.WATER }.derivation)
    }
}
