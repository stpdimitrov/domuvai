package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/** What stands behind a charge, with the repositories mocked. ChargeRunDecisionIT reads a real paper decision through the issue. */
class GaDecisionsTest {

    private val decisions: DecisionRepository = mock()
    private val merged: MergedDecisionRepository = mock()
    private val paper: PaperDecisionRepository = mock()
    private val port = GaDecisionsAdapter(decisions, merged, paper)
    private val entrance = UUID.randomUUID()

    init {
        whenever(decisions.findById(any())).thenReturn(Optional.empty())
        whenever(merged.findById(any())).thenReturn(Optional.empty())
        whenever(paper.findById(any())).thenReturn(Optional.empty())
    }

    private fun taken(outcome: String, status: String = "PROVISIONAL", of: UUID = entrance): Decision {
        val none = UUID.randomUUID()
        val row = Decision(
            UUID.randomUUID(), of, none, none, 1, "PM-GA-012", "TOTAL", "MORE_THAN", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
            outcome, status, "basis", "hash", "v", "e", Instant.EPOCH, none, "issuer", "subject",
        )
        whenever(decisions.findById(row.id)).thenReturn(Optional.of(row))
        return row
    }

    private fun mergedInto(decision: Decision, outcome: String): MergedDecision {
        val none = UUID.randomUUID()
        val row = MergedDecision(
            UUID.randomUUID(), decision.entranceId, decision.assemblyId, decision.agendaItemId, decision.id, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
            outcome, "basis", "hash", "v", "e", Instant.EPOCH, none, "issuer", "subject",
        )
        whenever(merged.findById(row.id)).thenReturn(Optional.of(row))
        whenever(merged.findBySupersedes(decision.id)).thenReturn(row)
        return row
    }

    private fun recorded(of: UUID = entrance): PaperDecision {
        val row = PaperDecision(UUID.randomUUID(), of, "text", LocalDate.EPOCH, "ref", "where", "who", "basis", "hash", "v", "e", null, null, Instant.EPOCH)
        whenever(paper.findById(row.id)).thenReturn(Optional.of(row))
        return row
    }

    private fun passed(id: UUID) = port.passed(entrance, id.toString())

    @Test
    fun `PM-FEE-012 a passed decision of the entrance stands behind a charge, PROVISIONAL or FINAL`() {
        assertThat(passed(taken("PASSED").id)).isTrue()
        assertThat(passed(taken("PASSED", "FINAL").id)).isTrue()
    }

    @Test
    fun `PM-FEE-012 a decision that did not pass, or was annulled, does not`() {
        assertThat(passed(taken("NOT_PASSED").id)).isFalse()
        assertThat(passed(taken("PASSED", "ANNULLED").id)).isFalse()
    }

    @Test
    fun `PM-FEE-012 a decision or a paper decision of another entrance does not`() {
        assertThat(passed(taken("PASSED", of = UUID.randomUUID()).id)).isFalse()
        assertThat(passed(recorded(of = UUID.randomUUID()).id)).isFalse()
    }

    @Test
    fun `PM-FEE-012 an unknown id does not, nor anything that is no id, nor another spelling of a stored one`() {
        val stored = taken("PASSED").id
        assertThat(passed(UUID.randomUUID())).isFalse()
        assertThat(port.passed(entrance, "GA-2026-03-12-4")).isFalse()
        assertThat(port.passed(entrance, "")).isFalse()
        assertThat(port.passed(entrance, stored.toString().uppercase())).isFalse()
    }

    @Test
    fun `PM-FEE-012 a decision recorded from a paper protocol does`() {
        assertThat(passed(recorded().id)).isTrue()
    }

    @Test
    fun `PM-VOTE-013 once absentee ballots are merged the merged outcome is what stands behind a charge, under either id`() {
        val overturned = taken("PASSED")
        val lost = mergedInto(overturned, "NOT_PASSED")
        assertThat(passed(overturned.id)).isFalse()
        assertThat(passed(lost.id)).isFalse()

        val turned = taken("NOT_PASSED")
        val won = mergedInto(turned, "PASSED")
        assertThat(passed(turned.id)).isTrue()
        assertThat(passed(won.id)).isTrue()
    }

    @Test
    fun `PM-FEE-012 a merged decision is the entrance's and stands only as its decision stands`() {
        assertThat(passed(mergedInto(taken("PASSED", of = UUID.randomUUID()), "PASSED").id)).isFalse()
        assertThat(passed(mergedInto(taken("PASSED", "ANNULLED"), "PASSED").id)).isFalse()
    }
}
