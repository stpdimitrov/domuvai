package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import zues.law.Comparison
import zues.law.Denominator
import zues.law.MajorityRule
import zues.law.majorityRuleOn
import java.math.BigDecimal
import java.util.UUID

/** The tally, pure: ideal parts against the majority's own denominator, exactly, reported to 2 decimals. */
class DecisionTallyTest {

    private val units = List(4) { UUID.randomUUID() }
    private val parts = units.zip(listOf("40", "30", "20", "10")).associate { (u, p) -> u to BigDecimal(p) }
    private val general = majorityRuleOn("GENERAL", "2026-10-24")

    private fun cast(unit: Int, choice: VoteChoice, weight: String = parts.getValue(units[unit]).toPlainString()) =
        Cast(UUID.randomUUID(), UUID.randomUUID(), units[unit], BigDecimal(weight), choice)

    /** A rule of the law's shape with another denominator — the law names no TOTAL majority yet. */
    private fun ofTotal(threshold: String) = MajorityRule("TEST_TOTAL", threshold, Comparison.MORE_THAN, Denominator.TOTAL, "2026-01-01", "test", true, "PM-VOTE-012")

    @Test
    fun `PM-VOTE-002 an item with no explicit threshold resolves to more than 50 percent of the represented ideal parts and states the denominator`() {
        assertThat(general.denominator).isEqualTo(Denominator.REPRESENTED)
        // 80% represented: 40 for, 40 against — exactly half of represented is not more than half
        val even = tally(listOf(cast(0, VoteChoice.FOR), cast(1, VoteChoice.AGAINST), cast(3, VoteChoice.AGAINST)), parts, BigDecimal("80"), general)
        assertThat(even.passed).isFalse()
        assertThat(even.line).contains("50.00% of represented").contains("more than 50% of represented").endsWith("NOT PASSED")
        // 40 + 10 for of 80 represented: 62.5% of represented, though only 50% of all — passes on its denominator
        val more = tally(listOf(cast(0, VoteChoice.FOR), cast(3, VoteChoice.FOR), cast(1, VoteChoice.AGAINST)), parts, BigDecimal("80"), general)
        assertThat(more.passed).isTrue()
        assertThat(more.line).startsWith("FOR 62.50% of represented (50.00% of total, 80.00% represented)").endsWith("— PASSED")
    }

    @Test
    fun `PM-VOTE-012 a threshold against all ideal parts is never satisfied from the represented subset`() {
        // 60% represented, 45 for: three quarters of represented, but 45% of all — not more than half of total
        val casts = listOf(cast(0, VoteChoice.FOR), cast(2, VoteChoice.AGAINST, "15"), cast(3, VoteChoice.FOR, "5"))
        val total = tally(casts, parts, BigDecimal("60"), ofTotal("50"))
        assertThat(total.forParts).isEqualByComparingTo("45")
        assertThat(total.passed).isFalse()
        assertThat(total.line).contains("45.00% of total").contains("more than 50% of total").endsWith("NOT PASSED")
        assertThat(tally(casts, parts, BigDecimal("60"), general).passed).isTrue()                        // the same votes against represented
        assertThat(tally(casts, parts, BigDecimal("60"), ofTotal("44")).passed).isTrue()
    }

    @Test
    fun `PM-VOTE-011 the tally is on ideal parts, reported to 2 decimals, a unit capped at its own parts, and decided on the exact numbers`() {
        // three co-owners of the 30% unit at a third each: 10.0001 × 3 rounds to a hair over the unit — it counts 30
        val thirds = List(3) { cast(1, VoteChoice.FOR, "10.0001") }
        val result = tally(thirds + cast(2, VoteChoice.AGAINST) + cast(3, VoteChoice.ABSTAIN), parts, BigDecimal("60"), general)
        assertThat(listOf(result.forParts, result.againstParts, result.abstainParts).map { it.stripTrailingZeros().toPlainString() }).containsExactly("30", "20", "10")
        assertThat(result.passed).isFalse()                                                             // 30 of 60: half, not more
        assertThat(result.line).startsWith("FOR 50.00% of represented (30.00% of total, 60.00% represented)")
        // 30.0001 of 60 reports as 50.00% yet is more than half: the decision is the exact one
        val hair = tally(listOf(cast(1, VoteChoice.FOR, "20"), cast(2, VoteChoice.FOR, "10.0001")), parts, BigDecimal("60"), general)
        assertThat(hair.passed).isTrue()
        assertThat(hair.line).contains("50.00% of represented")
        // a share of a third of a unit is kept exactly in the sum, past four decimals
        assertThat(tally(listOf(cast(3, VoteChoice.FOR, "3.333333")), parts, BigDecimal("60"), general).forParts).isEqualByComparingTo("3.333333")
    }
}
