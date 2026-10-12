package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import zues.app.policy.Role
import java.time.LocalDate
import java.util.UUID

/**
 * How a mandate stands on a date (PM-GOV-004's acceptance): in term, an election due, acting past term — derived from
 * its dates and the one reading of "successor". A fact to be read: nothing here raises a task, schedules an election
 * or shows a countdown (PM-GOV-005's scheduling and the acceptance's task are not built). No Spring, no database.
 */
class MandateStandingTest {

    private val block = UUID.randomUUID()
    private fun day(iso: String) = LocalDate.parse(iso)
    private val from = day("2024-03-01")
    private val ends = day("2026-03-01")                                                           // the day the term ends: the first day outside it

    private fun mandate(body: String, from: LocalDate = this.from, to: LocalDate = ends, succeededAt: LocalDate? = null, at: UUID = block) =
        Mandate(UUID.randomUUID(), at, body, UUID.randomUUID(), from, to, succeededAt)

    /** The reminder period the law module has in force on a day. */
    private fun reminder(leaves: LocalDate): Long? = zues.law.numberOn("MANDATE_ELECTION_REMINDER_DAYS", leaves.toString()).toLong()
    private val dueFrom = ends.minusDays(reminder(ends)!!)

    private fun Mandate.standing(on: LocalDate, others: List<Mandate> = emptyList()) = standingOn(on, others + this, ::reminder)

    private val inTerm = Standing(StandingKind.IN_TERM)
    private fun due(by: LocalDate, until: LocalDate? = null) = Standing(StandingKind.ELECTION_DUE, electBy = by, until = until)
    private fun acting(since: LocalDate, until: LocalDate? = null) = Standing(StandingKind.EXPIRED_ACTING, electBy = since, actingSince = since, until = until)

    @Test
    fun `PM-GOV-004 a manager's or a board member's mandate is in term, then an election is due, then it is acting past term — each from its own day`() {
        // The reminder counts back from the day the term ends, the first day outside it: a term ending on 1 March 2026
        // is called due from 31 December 2025 — 60 days, the catalogue's own figure.
        assertThat(dueFrom).isEqualTo(day("2025-12-31"))
        for (office in listOf("BM", "MB")) {
            val m = mandate(office)
            assertThat(m.standing(from.minusDays(1))).describedAs("$office before it began").isNull()
            assertThat(m.standing(from)).isEqualTo(inTerm)
            assertThat(m.standing(day("2025-12-30"))).isEqualTo(inTerm)
            assertThat(m.standing(day("2025-12-31"))).describedAs("$office, the first day of the reminder period").isEqualTo(due(ends))
            assertThat(m.standing(day("2026-02-28"))).describedAs("$office, the last day of the term").isEqualTo(due(ends))
            assertThat(m.standing(day("2026-03-01"))).describedAs("$office, the day the term ends").isEqualTo(acting(ends))
            assertThat(m.standing(ends.plusYears(3))).isEqualTo(acting(ends))
        }
    }

    @Test
    fun `PM-GOV-004 once somebody is elected to follow, no election is due — and the day to elect by was the day the term ends`() {
        val manager = mandate("BM")
        // elected in time, to begin the day the term ends: no election is due any more, and the mandate is never acting
        val inTime = mandate("BM", from = ends, to = ends.plusYears(1))
        assertThat(manager.standing(dueFrom, listOf(inTime))).isEqualTo(Standing(StandingKind.IN_TERM, until = ends))
        assertThat(manager.standing(ends.minusDays(1), listOf(inTime))).isEqualTo(Standing(StandingKind.IN_TERM, until = ends))
        assertThat(manager.standing(ends, listOf(inTime))).isNull()
        // elected late, to begin two months after: the manager is acting in between, until the successor's first day
        val late = mandate("BM", from = ends.plusMonths(2), to = ends.plusMonths(14))
        assertThat(manager.standing(ends.minusDays(1), listOf(late))).isEqualTo(Standing(StandingKind.IN_TERM, until = late.validFrom))
        assertThat(manager.standing(ends.plusDays(10), listOf(late))).isEqualTo(acting(ends, until = late.validFrom))
        assertThat(manager.standing(late.validFrom, listOf(late))).isNull()
        // an end recorded past the term, with nobody on record to follow: acting until that day — and while in term, still due
        val stamped = manager.copy(succeededAt = ends.plusMonths(2))
        assertThat(stamped.standing(ends.plusDays(10))).isEqualTo(acting(ends, until = ends.plusMonths(2)))
        assertThat(stamped.standing(dueFrom)).isEqualTo(due(ends, until = ends.plusMonths(2)))
    }

    @Test
    fun `PM-GOV-004 an end recorded with nobody elected to follow — a resignation — silences nothing — the election is due by the day the office is left`() {
        val leaves = day("2025-06-01")
        for (office in listOf("BM", "MB")) {
            val resigning = mandate(office, succeededAt = leaves)
            val window = leaves.minusDays(reminder(leaves)!!)
            assertThat(resigning.standing(window.minusDays(1))).describedAs(office).isEqualTo(Standing(StandingKind.IN_TERM, until = leaves))
            assertThat(resigning.standing(window)).describedAs(office).isEqualTo(due(leaves, until = leaves))
            assertThat(resigning.standing(leaves.minusDays(1))).describedAs(office).isEqualTo(due(leaves, until = leaves))
            assertThat(resigning.standing(leaves)).describedAs(office).isNull()                  // the office is empty: no mandate is left to have a standing
            // … and with the successor on record, the same end is an election held
            val successor = mandate("BM", from = leaves, to = leaves.plusYears(1))
            assertThat(resigning.standing(leaves.minusDays(1), listOf(successor))).describedAs(office).isEqualTo(Standing(StandingKind.IN_TERM, until = leaves))
        }
    }

    @Test
    fun `PM-GOV-004 a board member whose term ran out while a by-elected colleague sits on is acting past term — the colleague's election was not this seat's`() {
        // The audit's case: A serves 2024-01-01 → 2026-01-01; B is by-elected 2025-03-01 → 2027-03-01.
        val a = mandate("MB", from = day("2024-01-01"), to = day("2026-01-01"))
        val b = mandate("MB", from = day("2025-03-01"), to = day("2027-03-01"))
        assertThat(a.standing(day("2025-12-31"), listOf(b))).isEqualTo(due(day("2026-01-01")))
        assertThat(a.standing(day("2026-01-01"), listOf(b))).isEqualTo(acting(day("2026-01-01")))
        assertThat(a.standing(day("2026-09-01"), listOf(b))).isEqualTo(acting(day("2026-01-01")))
        assertThat(b.standing(day("2026-09-01"), listOf(a))).isEqualTo(inTerm)
    }

    @Test
    fun `PM-GOV-004 a controller and a cashier are in term or not in force — no election is called due for them, and they do not act past term`() {
        for (office in listOf("CTL", "CSH")) {
            val m = mandate(office)
            assertThat(m.standing(dueFrom)).describedAs(office).isEqualTo(Standing(StandingKind.IN_TERM, until = ends))   // it ends with its dates
            assertThat(m.standing(ends.minusDays(1))).describedAs(office).isEqualTo(Standing(StandingKind.IN_TERM, until = ends))
            assertThat(m.standing(ends)).describedAs(office).isNull()
        }
        // a firm's own mandate runs its dates like any other; it is nobody's role, and the read leaves it out (below)
        assertThat(mandate("PMC").standing(ends.minusDays(1))).isEqualTo(Standing(StandingKind.IN_TERM, until = ends))
        assertThat(mandate("PMC").standing(ends)).isNull()
    }

    @Test
    fun `a mandate shorter than the reminder period is due an election from its first day`() {
        val short = mandate("BM", from = ends.minusDays(20), to = ends)
        assertThat(short.standing(short.validFrom)).isEqualTo(due(ends))
        assertThat(short.standing(short.validFrom.minusDays(1))).isNull()
    }

    @Test
    fun `a mandate has a standing exactly when it is in force, and 'until' is the first day it no longer is — for every office, with a successor, an end, both or neither`() {
        val later = mandate("BM", from = ends.plusMonths(2), to = ends.plusMonths(14))
        val midTerm = mandate("BM", from = from.plusMonths(8), to = from.plusMonths(20))           // a manager recorded while this one's term runs, never stamped
        val days = generateSequence(from.minusDays(2)) { it.plusDays(1) }.takeWhile { it.isBefore(ends.plusMonths(16)) }.toList()
        for (office in listOf("BM", "MB", "CTL", "CSH")) for (others in listOf(emptyList(), listOf(later), listOf(midTerm), listOf(later, midTerm)))
            for (stamp in listOf(null, from.plusMonths(6), ends.plusMonths(1), ends.plusMonths(5))) {
                val m = mandate(office, succeededAt = stamp)
                val all = others + m
                val case = "$office, stamped $stamp, ${others.size} other(s)"
                for (on in days) {
                    val standing = m.standing(on, others)
                    assertThat(standing != null).describedAs("$case, on $on").isEqualTo(m.inForceOn(on, all))
                    val until = standing?.until ?: continue
                    assertThat(until).describedAs("$case, on $on: until").isAfter(on)
                    assertThat(m.inForceOn(until.minusDays(1), all)).describedAs("$case: the day before $until").isTrue()
                    assertThat(m.inForceOn(until, all)).describedAs("$case: on $until").isFalse()
                }
            }
    }

    @Test
    fun `PM-GOV-004 the read lists every mandate in force in the entrance with its standing — one that names nobody too, and no firm's and none that is over`() {
        val store: MandateStore = mock()
        val manager = mandate("BM")
        val controller = mandate("CTL", from = day("2025-06-01"), to = day("2027-06-01"))
        val gone = mandate("BM", from = day("2022-03-01"), to = day("2024-03-01"), succeededAt = from)
        val firm = mandate("PMC")
        whenever(store.ofEntrance(block)).thenReturn(listOf(gone, manager, controller, firm))
        val read = MandateStanding(store)
        assertThat(read.of(block, ends.plusDays(5))).containsExactly(
            StandingMandate(manager.id, manager.partyId, Role.BM, from, ends, acting(ends)),
            StandingMandate(controller.id, controller.partyId, Role.CTL, controller.validFrom, controller.validTo, Standing(StandingKind.IN_TERM, until = controller.validTo)),
        )
        assertThat(read.of(block, dueFrom).map { it.standing.kind }).containsExactly(StandingKind.ELECTION_DUE, StandingKind.IN_TERM)
        assertThat(read.of(block, day("2025-10-01")).map { it.standing.kind }).containsOnly(StandingKind.IN_TERM)
        assertThat(read.of(UUID.randomUUID(), ends)).isEmpty()
        // a board seat that names nobody still needs its election: listed, with no party
        val hall = UUID.randomUUID()
        val emptySeat = mandate("MB", from = day("2024-01-01"), to = day("2026-01-01"), at = hall).copy(partyId = null)
        whenever(store.ofEntrance(hall)).thenReturn(listOf(emptySeat))
        assertThat(read.of(hall, day("2026-02-01"))).containsExactly(StandingMandate(emptySeat.id, null, Role.MB, emptySeat.validFrom, emptySeat.validTo, acting(day("2026-01-01"))))
    }

    @Test
    fun `PM-SYS-002 the reminder period is the one in force on the day the office is left — not on the day asked about — and where the law holds none, none is invented`() {
        val store: MandateStore = mock()
        val inForceFrom = day(zues.law.constantOn("MANDATE_ELECTION_REMINDER_DAYS", "2026-01-01").inForceFrom)
        val endsJustAfter = mandate("BM", from = inForceFrom.minusYears(1), to = inForceFrom.plusDays(14))
        val endsBefore = mandate("BM", from = inForceFrom.minusYears(2), to = inForceFrom.minusDays(1), at = UUID.randomUUID())
        whenever(store.ofEntrance(block)).thenReturn(listOf(endsJustAfter))
        whenever(store.ofEntrance(endsBefore.entranceId)).thenReturn(listOf(endsBefore))
        val read = MandateStanding(store)
        val asked = inForceFrom.minusDays(10)                                                       // before the law holds any period; inside the reminder period of the first
        assertThat(read.of(block, asked).single().standing).isEqualTo(due(endsJustAfter.validTo))
        assertThat(read.of(endsBefore.entranceId, inForceFrom.minusDays(2)).single().standing).isEqualTo(inTerm)   // its last day: no reminder was in force for it
        assertThat(read.of(endsBefore.entranceId, inForceFrom.minusDays(1)).single().standing.kind).isEqualTo(StandingKind.EXPIRED_ACTING)
    }
}
