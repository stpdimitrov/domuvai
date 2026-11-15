package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Where a decision stands, pure: PROVISIONAL until the absentee window and the time to seek annulment have closed. */
class DecisionStandingTest {

    @Test
    fun `PM-VOTE-013 a decision is PROVISIONAL until day 7 after its sitting closes - rolled off a weekend`() {
        val monday = standing("2026-11-02", null, "2026-11-09")
        assertThat(monday.absenteeCloses).isEqualTo("2026-11-09")                         // day 7 is a Monday
        assertThat(monday.status).isEqualTo("PROVISIONAL")
        assertThat(standing("2026-11-14", null, "2026-11-22").absenteeCloses).isEqualTo("2026-11-23")   // day 7 a Saturday → Monday
        // day 7 long closed, but the protocol not posted: no countdown starts, and nothing becomes final
        val unposted = standing("2026-11-02", null, "2027-11-02")
        assertThat(unposted.status).isEqualTo("PROVISIONAL")
        assertThat(unposted.annulmentCloses).isNull()
        assertThat(unposted.finalOn).isNull()
    }

    @Test
    fun `PM-GA-023 a decision shows its countdown from the protocol's posting and is FINAL the day after the 30 days`() {
        val posted = standing("2026-11-02", "2026-11-03", "2026-12-03")
        assertThat(posted.annulmentCloses).isEqualTo("2026-12-03")                        // 30 days after the posting, a Thursday
        assertThat(posted.finalOn).isEqualTo("2026-12-04")
        assertThat(posted.status).isEqualTo("PROVISIONAL")                                // the last day to seek annulment
        assertThat(standing("2026-11-02", "2026-11-03", "2026-12-04").status).isEqualTo("FINAL")
        // posted on 2026-11-24: day 30 is Christmas Eve, a holiday → rolled past Christmas to the next working day
        val christmas = standing("2026-11-23", "2026-11-24", "2026-12-28")
        assertThat(christmas.annulmentCloses).isEqualTo("2026-12-28")
        assertThat(christmas.status).isEqualTo("PROVISIONAL")
        assertThat(standing("2026-11-23", "2026-11-24", "2026-12-29").status).isEqualTo("FINAL")
        // a posting before the sitting would end the 30 days inside the absentee window: not a posting
        assertThatThrownBy { standing("2026-11-02", "2026-10-01", "2026-12-04") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `PM-VOTE-013 an item tagged for absentee ballots stays PROVISIONAL until they are merged, after day 7 - then FINAL on the later of the two days`() {
        val unmerged = standing("2026-11-02", "2026-11-03", "2027-11-02", eligible = true)
        assertThat(unmerged.status).isEqualTo("PROVISIONAL")
        assertThat(unmerged.finalOn).isNull()
        assertThat(standing("2026-11-02", "2026-11-03", "2026-12-04", eligible = true, mergedOn = "2026-11-10").status).isEqualTo("FINAL")
        val late = standing("2026-11-02", "2026-11-03", "2026-12-04", eligible = true, mergedOn = "2026-12-10")
        assertThat(late.finalOn).isEqualTo("2026-12-10")
        assertThat(late.status).isEqualTo("PROVISIONAL")
        // a merge on day 7 itself, or on an item not tagged, is not a merge
        assertThatThrownBy { standing("2026-11-02", null, "2026-11-09", eligible = true, mergedOn = "2026-11-09") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { standing("2026-11-02", null, "2026-11-10", eligible = false, mergedOn = "2026-11-10") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `PM-GA-023 an eligible item merged before the 30 days close is FINAL the day after them, not before`() {
        val merged = standing("2026-11-02", "2026-11-03", "2026-12-03", eligible = true, mergedOn = "2026-11-10")
        assertThat(merged.finalOn).isEqualTo("2026-12-04")
        assertThat(merged.status).isEqualTo("PROVISIONAL")
    }

    @Test
    fun `PM-GA-017 an absent owner's ballot adds their parts to the represented share`() {
        fun ballot(weight: String) = AbsenteeBallot(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal(weight),
            "FOR", "HANDWRITTEN", "d", "ab".repeat(32), LocalDate.parse("2026-11-03"), UUID.randomUUID(), "i", "s", Instant.EPOCH,
        )
        assertThat(representedWithBallots(BigDecimal("75"), listOf(ballot("12.5"), ballot("0.333333")))).isEqualByComparingTo("87.833333")
        assertThat(representedWithBallots(BigDecimal("75"), emptyList())).isEqualByComparingTo("75")
    }
}
