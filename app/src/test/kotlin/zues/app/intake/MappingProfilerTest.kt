package zues.app.intake

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The profiler proved without a file: it maps **different** sheet layouts onto the same domain fields
 * (ADR-012 §7 — the adversarial corpus stands in for a real sheet), surfaces columns it cannot place,
 * and names a required field that no column carries. Pure — runs in the local gate pack.
 */
class MappingProfilerTest {

    @Test
    fun `the fixed four-column layout maps cleanly`() {
        val p = MappingProfiler.profile(listOf("designation", "ideal_parts", "occupants", "fee_minor"))
        assertThat(p.mapping.values).containsExactlyInAnyOrder(
            IntakeField.DESIGNATION, IntakeField.IDEAL_PARTS, IntakeField.OCCUPANTS, IntakeField.FEE_MINOR,
        )
        assertThat(p.missingRequired).isEmpty()
        assertThat(p.unmappedColumns).isEmpty()
    }

    @Test
    fun `a different Bulgarian layout maps by alias, order-independent, extras surfaced`() {
        val p = MappingProfiler.profile(listOf("Сума", "Идеални части", "живущи", "Обект", "телефон"))
        assertThat(p.mapping["Сума"]).isEqualTo(IntakeField.FEE_MINOR)
        assertThat(p.mapping["Обект"]).isEqualTo(IntakeField.DESIGNATION)
        assertThat(p.mapping["Идеални части"]).isEqualTo(IntakeField.IDEAL_PARTS)
        assertThat(p.mapping["живущи"]).isEqualTo(IntakeField.OCCUPANTS)
        assertThat(p.missingRequired).isEmpty()
        assertThat(p.unmappedColumns).containsExactly("телефон")   // surfaced, not dropped
    }

    @Test
    fun `a required field with no column is named, not silently missing`() {
        val p = MappingProfiler.profile(listOf("designation", "occupants", "fee_minor"))   // no ideal parts
        assertThat(p.missingRequired).containsExactly(IntakeField.IDEAL_PARTS)
    }

    @Test
    fun `unrecognised columns are surfaced for a human, never dropped`() {
        val p = MappingProfiler.profile(listOf("designation", "ideal_parts", "occupants", "fee_minor", "notes", "iban"))
        assertThat(p.unmappedColumns).containsExactly("notes", "iban")
    }

    @Test
    fun `optional fields map when the sheet carries them`() {
        val p = MappingProfiler.profile(
            listOf("designation", "ideal_parts", "occupants", "fee_minor", "площ", "собственик", "животни"),
        )
        assertThat(p.mapping["площ"]).isEqualTo(IntakeField.BUILT_AREA)
        assertThat(p.mapping["собственик"]).isEqualTo(IntakeField.OWNER_NAME)
        assertThat(p.mapping["животни"]).isEqualTo(IntakeField.ANIMALS)
    }

    @Test
    fun `two columns for one field — first wins, the second is surfaced not dropped`() {
        // a sheet that carries the fee twice ("amount" and "сума" both mean FEE_MINOR)
        val p = MappingProfiler.profile(listOf("designation", "ideal_parts", "occupants", "amount", "сума"))
        assertThat(p.mapping["amount"]).isEqualTo(IntakeField.FEE_MINOR)   // first-wins
        assertThat(p.mapping).doesNotContainKey("сума")
        assertThat(p.unmappedColumns).containsExactly("сума")              // the duplicate is surfaced
        assertThat(p.missingRequired).isEmpty()
    }

    @Test
    fun `a wholly unrecognised header maps nothing and names every required field`() {
        val p = MappingProfiler.profile(listOf("col_a", "col_b", "col_c", "col_d"))
        assertThat(p.mapping).isEmpty()
        assertThat(p.unmappedColumns).containsExactly("col_a", "col_b", "col_c", "col_d")
        assertThat(p.missingRequired).containsExactlyInAnyOrderElementsOf(IntakeField.REQUIRED)
    }
}
