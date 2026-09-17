package zues.app.intake

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IntakeDryRunTest {

    private val period = "2026-05"
    private val on = "2026-05-01"

    // a 10000-minor maintenance pot split by ideal parts: 60/40 -> 6000 / 4000
    private fun maintenance() = listOf(TariffInput("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000))

    private fun sheet(feeA: Long, feeB: Long) = FeeSheet.parse(
        """
        designation,ideal_parts,occupants,fee_minor
        ап. 1,60.0000,2,$feeA
        ап. 2,40.0000,1,$feeB
        """.trimIndent(),
    )

    @Test
    fun `PM-FEE-014 the engine reproduces the sheet to the cent`() {
        val report = IntakeDryRun.of("e1", period, on, null, maintenance(), sheet(6000, 4000))
        assertThat(report.reproduced).isTrue()
        assertThat(report.matched).isEqualTo(2)
        assertThat(report.differing).isEqualTo(0)
    }

    @Test
    fun `a one-cent difference is named, not hidden`() {
        val report = IntakeDryRun.of("e1", period, on, null, maintenance(), sheet(6001, 4000))
        assertThat(report.reproduced).isFalse()
        assertThat(report.differing).isEqualTo(1)
        val diff = report.differences.single()
        assertThat(diff.designation).isEqualTo("ап. 1")
        assertThat(diff.theirMinor).isEqualTo(6001)
        assertThat(diff.ourMinor).isEqualTo(6000)
        assertThat(diff.deltaMinor).isEqualTo(-1)   // ours − theirs
    }

    @Test
    fun `PM-ORG-002 ideal parts that do not sum to 100 percent are a violation`() {
        val badSheet = FeeSheet.parse(
            """
            designation,ideal_parts,occupants,fee_minor
            ап. 1,60.0000,2,6000
            ап. 2,30.0000,1,4000
            """.trimIndent(),
        )
        val report = IntakeDryRun.of("e1", period, on, null, maintenance(), badSheet)
        assertThat(report.reproduced).isFalse()
        assertThat(report.violations).isNotEmpty()
    }
}
