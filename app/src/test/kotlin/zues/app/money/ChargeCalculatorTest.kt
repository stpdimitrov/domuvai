package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pure tests of the calculator — no Spring, no database, so they run in the gate pack
 * everywhere. They prove the two Gate-1 properties the money edge is responsible for:
 * lines are typed (PM-FEE-001) and the computation is reproducible (PM-FEE-014).
 */
class ChargeCalculatorTest {

    private fun request() = ChargeRunRequest(
        entranceId = "entrance-1",
        period = "2026-05",
        legalDate = "2026-05-01",
        lines = listOf(
            TariffLineRequest("MANAGEMENT", "PER_PERSON", decisionId = "GA-2026-1", rateMinor = 500),
            TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", decisionId = "GA-2026-1", totalMinor = 10_000),
            TariffLineRequest("REPAIR_FUND", "BY_IDEAL_PARTS", decisionId = "GA-2026-1", totalMinor = 20_000),
        ),
        units = listOf(
            UnitRequest("u1", "ап. 1", idealParts = "60.0000", occupants = 2),
            UnitRequest("u2", "ап. 2", idealParts = "40.0000", occupants = 1),
        ),
    )

    @Test
    fun `PM-FEE-001 every charge line is typed to one of the three cost streams`() {
        val run = ChargeCalculator.run(request())
        val lines = run.charges.flatMap { it.lines }
        assertThat(lines).isNotEmpty
        assertThat(lines).allMatch { it.stream in setOf("MANAGEMENT", "MAINTENANCE", "REPAIR_FUND") }
    }

    @Test
    fun `PM-FEE-001 a concierge line is a maintenance line, so there are still three streams`() {
        val withConcierge = request().copy(
            lines = request().lines + TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", decisionId = "GA-2026-2", totalMinor = 3_000, item = "CONCIERGE"),
        )
        val lines = ChargeCalculator.run(withConcierge).charges.flatMap { it.lines }
        assertThat(lines).allMatch { it.stream in setOf("MANAGEMENT", "MAINTENANCE", "REPAIR_FUND") }
        assertThat(lines.filter { it.item == "CONCIERGE" }).hasSize(2)            // one per unit — the item was not dropped
            .allMatch { it.stream == "MAINTENANCE" && it.decisionId == "GA-2026-2" }
    }

    @Test
    fun `PM-FEE-014 re-running the same period reproduces identical figures`() {
        val first = ChargeCalculator.run(request())
        val second = ChargeCalculator.run(request())
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `PM-FEE-017 readings reach the engine exactly, and an unread meter comes back listed`() {
        val metered = request().copy(
            consumption = listOf(ConsumptionLineRequest("WATER", priceMinor = 230, decisionId = "GA-2026-9")),
            readings = listOf(ReadingRequest("u1", "WATER", "12.345")),
        )
        val run = ChargeCalculator.run(metered)
        val water = run.charges.single { it.unitId == "u1" }.lines.single { it.item == "WATER" }
        assertThat(water.amountMinor).isEqualTo(2_839)                            // 12.345 m³ × 2.30 € = 28.3935 → 28.39
        assertThat(listOf(water.stream, water.key, water.decisionId)).containsExactly("MAINTENANCE", "METERED", "GA-2026-9")
        assertThat(run.missingReadings).containsExactly(MissingReadingResponse("u2", "WATER"))
    }

    @Test
    fun `PM-FEE-017 a reading has at most three decimals, is not negative, and names a unit of the run once`() {
        val base = request().copy(consumption = listOf(ConsumptionLineRequest("WATER", 230, "GA-2026-9")))
        for (readings in listOf(
            listOf(ReadingRequest("u1", "WATER", "12.3456")),
            listOf(ReadingRequest("u1", "WATER", "-1")),
            listOf(ReadingRequest("u1", "WATER", "twelve")),
            listOf(ReadingRequest("u1", "WATER", "1E+400")),                            // no exponent: nothing huge reaches the arithmetic
            listOf(ReadingRequest("u1", "WATER", "1234567")),                           // more than a stored quantity holds
            listOf(ReadingRequest("u1", "WATER", "")),
            listOf(ReadingRequest("u1", "GAS", "1")),
            listOf(ReadingRequest("u1", "CONCIERGE", "1")),                             // not a metered item
            listOf(ReadingRequest("u9", "WATER", "1")),
            listOf(ReadingRequest("u1", "WATER", "1"), ReadingRequest("u1", "WATER", "2")),
        )) {
            assertThatThrownBy { ChargeCalculator.run(base.copy(readings = readings)) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { ChargeCalculator.run(base.copy(consumption = listOf(ConsumptionLineRequest("GAS", 230, "GA-2026-9")))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageNotContaining("zues.")      // named in words, not by class
        assertThat(ChargeCalculator.run(base.copy(readings = listOf(ReadingRequest("u1", "WATER", "999999.999")))).missingReadings).hasSize(1)
        val whole = ChargeCalculator.run(base.copy(readings = listOf(ReadingRequest("u1", "WATER", "7"))))
        assertThat(whole.charges.single { it.unitId == "u1" }.lines.single { it.item == "WATER" }.amountMinor).isEqualTo(1_610)   // 7 m³ × 2.30 €
    }
}
