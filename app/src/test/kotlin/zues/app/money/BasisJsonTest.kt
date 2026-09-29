package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import zues.charges.PropertyUnit
import zues.charges.Tariff
import zues.charges.TariffLine
import zues.charges.computeChargeRun
import zues.charges.ConsumptionLine
import zues.kernel.IdealParts
import zues.law.AllocationKey
import zues.law.CostItem
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
    fun `PM-FEE-014 a basis with no named line serializes as it did before lines could be named`() {
        assertThat(BasisJson.of(run(10_000))).doesNotContain("item")   // so a past run's hash is reproduced unchanged
        val named = computeChargeRun(
            "entrance-1", listOf(PropertyUnit("u1", "ап. 1", IdealParts.of("100.0000"), occupants = 1)),
            Tariff("entrance-1", "2026-05", "2026-05-01", listOf(
                TariffLine(CostStream.MAINTENANCE, AllocationKey.PER_PERSON, "GA-2026-1", rateMinor = 300L),
                TariffLine(CostStream.MAINTENANCE, AllocationKey.PER_PERSON, "GA-2026-2", rateMinor = 200L, item = CostItem.CONCIERGE),
            )),
        )
        assertThat(BasisJson.of(named)).contains("CONCIERGE")
    }

    @Test
    fun `PM-FEE-014 an unnamed basis hashes exactly as it did before lines could be named`() {
        // Pinned from the serializer as it stood before items existed: any change to the format fails here.
        assertThat(BasisJson.hash(BasisJson.of(run(10_000)))).isEqualTo("4c0a399738100ad09501766fdaf1b5337834d886b7aa0b01f035717f82a6ac1e")
    }

    @Test
    fun `a different tariff produces a different basis hash`() {
        assertThat(BasisJson.hash(BasisJson.of(run(20_000))))
            .isNotEqualTo(BasisJson.hash(BasisJson.of(run(10_000))))
    }

    @Test
    fun `PM-FEE-017 a metered run carries its readings and prices in its basis`() {
        val metered = computeChargeRun(
            "entrance-1",
            listOf(
                PropertyUnit("u1", "ап. 1", IdealParts.of("60.0000"), occupants = 0, readings = mapOf(CostItem.WATER to 12_345L)),
                PropertyUnit("u2", "ап. 2", IdealParts.of("40.0000"), occupants = 0),
            ),
            Tariff(
                "entrance-1", "2026-05", "2026-05-01",
                listOf(TariffLine(CostStream.MAINTENANCE, AllocationKey.BY_IDEAL_PARTS, "GA-2026-1", totalMinor = 10_000)),
                consumption = listOf(ConsumptionLine(CostItem.WATER, 230, "GA-2026-9")),
            ),
        )
        val json = BasisJson.of(metered)
        assertThat(json).contains("\"consumption\":[{\"decisionId\":\"GA-2026-9\",\"item\":\"WATER\",\"priceMinor\":230}]")
        assertThat(json).contains("\"readingsThousandths\":{\"WATER\":12345}")
        assertThat(json.split("\"readingsThousandths\"")).hasSize(2)                     // only the unit that has readings
        assertThat(BasisJson.hash(json)).isNotEqualTo(BasisJson.hash(BasisJson.of(run(10_000))))
    }
}
