package zues.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SysPrimitivesTest {

    private val at = Instant.parse("2026-11-09T10:00:00Z")

    @Test
    fun `PM-SYS-006 an unmet precondition is passed only by a documented deviation, never skipped`() {
        assertEquals(PreconditionOutcome.Met("notice-posted"), checkPrecondition("notice-posted", met = true))

        val skipped = assertThrows(PreconditionNotMet::class.java) { checkPrecondition("notice-posted", met = false) }
        assertEquals("notice-posted", skipped.precondition)

        val deviation = PreconditionOutcome.Deviation("notice-posted", "posted late, every owner signed receipt", "manager-1", at)
        // Overrides are evidence, not silence: what passes the gate is the deviation itself, reason and all.
        assertSame(deviation, checkPrecondition("notice-posted", met = false, deviation = deviation))
    }

    @Test
    fun `PM-SYS-006 a deviation needs a reason and must name the precondition it departs from`() {
        assertThrows(IllegalArgumentException::class.java) { PreconditionOutcome.Deviation("quorum", " ", "manager-1", at) }
        assertThrows(IllegalArgumentException::class.java) { PreconditionOutcome.Deviation("quorum", "reason", "", at) }
        val other = PreconditionOutcome.Deviation("quorum", "reconvened", "manager-1", at)
        assertThrows(PreconditionNotMet::class.java) { checkPrecondition("notice-posted", met = false, deviation = other) }
    }

    private fun report(vararg lines: Pair<String, Long>, exportedAt: Instant = at) =
        ReportSnapshot.of("2026-10", lines.map { ReportLine(it.first, eur(it.second)) }, exportedAt)

    @Test
    fun `PM-SYS-012 the same period re-exported yields the same figures and the same hash`() {
        val first = report("unit-1" to 4250, "unit-2" to 3100)
        val again = report("unit-2" to 3100, "unit-1" to 4250, exportedAt = at.plusSeconds(86_400))
        assertEquals(first.hash, again.hash)
        assertSame(again, verifyReexport(first, again))
        assertNotEquals(first.hash, ReportSnapshot.of("2026-11", first.lines, at).hash)
        assertThrows(IllegalArgumentException::class.java) { report("unit-1" to 1, "unit-1" to 2) }
    }

    @Test
    fun `PM-SYS-012 different figures are refused unless a documented correction names every changed line`() {
        val first = report("unit-1" to 4250, "unit-2" to 3100)
        val changed = report("unit-1" to 4300, "unit-3" to 900)

        val refused = assertThrows(ReportNotReproduced::class.java) { verifyReexport(first, changed) }
        assertEquals(setOf("unit-1", "unit-2", "unit-3"), refused.unexplained)

        val partial = ReportCorrection("2026-10", setOf("unit-1"), "late payment posted", "manager-1", at.plusSeconds(60))
        val stillOpen = assertThrows(ReportNotReproduced::class.java) { verifyReexport(first, changed, listOf(partial)) }
        assertEquals(setOf("unit-2", "unit-3"), stillOpen.unexplained)

        val otherPeriod = ReportCorrection("2026-09", setOf("unit-2", "unit-3"), "wrong period", "manager-1", at.plusSeconds(60))
        assertThrows(ReportNotReproduced::class.java) { verifyReexport(first, changed, listOf(partial, otherPeriod)) }

        // A correction only explains a difference if it intervened — recorded after the original export.
        val before = ReportCorrection("2026-10", setOf("unit-2", "unit-3"), "unit split", "manager-1", at.minusSeconds(60))
        assertThrows(ReportNotReproduced::class.java) { verifyReexport(first, changed, listOf(partial, before)) }

        val rest = ReportCorrection("2026-10", setOf("unit-2", "unit-3"), "unit split", "manager-1", at.plusSeconds(60))
        assertSame(changed, verifyReexport(first, changed, listOf(partial, rest)))
        assertThrows(IllegalArgumentException::class.java) { ReportCorrection("2026-10", setOf("unit-1"), "", "manager-1", at) }
    }

    @Test
    fun `PM-SYS-014 a queued vote renders as изпраща се, never as cast, until the server confirms`() {
        assertThrows(IllegalArgumentException::class.java) { IdempotencyKey(" ") }
        val vote = OfflineAction(IdempotencyKey("k-1"), "assembly.vote")
        assertEquals(OfflineStatus.QUEUED, vote.status)
        assertFalse(vote.performed)
        assertEquals("изпраща се", vote.pendingLabel)

        val cast = vote.confirmed("vote-17")
        assertTrue(cast.performed)
        assertNull(cast.pendingLabel)
        assertThrows(IllegalStateException::class.java) { cast.confirmed("vote-18") }

        val refused = vote.rejected()
        assertFalse(refused.performed)
        assertNull(refused.pendingLabel)
    }

    @Test
    fun `PM-SYS-014 replaying the queue twice produces one vote`() {
        val kept = mutableMapOf<IdempotencyKey, KeptKey>()
        var writes = 0
        fun serve(action: OfflineAction, requestHash: String): OfflineAction =
            when (val d = decideKey(kept[action.key], action.operation, requestHash)) {
                KeyDecision.Apply -> {
                    writes++
                    kept[action.key] = KeptKey(action.operation, requestHash, "vote-$writes")
                    action.confirmed("vote-$writes")
                }
                is KeyDecision.Replay -> action.confirmed(d.resultId)
                KeyDecision.Reused -> action.rejected()
            }

        val queue = listOf(
            OfflineAction(IdempotencyKey("k-1"), "assembly.vote") to "for",
            OfflineAction(IdempotencyKey("k-2"), "assembly.vote") to "against",
        )
        val first = queue.map { (a, h) -> serve(a, h) }
        val second = queue.map { (a, h) -> serve(a, h) }

        assertEquals(2, writes)
        assertEquals(first.map { it.resultId }, second.map { it.resultId })
        assertTrue(second.all { it.performed })

        // The same key for another request or operation is refused, never applied.
        assertEquals(KeyDecision.Reused, decideKey(kept[IdempotencyKey("k-1")], "money.pay", "for"))
        assertFalse(serve(OfflineAction(IdempotencyKey("k-1"), "assembly.vote"), "against").performed)
        assertEquals(2, writes)
    }
}
