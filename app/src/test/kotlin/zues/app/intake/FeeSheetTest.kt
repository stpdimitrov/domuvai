package zues.app.intake

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FeeSheetTest {

    @Test
    fun `a clean sheet parses to rows`() {
        val csv = """
            designation,ideal_parts,occupants,fee_minor
            ап. 1,60.0000,2,6000
            ап. 2,40.0000,1,4000
        """.trimIndent()
        val parsed = FeeSheet.parse(csv)
        assertThat(parsed.violations).isEmpty()
        assertThat(parsed.rows).hasSize(2)
        assertThat(parsed.rows[0].designation).isEqualTo("ап. 1")
        assertThat(parsed.rows[0].theirFeeMinor).isEqualTo(6000)
    }

    @Test
    fun `a missing column is a whole-sheet violation`() {
        val parsed = FeeSheet.parse("designation,occupants,fee_minor\nап. 1,2,6000")
        assertThat(parsed.rows).isEmpty()
        assertThat(parsed.violations).anyMatch { it.contains("ideal_parts") }
    }

    @Test
    fun `a malformed row is a per-row violation, the others still parse`() {
        val csv = "designation,ideal_parts,occupants,fee_minor\nап. 1,60.0000,two,6000\nап. 2,40.0000,1,4000"
        val parsed = FeeSheet.parse(csv)
        assertThat(parsed.rows).hasSize(1)
        assertThat(parsed.rows[0].designation).isEqualTo("ап. 2")
        assertThat(parsed.violations).hasSize(1)
    }

    @Test
    fun `an empty sheet is a violation`() {
        assertThat(FeeSheet.parse("   ").violations).isNotEmpty()
    }
}
