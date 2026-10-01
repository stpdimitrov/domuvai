package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Ideal parts derived from the built-up area ratio (PM-ORG-003), pure — no database. */
class IdealPartsDerivationTest {

    private fun derive(vararg areas: String) = IdealPartsDerivation.byArea(areas.map(::BigDecimal)).map { it.format() }

    @Test
    fun `PM-ORG-003 each unit's share is its area over the entrance's total`() {
        assertThat(derive("60.00", "40.00")).containsExactly("60.000000", "40.000000")
        assertThat(derive("75.50", "50.25", "25.25")).containsExactly("50.000000", "33.278100", "16.721900")
    }

    @Test
    fun `PM-ORG-003 derived shares sum to exactly 100 percent, the last steps by largest remainder`() {
        // three equal areas: 33.3333% each leaves one 0.0001% step, which goes to the first unit
        assertThat(derive("70.00", "70.00", "70.00")).containsExactly("33.333400", "33.333300", "33.333300")
        val parts = IdealPartsDerivation.byArea(listOf("61.37", "48.12", "93.08", "55.55", "71.01", "12.34", "88.88").map(::BigDecimal))
        assertThat(parts.sumOf { it.ppmPct }).isEqualTo(100_000_000)
        assertThat(parts).allMatch { it.ppmPct % 100 == 0 }   // the schema's 4 decimals
    }

    @Test
    fun `an area that is not positive, or finer than the schema, cannot be derived from`() {
        assertThatThrownBy { derive("60.00", "0.00") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { derive("60.00", "40.001") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { derive("60.00", "100000000.00") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
