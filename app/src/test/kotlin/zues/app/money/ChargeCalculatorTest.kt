package zues.app.money

import org.assertj.core.api.Assertions.assertThat
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
    fun `PM-FEE-014 re-running the same period reproduces identical figures`() {
        val first = ChargeCalculator.run(request())
        val second = ChargeCalculator.run(request())
        assertThat(second).isEqualTo(first)
    }
}
