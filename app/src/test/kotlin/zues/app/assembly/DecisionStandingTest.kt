package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

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
}
