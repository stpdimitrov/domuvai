package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import zues.charges.PropertyUnit
import zues.charges.Tariff
import zues.charges.TariffLine
import zues.charges.computeChargeRun
import zues.kernel.IdealParts
import zues.law.AllocationKey
import zues.law.CostStream

/**
 * Pure tests of the basis serializer — the reproducibility half of PM-FEE-014: the same run
 * yields byte-identical JSON and the same hash, a different run a different hash. No Spring,
 * no database, so they run in the gate pack everywhere.
 */
class BasisJsonTest {

    private fun run(totalMinor: Long) = computeChargeRun(
        "entrance-1",
        listOf(
            PropertyUnit("u1", "ап. 1", IdealParts.of("60.0000"), occupants = 0),
            PropertyUnit("u2", "ап. 2", IdealParts.of("40.0000"), occupants = 0),
        ),
        Tariff(
            "entrance-1", "2026-05", "2026-05-01",
            listOf(TariffLine(CostStream.MAINTENANCE, AllocationKey.BY_IDEAL_PARTS, "GA-2026-1", totalMinor = totalMinor)),
        ),
    )

    @Test
    fun `PM-FEE-014 the same run produces byte-identical basis JSON and hash`() {
        val first = BasisJson.of(run(10_000))
        val second = BasisJson.of(run(10_000))
        assertThat(second).isEqualTo(first)
        assertThat(BasisJson.hash(second)).isEqualTo(BasisJson.hash(first))
    }

    @Test
    fun `a different tariff produces a different basis hash`() {
        assertThat(BasisJson.hash(BasisJson.of(run(20_000))))
            .isNotEqualTo(BasisJson.hash(BasisJson.of(run(10_000))))
    }
}
