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
    fun `mapped optional columns are read as written, and a blank cell is absent`() {
        val parsed = FeeSheet.parse(
            "designation,ideal_parts,occupants,fee_minor,area,owner\nап. 1,60.0000,2,6000,72.50,Иван Петров\nап. 2,40.0000,1,4000,,",
        )
        assertThat(parsed.violations).isEmpty()
        assertThat(parsed.rows[0].optional).containsExactlyInAnyOrderEntriesOf(
            mapOf(IntakeField.BUILT_AREA to "72.50", IntakeField.OWNER_NAME to "Иван Петров"),
        )
        assertThat(parsed.rows[1].optional).isEmpty()
    }

    @Test
    fun `an area the registry cannot hold or a child count that is not whole makes its row a violation`() {
        val parsed = FeeSheet.parse(
            "designation,ideal_parts,occupants,fee_minor,area,children\n" +
                "ап. 1,60.0000,2,6000,72.505,0\n" +     // three decimals: area_m2 holds two — never rounded silently
                "ап. 2,40.0000,1,4000,50,1.5\n" +       // half a child
                "ап. 3,10.0000,1,1000,-3,0",             // not a positive area
        )
        assertThat(parsed.rows).isEmpty()
        assertThat(parsed.violations).hasSize(3)
    }

    @Test
    fun `PM-BOOK-011 an owner cell carrying an identity number makes its row a violation`() {
        val parsed = FeeSheet.parse(
            "designation,ideal_parts,occupants,fee_minor,owner\n" +
                "ап. 1,60.0000,2,6000,Иван Петров 7501010010\n" +   // an ЕГН written into the name
                "ап. 2,40.0000,1,4000,„Строй 2000“ ЕООД",            // digits in a company name are fine
        )
        assertThat(parsed.rows.map { it.designation }).containsExactly("ап. 2")
        assertThat(parsed.violations).singleElement().asString().contains("row 2")
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

    @Test
    fun `a differently-shaped sheet parses via the profiler (ADR-012)`() {
        // reordered, Bulgarian headers — the same parse, no fixed layout
        val csv = "Обект,Живущи,Идеални части,Сума\nап. 1,2,60.0000,6000"
        val parsed = FeeSheet.parse(csv)
        assertThat(parsed.violations).isEmpty()
        assertThat(parsed.rows).hasSize(1)
        assertThat(parsed.rows[0].designation).isEqualTo("ап. 1")
        assertThat(parsed.rows[0].idealParts).isEqualTo("60.0000")
        assertThat(parsed.rows[0].occupants).isEqualTo(2)
        assertThat(parsed.rows[0].theirFeeMinor).isEqualTo(6000)
    }

    @Test
    fun `a confirmed mapping parses headers the profiler cannot recognise`() {
        val csv = "col_a,col_b,col_c,col_d\nап. 1,60.0000,2,6000"
        val mapping = mapOf(
            "col_a" to IntakeField.DESIGNATION, "col_b" to IntakeField.IDEAL_PARTS,
            "col_c" to IntakeField.OCCUPANTS, "col_d" to IntakeField.FEE_MINOR,
        )
        val parsed = FeeSheet.parse(csv, mapping)
        assertThat(parsed.violations).isEmpty()
        assertThat(parsed.rows[0].designation).isEqualTo("ап. 1")
        assertThat(parsed.rows[0].theirFeeMinor).isEqualTo(6000)
    }

    @Test
    fun `PM-FEE-010 a business-use cell is a yes or a no, in either language — a blank is a no, anything else makes its row a violation`() {
        val parsed = FeeSheet.parse(
            "designation,ideal_parts,occupants,fee_minor,business\n" +
                "об. 1,10.0000,1,0,Да\nоб. 2,10.0000,1,0,yes\nоб. 3,10.0000,1,0,true\nоб. 4,10.0000,1,0,1\n" +
                "об. 5,10.0000,1,0,не\nоб. 6,10.0000,1,0,No\nоб. 7,10.0000,1,0,false\nоб. 8,10.0000,1,0,0\nоб. 9,10.0000,1,0,\n" +
                "об. 10,10.0000,1,0,магазин",                  // what the business is, not whether there is one
        )
        assertThat(parsed.rows.map { it.businessUse }).containsExactly(true, true, true, true, false, false, false, false, false)
        assertThat(parsed.violations.single()).contains("row 11").contains("магазин")
    }
}
