package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * A statement line's derivation in Bulgarian, built at read time from the stored line and its run's stored basis
 * (Rule: PM-FEE-018) — the stored English `derivation` stays as issued (PM-FEE-015). Amounts are worded from integer
 * minor units (Rule: PM-FEE-016).
 */
class StatementDerivationTest {

    private val postings: PostingRepository = mock()
    private val chargeLines: ChargeLineRepository = mock()
    private val chargeRuns: ChargeRunRepository = mock()
    private val service = StatementService(postings, chargeLines, chargeRuns)

    private val unitId = UUID.randomUUID()
    private val runId = UUID.randomUUID()

    private fun basis(tariffLines: String, consumption: String = "", businessUse: Boolean = false, multiplier: String = "null") = """
        {"constants":{"BUSINESS_USE_MULTIPLIER_MIN":2.0,"BUSINESS_USE_MULTIPLIER_MAX":4.0},
         "units":[{"unitId":"$unitId","businessUse":$businessUse,"occupants":3}],
         "tariff":{"businessMultiplier":$multiplier,"lines":[$tariffLines]${if (consumption.isEmpty()) "" else ",\"consumption\":[$consumption]"}}}
    """.trimIndent()

    private fun lineOf(component: String, key: String, quantity: String, amount: Long, item: String? = null) =
        ChargeLineRow(UUID.randomUUID(), UUID.randomUUID(), runId, unitId, component, key, BigDecimal(quantity), amount, "EUR",
            "english as issued", "GA-1", item)

    private fun statementLine(line: ChargeLineRow, basisJson: String): StatementLine {
        whenever(chargeLines.findByUnitId(unitId)).thenReturn(listOf(line))
        whenever(chargeRuns.findAllById(any())).thenReturn(
            listOf(ChargeRunRow(runId, UUID.randomUUID(), "2026-05", LocalDate.of(2026, 5, 1), JsonbValue(basisJson), "h", "1.3", "0.1.0", "ISSUED")),
        )
        return service.forUnit(unitId).lines.single()
    }

    @Test
    fun `PM-FEE-018 a per-person line shows the count and the rate it was billed at`() {
        val tariff = """{"stream":"MANAGEMENT","key":"PER_PERSON","rateMinor":620,"totalMinor":null,"decisionId":"GA-1"}"""
        val three = statementLine(lineOf("MANAGEMENT", "PER_PERSON", "3.000000", 1_860), basis(tariff))
        assertThat(three.rateMinor).isEqualTo(620)
        assertThat(three.derivationBg).isEqualTo("3 души × 6,20 €")
        assertThat(three.derivation).isEqualTo("english as issued")                  // the issued text is untouched

        val one = statementLine(lineOf("MANAGEMENT", "PER_PERSON", "1.000000", 620), basis(tariff))
        assertThat(one.derivationBg).isEqualTo("1 човек × 6,20 €")
    }

    @Test
    fun `PM-FEE-018 a per-unit line shows one unit times the rate`() {
        val tariff = """{"stream":"MAINTENANCE","key":"PER_UNIT","rateMinor":1500,"totalMinor":null,"decisionId":"GA-1"}"""
        val line = statementLine(lineOf("MAINTENANCE", "PER_UNIT", "1.000000", 1_500), basis(tariff))
        assertThat(line.rateMinor).isEqualTo(1_500)
        assertThat(line.derivationBg).isEqualTo("1 обект × 15,00 €")
    }

    @Test
    fun `PM-FEE-018 an ideal-parts line shows the unit's parts of the rate`() {
        val tariff = """{"stream":"REPAIR_FUND","key":"BY_IDEAL_PARTS","rateMinor":100000,"totalMinor":null,"decisionId":"GA-1"}"""
        val line = statementLine(lineOf("REPAIR_FUND", "BY_IDEAL_PARTS", "2.500000", 2_500), basis(tariff, businessUse = true))
        assertThat(line.rateMinor).isEqualTo(100_000)
        assertThat(line.derivationBg).isEqualTo("2,500000 % ид. части от 1 000,00 €")   // no business multiple on the repair fund
    }

    @Test
    fun `PM-FEE-018 a share of a total names the total and the key, with no rate`() {
        val perPerson = """{"stream":"MAINTENANCE","key":"PER_PERSON","rateMinor":null,"totalMinor":120000,"decisionId":"GA-1"}"""
        val share = statementLine(lineOf("MAINTENANCE", "PER_PERSON", "3.000000", 30_000), basis(perPerson))
        assertThat(share.rateMinor).isNull()
        assertThat(share.derivationBg).isEqualTo("дял от 1 200,00 € по брой души · 3 души")

        val byParts = """{"stream":"MANAGEMENT","key":"BY_IDEAL_PARTS","rateMinor":null,"totalMinor":120000,"decisionId":"GA-1"}"""
        assertThat(statementLine(lineOf("MANAGEMENT", "BY_IDEAL_PARTS", "2.500000", 3_000), basis(byParts)).derivationBg)
            .isEqualTo("дял от 1 200,00 € по идеални части · 2,500000 %")

        val perUnit = """{"stream":"MANAGEMENT","key":"PER_UNIT","rateMinor":null,"totalMinor":120000,"decisionId":"GA-1"}"""
        assertThat(statementLine(lineOf("MANAGEMENT", "PER_UNIT", "1.000000", 20_000), basis(perUnit)).derivationBg)
            .isEqualTo("дял от 1 200,00 € по обект · 1 обект")
    }

    @Test
    fun `PM-FEE-018 a metered line shows its reading times the adopted price`() {
        val water = """{"item":"WATER","priceMinor":210,"decisionId":"GA-1"}"""
        val line = statementLine(lineOf("MAINTENANCE", "METERED", "12.345000", 2_592, item = "WATER"), basis("", water, businessUse = true))
        assertThat(line.rateMinor).isEqualTo(210)
        assertThat(line.derivationBg).isEqualTo("12,345 m³ × 2,10 €/m³")              // no business multiple on a metered line
    }

    @Test
    fun `PM-FEE-018 business use shows the multiple it was billed at`() {
        val tariff = """{"stream":"MANAGEMENT","key":"PER_PERSON","rateMinor":620,"totalMinor":null,"decisionId":"GA-1"}"""
        val adopted = statementLine(lineOf("MANAGEMENT", "PER_PERSON", "3.000000", 5_580), basis(tariff, businessUse = true, multiplier = "3"))
        assertThat(adopted.derivationBg).isEqualTo("3 души × 6,20 € · ×3 стопанска дейност")

        // no multiple adopted: the engine billed at the statutory minimum the basis froze
        val minimum = statementLine(lineOf("MANAGEMENT", "PER_PERSON", "3.000000", 3_720), basis(tariff, businessUse = true))
        assertThat(minimum.derivationBg).isEqualTo("3 души × 6,20 € · ×2 стопанска дейност")
    }

    @Test
    fun `PM-FEE-018 an item line is matched to its own tariff line, not the stream's`() {
        val tariff = """{"stream":"MAINTENANCE","key":"PER_PERSON","rateMinor":450,"totalMinor":null,"decisionId":"GA-1"},
                        {"stream":"MAINTENANCE","key":"PER_PERSON","rateMinor":300,"totalMinor":null,"decisionId":"GA-1","item":"CONCIERGE"}"""
        val concierge = statementLine(lineOf("MAINTENANCE", "PER_PERSON", "3.000000", 900, item = "CONCIERGE"), basis(tariff))
        assertThat(concierge.derivationBg).isEqualTo("3 души × 3,00 €")
        assertThat(concierge.item).isEqualTo("CONCIERGE")
    }

    @Test
    fun `PM-FEE-018 a line whose basis has no matching tariff line gets no derivation, never a guess`() {
        val other = """{"stream":"MANAGEMENT","key":"PER_PERSON","rateMinor":620,"totalMinor":null,"decisionId":"GA-1"}"""
        val line = statementLine(lineOf("MAINTENANCE", "PER_PERSON", "3.000000", 1_350), basis(other))
        assertThat(line.rateMinor).isNull()
        assertThat(line.derivationBg).isNull()

        val emptyBasis = statementLine(lineOf("MANAGEMENT", "PER_PERSON", "3.000000", 1_860), "{}")
        assertThat(emptyBasis.derivationBg).isNull()
    }

    @Test
    fun `PM-FEE-016 amounts are formatted from minor units with integer arithmetic`() {
        assertThat(DerivationBg.euro(0)).isEqualTo("0,00 €")
        assertThat(DerivationBg.euro(5)).isEqualTo("0,05 €")
        assertThat(DerivationBg.euro(620)).isEqualTo("6,20 €")
        assertThat(DerivationBg.euro(120_000)).isEqualTo("1 200,00 €")
        assertThat(DerivationBg.euro(123_456_789)).isEqualTo("1 234 567,89 €")
        assertThat(DerivationBg.euro(-1_050)).isEqualTo("-10,50 €")
        assertThat(DerivationBg.euro(Long.MIN_VALUE)).isEqualTo("-92 233 720 368 547 758,08 €")   // no overflow on negation
    }
}
