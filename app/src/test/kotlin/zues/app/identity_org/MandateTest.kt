package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import zues.app.policy.Role
import java.time.LocalDate
import java.util.UUID

/** When a mandate counts (PM-GOV-004): from its first day, up to its end — and past it, for the manager and the board, until a successor. */
class MandateTest {

    private val from = LocalDate.of(2024, 3, 1)
    private val to = LocalDate.of(2026, 3, 1)

    private val block = UUID.randomUUID()

    private fun mandate(body: String, succeededAt: LocalDate? = null, from: LocalDate = this.from, to: LocalDate = this.to, at: UUID = block) =
        Mandate(UUID.randomUUID(), at, body, UUID.randomUUID(), from, to, succeededAt)

    private fun Mandate.inForceOn(date: LocalDate) = inForceOn(date, listOf(this))

    @Test
    fun `PM-GOV-004 a mandate counts from its first day and through its last, never before`() {
        for (body in listOf("BM", "MB", "CTL", "CSH")) {
            val m = mandate(body)
            assertThat(m.inForceOn(from.minusDays(1))).describedAs(body).isFalse()
            assertThat(m.inForceOn(from)).describedAs(body).isTrue()
            assertThat(m.inForceOn(to.minusDays(1))).describedAs(body).isTrue()
        }
    }

    @Test
    fun `PM-GOV-004 the manager and the board continue in office past the mandate's end until a successor takes over`() {
        for (body in listOf("BM", "MB")) {
            assertThat(mandate(body).inForceOn(to)).describedAs(body).isTrue()
            assertThat(mandate(body).inForceOn(to.plusYears(3))).describedAs(body).isTrue()
            val succeeded = mandate(body, succeededAt = to.plusMonths(2))
            assertThat(succeeded.inForceOn(to.plusMonths(2).minusDays(1))).describedAs(body).isTrue()
            assertThat(succeeded.inForceOn(to.plusMonths(2))).describedAs(body).isFalse()
            assertThat(succeeded.inForceOn(to.plusYears(3))).describedAs(body).isFalse()
        }
    }

    @Test
    fun `the controller and the cashier do not continue — the rule names the board and the manager`() {
        for (body in listOf("CTL", "CSH")) {
            assertThat(mandate(body).inForceOn(to)).describedAs(body).isFalse()
            assertThat(mandate(body).inForceOn(to.plusYears(1))).describedAs(body).isFalse()
        }
    }

    @Test
    fun `a successor recorded before the mandate's end ends it that day, whatever the office`() {
        val early = from.plusMonths(6)
        for (body in listOf("BM", "MB", "CTL", "CSH")) {
            val m = mandate(body, succeededAt = early)
            assertThat(m.inForceOn(early.minusDays(1))).describedAs(body).isTrue()
            assertThat(m.inForceOn(early)).describedAs(body).isFalse()
            assertThat(m.inForceOn(to.minusDays(1))).describedAs(body).isFalse()
        }
    }

    @Test
    fun `PM-GOV-004 a later executive mandate of the entrance is the successor, though nobody recorded it on the old one`() {
        for (old in listOf("BM", "MB")) for (next in listOf("BM", "MB", "PMC")) {
            val incumbent = mandate(old)
            val successor = mandate(next, from = to.plusMonths(2), to = to.plusMonths(26))     // a gap of two months: the incumbent fills it
            val all = listOf(incumbent, successor)
            assertThat(incumbent.inForceOn(to.plusMonths(2).minusDays(1), all)).describedAs("$old then $next").isTrue()
            assertThat(incumbent.inForceOn(to.plusMonths(2), all)).describedAs("$old then $next").isFalse()
            assertThat(incumbent.inForceOn(to.plusYears(5), all)).describedAs("$old then $next").isFalse()
        }
    }

    @Test
    fun `PM-GOV-004 only a successor ends the stay — not a controller's or a cashier's mandate, another entrance's, an earlier one or one not yet started`() {
        val incumbent = mandate("BM")
        val later = to.plusMonths(2)
        val notSuccessors = listOf(
            mandate("CTL", from = later, to = later.plusYears(2)), mandate("CSH", from = later, to = later.plusYears(2)),
            mandate("BM", from = later, to = later.plusYears(2), at = UUID.randomUUID()),
            mandate("BM", from = from.minusYears(2), to = from), mandate("MB"),               // before it, and beside it
            mandate("BM", from = to.plusYears(1), to = to.plusYears(3)),                       // not yet started on the day asked about
        )
        assertThat(incumbent.inForceOn(to.plusMonths(6), notSuccessors + incumbent)).isTrue()
    }

    @Test
    fun `PM-GOV-004 two managers on real dates with no end recorded — the later one is the manager from its first day, and they are never in force together`() {
        val a = mandate("BM", from = day("2025-01-01"), to = day("2027-01-01"))
        val b = mandate("BM", from = day("2026-03-01"), to = day("2028-03-01"))
        val both = listOf(a, b)
        assertThat(a.inForceOn(day("2026-02-28"), both)).isTrue()
        assertThat(b.inForceOn(day("2026-02-28"), both)).isFalse()
        assertThat(a.inForceOn(day("2026-03-01"), both)).isFalse()
        assertThat(b.inForceOn(day("2026-03-01"), both)).isTrue()
        assertThat(a.inForceOn(day("2026-12-31"), both)).isFalse()                                  // a day inside A's own dates
        assertThat(a.inForceOn(day("2027-01-01"), both)).isFalse()
        assertThat(b.inForceOn(day("2028-03-01"), both)).isTrue()                                   // B continues past its term, as any manager
        generateSequence(day("2024-12-31")) { it.plusDays(1) }.takeWhile { it.isBefore(day("2028-06-01")) }.forEach { date ->
            assertThat(both.count { it.inForceOn(date, both) }).describedAs("$date").isEqualTo(if (date.isBefore(a.validFrom)) 0 else 1)
        }
        // what ends A is B's own first day: a row stamped that day reads the same on every day
        val stamped = a.copy(succeededAt = b.validFrom)
        for (date in listOf("2025-06-01", "2026-02-28", "2026-03-01", "2026-12-31", "2027-01-01")) assertThat(a.inForceOn(day(date), both)).describedAs(date).isEqualTo(stamped.inForceOn(day(date), listOf(stamped, b)))
        // the one pair this does not reach: two beginning the same day succeed neither. The recorder refuses that pair; only SQL can write it
        val sameDay = mandate("BM", from = a.validFrom, to = a.validTo)
        assertThat(listOf(a, sameDay).count { it.inForceOn(day("2025-06-01"), listOf(a, sameDay)) }).isEqualTo(2)
    }

    @Test
    fun `PM-GOV-004 inside the term a later manager ends a manager or a board member, and a later board member ends a manager — on the successor's first day`() {
        val first = day("2025-01-01"); val last = day("2027-01-01"); val elected = day("2026-03-01")
        for ((old, next) in listOf("BM" to "BM", "MB" to "BM", "BM" to "MB")) {
            val incumbent = mandate(old, from = first, to = last)
            val successor = mandate(next, from = elected, to = elected.plusYears(2))
            val all = listOf(incumbent, successor)
            assertThat(incumbent.inForceOn(elected.minusDays(1), all)).describedAs("$old then $next").isTrue()
            assertThat(incumbent.inForceOn(elected, all)).describedAs("$old then $next").isFalse()
            assertThat(incumbent.endsOnRecord(all)).describedAs("$old then $next").isEqualTo(elected)
            // another entrance's is nothing to this one, and neither is a successor who has not begun
            val nextDoor = mandate(next, from = elected, to = elected.plusYears(2), at = UUID.randomUUID())
            assertThat(incumbent.inForceOn(elected, listOf(incumbent, nextDoor))).describedAs("$old then $next, next door").isTrue()
        }
    }

    @Test
    fun `PM-GOV-004 a by-election inside a term ends nobody — both members sit on every day of the overlap, and a board elected whole mid-term sits beside the old one until an end is recorded`() {
        val a = mandate("MB", from = day("2025-01-01"), to = day("2027-01-01"))
        val byElected = mandate("MB", from = day("2026-03-01"), to = day("2028-03-01"))
        val board = listOf(a, byElected)
        generateSequence(day("2026-03-01")) { it.plusDays(1) }.takeWhile { it.isBefore(day("2027-01-01")) }.forEach { date ->
            assertThat(board.count { it.inForceOn(date, board) }).describedAs("$date").isEqualTo(2)
        }
        // the seats are not on the table, so a whole new board reads as by-elections: nothing ends the old one but a recorded end
        val newBoard = List(3) { mandate("MB", from = day("2026-03-01"), to = day("2028-03-01")) }
        assertThat(a.inForceOn(day("2026-03-01"), newBoard + a)).isTrue()
        val ended = a.copy(succeededAt = day("2026-03-01"))
        assertThat(ended.inForceOn(day("2026-03-01"), newBoard + ended)).isFalse()
    }

    @Test
    fun `PM-GOV-004 a firm's mandate beginning inside a manager's term ends the manager on its first day, and a board member's only as another member would`() {
        val manager = mandate("BM", from = day("2025-01-01"), to = day("2027-01-01"))
        val member = mandate("MB", from = day("2025-01-01"), to = day("2027-01-01"))
        val firm = mandate("PMC", from = day("2026-03-01"), to = day("2028-03-01"))
        assertThat(manager.inForceOn(day("2026-02-28"), listOf(manager, firm))).isTrue()
        assertThat(manager.inForceOn(day("2026-03-01"), listOf(manager, firm))).isFalse()
        assertThat(member.inForceOn(day("2026-03-01"), listOf(member, firm))).isTrue()
        // the firm holds no role, so from that day nobody is the manager until an end or a successor is on record (asked on #387)
        val entrance = listOf(manager, firm)
        assertThat(entrance.filter { it.inForceOn(day("2026-03-01"), entrance) }.mapNotNull { it.role }).isEmpty()
    }

    @Test
    fun `PM-GOV-004 a controller or a cashier is cut short by nobody inside their term — only their dates and a recorded end end them`() {
        for (office in listOf("CTL", "CSH")) {
            val sitting = mandate(office, from = day("2025-01-01"), to = day("2027-01-01"))
            val others = listOf("BM", "MB", "CTL", "CSH").map { mandate(it, from = day("2026-03-01"), to = day("2028-03-01")) }
            assertThat(sitting.inForceOn(day("2026-12-31"), others + sitting)).describedAs(office).isTrue()
            assertThat(sitting.inForceOn(day("2027-01-01"), others + sitting)).describedAs(office).isFalse()
        }
    }

    private fun day(iso: String) = LocalDate.parse(iso)

    @Test
    fun `PM-GOV-004 a board member whose dates ran out keeps the office though a colleague was by-elected meanwhile — nobody was elected to succeed them`() {
        // The audit's case: A serves 2024-01-01 → 2026-01-01; B is by-elected 2025-03-01 → 2027-03-01.
        val a = mandate("MB", from = day("2024-01-01"), to = day("2026-01-01"))
        val b = mandate("MB", from = day("2025-03-01"), to = day("2027-03-01"))
        val board = listOf(a, b)
        assertThat(b.succeeds(a)).isFalse()
        for (date in listOf("2025-12-31", "2026-01-01", "2026-06-01", "2027-02-28", "2030-01-01")) assertThat(a.inForceOn(day(date), board)).describedAs(date).isTrue()
        // … until somebody is: a member elected on A's own last day or after it, a sole manager, or an end recorded.
        for (successor in listOf(mandate("MB", from = day("2026-01-01"), to = day("2028-01-01")), mandate("MB", from = day("2026-04-01"), to = day("2028-04-01")), mandate("BM", from = day("2026-04-01"), to = day("2028-04-01")))) {
            assertThat(successor.succeeds(a)).isTrue()
            assertThat(a.inForceOn(successor.validFrom.minusDays(1), board + successor)).isTrue()
            assertThat(a.inForceOn(successor.validFrom, board + successor)).isFalse()
        }
        assertThat(a.copy(succeededAt = day("2026-02-01")).inForceOn(day("2026-02-01"), board)).isFalse()
        // a member elected the day before A's last day began inside A's term, and succeeds nobody
        assertThat(mandate("MB", from = day("2025-12-31"), to = day("2027-12-31")).succeeds(a)).isFalse()
    }

    @Test
    fun `PM-GOV-004 who succeeds whom, office by office — a manager anybody before, a board member a manager and a member whose dates ran out`() {
        val earlierFrom = day("2024-01-01"); val earlierTo = day("2026-01-01")
        fun earlier(body: String) = mandate(body, from = earlierFrom, to = earlierTo)
        fun later(body: String, begins: String, at: UUID = block) = mandate(body, from = day(begins), to = day(begins).plusYears(1), at = at)
        // within the earlier one's term · on its last day · after it
        val expected = mapOf(
            ("BM" to "BM") to listOf(true, true, true), ("MB" to "BM") to listOf(true, true, true), ("BM" to "MB") to listOf(true, true, true),
            ("MB" to "MB") to listOf(false, true, true), ("MB" to "PMC") to listOf(false, true, true), ("BM" to "PMC") to listOf(true, true, true),
        )
        for ((pair, answers) in expected) for ((begins, answer) in listOf("2025-03-01", "2026-01-01", "2026-03-01").zip(answers))
            assertThat(later(pair.second, begins).succeeds(earlier(pair.first))).describedAs("${pair.first} then ${pair.second} from $begins").isEqualTo(answer)
        // two members elected the same day succeed neither each other; a firm's own mandate is ended by a manager like any other
        assertThat(later("MB", "2024-01-01").succeeds(earlier("MB"))).isFalse()
        assertThat(later("BM", "2025-03-01").succeeds(earlier("PMC"))).isTrue()
        assertThat(later("MB", "2025-03-01").succeeds(earlier("PMC"))).isFalse()
        // a successor that has itself ended since, or names no party, is a successor still: the office passed the day it began
        val incumbent = earlier("MB")
        val cameAndWent = later("BM", "2026-03-01").copy(succeededAt = day("2026-03-01"))
        val nobodyNamed = later("BM", "2026-03-01").copy(partyId = null)
        for (successor in listOf(cameAndWent, nobodyNamed)) {
            assertThat(successor.succeeds(incumbent)).isTrue()
            assertThat(incumbent.inForceOn(day("2026-03-01"), listOf(incumbent, successor))).isFalse()
        }
        // never: the same day or before, another entrance, an office that is not executive on either side
        assertThat(later("BM", "2024-01-01").succeeds(earlier("BM"))).isFalse()
        assertThat(later("BM", "2023-01-01").succeeds(earlier("BM"))).isFalse()
        assertThat(later("BM", "2026-03-01", at = UUID.randomUUID()).succeeds(earlier("BM"))).isFalse()
        for (office in listOf("CTL", "CSH")) {
            assertThat(later(office, "2026-03-01").succeeds(earlier("BM"))).isFalse()
            assertThat(later("BM", "2026-03-01").succeeds(earlier(office))).isFalse()
            assertThat(later("MB", "2026-03-01").succeeds(earlier(office))).isFalse()
            assertThat(later("PMC", "2026-03-01").succeeds(earlier(office))).isFalse()
        }
        for (executive in listOf("BM", "MB", "PMC")) assertThat(later("CTL", "2026-03-01").succeeds(earlier(executive))).isFalse()
        assertThat(later("CTL", "2026-03-01").succeeds(earlier("CSH"))).isFalse()
        assertThat(later("CSH", "2026-03-01").succeeds(earlier("CTL"))).isFalse()
        assertThat(later("CSH", "2026-03-01").succeeds(earlier("CSH"))).isFalse()                  // the cashier: nobody decided
    }

    @Test
    fun `an office is its role, and the firm's own mandate is nobody's role`() {
        assertThat(listOf("BM", "MB", "CTL", "CSH").map { mandate(it).role }).containsExactly(Role.BM, Role.MB, Role.CTL, Role.CSH)
        assertThat(mandate("PMC").role).isNull()
        assertThat(mandate("PMC").inForceOn(to)).isFalse()                              // and it does not continue either
    }

    @Test
    fun `PM-GOV-007 a controller succeeds an earlier one from the day that one's term ends — a control board is several seats, as the board is`() {
        val first = mandate("CTL", from = day("2024-01-01"), to = day("2026-01-01"))
        assertThat(mandate("CTL", from = day("2026-01-01"), to = day("2028-01-01")).succeeds(first)).isTrue()
        assertThat(mandate("CTL", from = day("2026-04-01"), to = day("2028-04-01")).succeeds(first)).isTrue()   // after a gap
        assertThat(mandate("CTL", from = day("2025-03-01"), to = day("2027-03-01")).succeeds(first)).isFalse()  // elected while the first sat: a seat beside them
        assertThat(mandate("CTL", from = day("2024-01-01"), to = day("2026-01-01")).succeeds(first)).isFalse()  // the same day
        assertThat(mandate("CTL", from = day("2023-01-01"), to = day("2025-01-01")).succeeds(first)).isFalse()  // before
        assertThat(mandate("CTL", from = day("2026-01-01"), to = day("2028-01-01"), at = UUID.randomUUID()).succeeds(first)).isFalse()
        assertThat(first.succeeds(mandate("BM", from = day("2022-01-01"), to = day("2024-01-01")))).isFalse()
        // and nothing about continuing changes: a controller is not in force past their dates, successor or none
        assertThat(first.inForceOn(day("2026-01-01"), listOf(first))).isFalse()
        assertThat(first.inForceOn(day("2025-12-31"), listOf(first, mandate("CTL", from = day("2025-03-01"), to = day("2027-03-01"))))).isTrue()   // a later controller ends nobody within their dates
    }
}
