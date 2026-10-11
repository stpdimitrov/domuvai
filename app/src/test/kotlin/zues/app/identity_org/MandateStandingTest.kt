package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import zues.app.policy.Role
import java.time.LocalDate
import java.util.UUID

/**
 * How a mandate stands on a date (PM-GOV-004's acceptance, PM-GOV-005): in term, an election due, acting past term —
 * derived from its dates and the one reading of "successor". No Spring, no database; the reminder period is asked of
 * the law module, so the test names no figure of its own.
 */
class MandateStandingTest {

    private val block = UUID.randomUUID()
    private fun day(iso: String) = LocalDate.parse(iso)
    private val from = day("2024-03-01")
    private val ends = day("2026-03-01")                                                           // the day the term ends: the first day outside it

    private fun mandate(body: String, from: LocalDate = this.from, to: LocalDate = ends, succeededAt: LocalDate? = null, at: UUID = block) =
        Mandate(UUID.randomUUID(), at, body, UUID.randomUUID(), from, to, succeededAt)

    /** The reminder period the law module has in force on the day a term ends. */
    private fun reminder(termEnds: LocalDate) = zues.law.numberOn("MANDATE_ELECTION_REMINDER_DAYS", termEnds.toString()).toLong()
    private val dueFrom = ends.minusDays(reminder(ends))

    private fun Mandate.standing(on: LocalDate, others: List<Mandate> = emptyList()) = standingOn(on, others + this, ::reminder)

    @Test
    fun `PM-GOV-004 a manager's or a board member's mandate is in term, then an election is due, then it is acting past term — each from its own day`() {
        for (office in listOf("BM", "MB")) {
            val m = mandate(office)
            assertThat(m.standing(from.minusDays(1))).describedAs("$office before it began").isNull()
            assertThat(m.standing(from)).isEqualTo(Standing(StandingKind.IN_TERM))
            assertThat(m.standing(dueFrom.minusDays(1))).isEqualTo(Standing(StandingKind.IN_TERM))
            assertThat(m.standing(dueFrom)).describedAs("$office, the first day of the reminder period").isEqualTo(Standing(StandingKind.ELECTION_DUE, electBy = ends))
            assertThat(m.standing(ends.minusDays(1))).describedAs("$office, the last day of the term").isEqualTo(Standing(StandingKind.ELECTION_DUE, electBy = ends))
            assertThat(m.standing(ends)).describedAs("$office, the day the term ends").isEqualTo(Standing(StandingKind.EXPIRED_ACTING, electBy = ends, actingSince = ends))
            assertThat(m.standing(ends.plusYears(3))).isEqualTo(Standing(StandingKind.EXPIRED_ACTING, electBy = ends, actingSince = ends))
        }
    }

    @Test
    fun `PM-GOV-005 the election is to be held no later than the day the term ends — and once a successor is on record, none is due`() {
        val manager = mandate("BM")
        // elected in time, to begin the day the term ends: no election is due any more, and the mandate is never acting
        val inTime = mandate("BM", from = ends, to = ends.plusYears(1))
        assertThat(manager.standing(dueFrom, listOf(inTime))).isEqualTo(Standing(StandingKind.IN_TERM, until = ends))
        assertThat(manager.standing(ends.minusDays(1), listOf(inTime))).isEqualTo(Standing(StandingKind.IN_TERM, until = ends))
        assertThat(manager.standing(ends, listOf(inTime))).isNull()
        // elected late, to begin two months after: the manager is acting in between, until the successor's first day
        val late = mandate("BM", from = ends.plusMonths(2), to = ends.plusMonths(14))
        assertThat(manager.standing(ends.minusDays(1), listOf(late))).isEqualTo(Standing(StandingKind.IN_TERM, until = late.validFrom))
        assertThat(manager.standing(ends.plusDays(10), listOf(late)))
            .isEqualTo(Standing(StandingKind.EXPIRED_ACTING, electBy = ends, actingSince = ends, until = late.validFrom))
        assertThat(manager.standing(late.validFrom, listOf(late))).isNull()
        // an end recorded on the mandate itself says the same as a successor found on the record
        val stamped = manager.copy(succeededAt = ends.plusMonths(2))
        assertThat(stamped.standing(ends.plusDays(10))).isEqualTo(Standing(StandingKind.EXPIRED_ACTING, electBy = ends, actingSince = ends, until = ends.plusMonths(2)))
        assertThat(stamped.standing(dueFrom)).isEqualTo(Standing(StandingKind.IN_TERM, until = ends.plusMonths(2)))
    }

    @Test
    fun `PM-GOV-004 a board member whose term ran out while a by-elected colleague sits on is acting past term — and an election is due for that seat, not held`() {
        // The audit's case: A serves 2024-01-01 → 2026-01-01; B is by-elected 2025-03-01 → 2027-03-01.
        val a = mandate("MB", from = day("2024-01-01"), to = day("2026-01-01"))
        val b = mandate("MB", from = day("2025-03-01"), to = day("2027-03-01"))
        val board = listOf(b)
        assertThat(a.standing(day("2025-12-31"), board)).isEqualTo(Standing(StandingKind.ELECTION_DUE, electBy = day("2026-01-01")))   // B's election was not A's successor's
        assertThat(a.standing(day("2026-01-01"), board)).isEqualTo(Standing(StandingKind.EXPIRED_ACTING, electBy = day("2026-01-01"), actingSince = day("2026-01-01")))
        assertThat(a.standing(day("2026-09-01"), board)).isEqualTo(Standing(StandingKind.EXPIRED_ACTING, electBy = day("2026-01-01"), actingSince = day("2026-01-01")))
        assertThat(b.standing(day("2026-09-01"), listOf(a))).isEqualTo(Standing(StandingKind.IN_TERM))
    }

    @Test
    fun `PM-GOV-004 a controller and a cashier are in term or not in force — no election is called due for them, and they do not act past term`() {
        for (office in listOf("CTL", "CSH")) {
            val m = mandate(office)
            assertThat(m.standing(dueFrom)).describedAs(office).isEqualTo(Standing(StandingKind.IN_TERM))
            assertThat(m.standing(ends.minusDays(1))).describedAs(office).isEqualTo(Standing(StandingKind.IN_TERM))
            assertThat(m.standing(ends)).describedAs(office).isNull()
        }
        // a firm's own mandate runs its dates like any other; it is nobody's role, and the read leaves it out (below)
        assertThat(mandate("PMC").standing(ends.minusDays(1))).isEqualTo(Standing(StandingKind.IN_TERM))
        assertThat(mandate("PMC").standing(ends)).isNull()
    }

    @Test
    fun `a mandate has a standing exactly when it is in force — on every day, for every office, with a successor or without`() {
        val successor = mandate("BM", from = ends.plusMonths(2), to = ends.plusMonths(14))
        for (office in listOf("BM", "MB", "CTL", "CSH")) for (others in listOf(emptyList(), listOf(successor))) for (stamp in listOf(null, from.plusMonths(6))) {
            val m = mandate(office, succeededAt = stamp)
            for (on in listOf(from.minusDays(1), from, from.plusMonths(6), dueFrom, ends.minusDays(1), ends, ends.plusMonths(2), ends.plusYears(1)))
                assertThat(m.standing(on, others) != null).describedAs("$office on $on, stamped $stamp, ${others.size} other(s)").isEqualTo(m.inForceOn(on, others + m))
        }
    }

    @Test
    fun `PM-GOV-004 the read lists every mandate in force in the entrance with its standing — and nobody who is not`() {
        val store: MandateStore = mock()
        val manager = mandate("BM")
        val controller = mandate("CTL", from = day("2025-06-01"), to = day("2027-06-01"))
        val gone = mandate("BM", from = day("2022-03-01"), to = day("2024-03-01"), succeededAt = from)
        val firm = mandate("PMC")
        whenever(store.ofEntrance(block)).thenReturn(listOf(gone, manager, controller, firm))
        val read = MandateStanding(store)
        assertThat(read.of(block, ends.plusDays(5))).containsExactly(
            StandingMandate(manager.id, manager.partyId, Role.BM, from, ends, Standing(StandingKind.EXPIRED_ACTING, electBy = ends, actingSince = ends)),
            StandingMandate(controller.id, controller.partyId, Role.CTL, controller.validFrom, controller.validTo, Standing(StandingKind.IN_TERM)),
        )
        assertThat(read.of(block, dueFrom).map { it.standing.kind }).containsExactly(StandingKind.ELECTION_DUE, StandingKind.IN_TERM)
        assertThat(read.of(block, dueFrom.minusDays(1)).map { it.standing.kind }).containsExactly(StandingKind.IN_TERM, StandingKind.IN_TERM)
        assertThat(read.of(UUID.randomUUID(), ends)).isEmpty()
    }

    @Test
    fun `PM-SYS-002 the reminder period is the one in force on the day the term ends — not on the day asked about`() {
        // The law holds the period from a day. A term ending just after that day is judged by it even when the day
        // asked about lies before it; a term ending before it has no reminder, and is in term to its last day.
        val store: MandateStore = mock()
        val inForceFrom = day(zues.law.constantOn("MANDATE_ELECTION_REMINDER_DAYS", "2026-01-01").inForceFrom)
        val endsJustAfter = mandate("BM", from = inForceFrom.minusYears(1), to = inForceFrom.plusDays(14))
        val endsBefore = mandate("BM", from = inForceFrom.minusYears(2), to = inForceFrom.minusDays(1), at = UUID.randomUUID())
        whenever(store.ofEntrance(block)).thenReturn(listOf(endsJustAfter))
        whenever(store.ofEntrance(endsBefore.entranceId)).thenReturn(listOf(endsBefore))
        val read = MandateStanding(store)
        val asked = inForceFrom.minusDays(10)                                                       // before the law holds any period; inside the reminder period of the first
        assertThat(read.of(block, asked).single().standing).isEqualTo(Standing(StandingKind.ELECTION_DUE, electBy = endsJustAfter.validTo))
        assertThat(read.of(endsBefore.entranceId, inForceFrom.minusDays(2)).single().standing).isEqualTo(Standing(StandingKind.IN_TERM))
        assertThat(read.of(endsBefore.entranceId, inForceFrom.minusDays(1)).single().standing.kind).isEqualTo(StandingKind.EXPIRED_ACTING)
    }
}
