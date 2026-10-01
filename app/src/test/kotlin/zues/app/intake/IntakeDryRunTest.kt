package zues.app.intake

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    private fun concierge(stream: String = "MAINTENANCE", key: String, rateMinor: Long? = null, totalMinor: Long? = null) =
        TariffInput(stream, key, "GA-2026-2", rateMinor, totalMinor, item = "CONCIERGE")

    @Test
    fun `PM-FEE-011 a concierge line on another key than maintenance's is a violation — the sheet is not reproduced`() {
        // The firm bills a concierge 500 a unit beside maintenance by ideal parts. The figures add up — unnamed,
        // the line would pass as maintenance on a second key — but a concierge cost follows maintenance's key.
        val perUnit = maintenance() + concierge(key = "PER_UNIT", rateMinor = 500)
        val report = IntakeDryRun.of("e1", period, on, null, perUnit, sheet(6500, 4500))
        assertThat(report.reproduced).isFalse()
        assertThat(report.violations.single()).contains("CONCIERGE").contains("PM-FEE-011")
        assertThat(report.matched).isEqualTo(0)

        // the same figures with the line left unnamed are plain maintenance on two keys: nothing to report
        val unnamed = maintenance() + TariffInput("MAINTENANCE", "PER_UNIT", "GA-2026-2", rateMinor = 500)
        assertThat(IntakeDryRun.of("e1", period, on, null, unnamed, sheet(6500, 4500)).reproduced).isTrue()
    }

    @Test
    fun `PM-FEE-011 a concierge line in another stream, or named twice, is a violation`() {
        val otherStream = maintenance() + concierge(stream = "MANAGEMENT", key = "BY_IDEAL_PARTS", totalMinor = 3_000)
        val twice = maintenance() + concierge(key = "BY_IDEAL_PARTS", totalMinor = 1_000) + concierge(key = "BY_IDEAL_PARTS", totalMinor = 2_000)
        for (lines in listOf(otherStream, twice)) {
            val report = IntakeDryRun.of("e1", period, on, null, lines, sheet(7800, 5200))
            assertThat(report.reproduced).isFalse()
            assertThat(report.violations.single()).contains("CONCIERGE").contains("PM-FEE-011")
        }
    }

    @Test
    fun `PM-FEE-014 a sheet billed with a concierge line on maintenance's key is reproduced to the cent`() {
        // 10000 maintenance + 3000 concierge, both by ideal parts: 60/40 -> 7800 / 5200
        val lines = maintenance() + concierge(key = "BY_IDEAL_PARTS", totalMinor = 3_000)
        val report = IntakeDryRun.of("e1", period, on, null, lines, sheet(7800, 5200))
        assertThat(report.violations).isEmpty()
        assertThat(report.reproduced).isTrue()
        assertThat(report.matched).isEqualTo(2)
    }

    @Test
    fun `PM-FEE-017 a metered cost named on a keyed line is a violation — it is not billed as maintenance`() {
        val lines = maintenance() + TariffInput("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-2", totalMinor = 3_000, item = "WATER")
        val report = IntakeDryRun.of("e1", period, on, null, lines, sheet(7800, 5200))   // unnamed, these figures reproduce
        assertThat(report.reproduced).isFalse()
        assertThat(report.violations.single()).contains("PM-FEE-017")
    }

    @Test
    fun `a cost the law does not name is the caller's error, not a finding about the sheet — whatever the sheet holds`() {
        val empty = FeeSheet.parse("designation,ideal_parts,occupants,fee_minor")
        for ((item, sheet) in listOf("DOORBELL" to sheet(7800, 5200), "concierge" to sheet(7800, 5200), "" to sheet(7800, 5200), "DOORBELL" to empty)) {
            val lines = maintenance() + TariffInput("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-2", totalMinor = 3_000, item = item)
            assertThatThrownBy { IntakeDryRun.of("e1", period, on, null, lines, sheet) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("CostItem")
        }
    }

    @Test
    fun `PM-FEE-008 PM-FEE-005 the sheet's occupants are the persons charged — its children, animal, absence and business cells change no recomputed fee`() {
        // 500 a person. ап. 1 was charged on 3 persons; the sheet also says a child lives there, two animals,
        // a long absence and a business. The 3 already is what those came to (D1 on #31, #86): none is applied again.
        val perPerson = listOf(TariffInput("MAINTENANCE", "PER_PERSON", "GA-2026-1", rateMinor = 500))
        val stated = FeeSheet.parse(
            "designation,ideal_parts,occupants,fee_minor,children,animals,absent_days,business\n" +
                "ап. 1,60.0000,3,1500,1,2,45,да\nап. 2,40.0000,1,500,,,,",
        )
        assertThat(stated.rows.first().optional.keys).containsExactlyInAnyOrder(      // the four cells were read, not dropped
            IntakeField.CHILDREN_UNDER_6, IntakeField.ANIMALS, IntakeField.ABSENT_DAYS, IntakeField.BUSINESS_USE,
        )
        val report = IntakeDryRun.of("e1", period, on, null, perPerson, stated)
        assertThat(report.violations).isEmpty()
        assertThat(report.differences).isEmpty()
        assertThat(report.reproduced).isTrue()

        // by ideal parts, a business cell applies no multiple: 60/40 of 10000
        val byParts = FeeSheet.parse(
            "designation,ideal_parts,occupants,fee_minor,business\nап. 1,60.0000,3,6000,\nап. 2,40.0000,1,4000,да",
        )
        assertThat(IntakeDryRun.of("e1", period, on, null, maintenance(), byParts).reproduced).isTrue()
    }
}
