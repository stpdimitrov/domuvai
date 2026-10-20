package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import zues.app.policy.Role
import java.time.LocalDate
import java.util.UUID

/** Who succeeded a party in an office, as at a date — the one reading of "successor" (`Mandate.succeeds`), followed through the record. */
class SuccessionTest {

    private val store: MandateStore = mock()
    private val succession = Succession(store)
    private val block = UUID.randomUUID()
    private fun day(iso: String) = LocalDate.parse(iso)
    private val ivan = UUID.randomUUID(); private val maria = UUID.randomUUID(); private val petar = UUID.randomUUID()
    private val elena = UUID.randomUUID(); private val georgi = UUID.randomUUID()

    private fun mandate(body: String, party: UUID?, from: String, to: String, ended: String? = null, at: UUID = block) =
        Mandate(UUID.randomUUID(), at, body, party, day(from), day(to), ended?.let(::day))

    private fun record(vararg mandates: Mandate) = whenever(store.ofEntrance(block)).thenReturn(mandates.toList())

    @Test
    fun `PM-GOV-004 a manager is succeeded by the manager elected after them — from that one's first day, and not before`() {
        val first = mandate("BM", ivan, "2022-03-01", "2024-03-01")
        val second = mandate("BM", maria, "2024-05-10", "2026-05-10")
        record(first, second)
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2024-04-01"))).isEmpty()      // acting past term: still his
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2024-05-10"))).containsExactly(Successor(maria, Role.BM, second.id, day("2024-05-10")))
        assertThat(succession.hasSucceeded(maria, ivan, block, Role.BM, day("2025-01-01"))).isTrue()
        assertThat(succession.hasSucceeded(ivan, maria, block, Role.BM, day("2025-01-01"))).isFalse()   // never the other way
        assertThat(succession.successorsOf(ivan, block, Role.MB, day("2025-01-01"))).isEmpty()      // he held no seat on the board: nobody took one from him
    }

    @Test
    fun `PM-GOV-004 the office is followed through the record — a successor's successor has taken the place too, and the one who left it has not`() {
        val first = mandate("BM", ivan, "2020-03-01", "2022-03-01")
        val second = mandate("BM", maria, "2022-03-01", "2024-03-01")
        val third = mandate("BM", petar, "2024-03-01", "2026-03-01")
        record(first, second, third)
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2023-01-01")).map { it.partyId }).containsExactly(maria)
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2025-01-01")).map { it.partyId }).containsExactly(petar)
        assertThat(succession.successorsOf(maria, block, Role.BM, day("2025-01-01")).map { it.partyId }).containsExactly(petar)
    }

    @Test
    fun `PM-GOV-004 the place passes through a manager — a member who followed the manager who followed a board member has taken that member's place, though not directly`() {
        // A sits on the board to 2026-01-01; B is elected sole manager from 2025-01-01 and ends A's seat that day; C, a
        // board member from 2025-06-01, ends B. C began inside A's own term, so C alone would succeed nobody of A's —
        // but the place went from A to B to C.
        val a = mandate("MB", ivan, "2024-01-01", "2026-01-01", ended = "2025-01-01")
        val b = mandate("BM", maria, "2025-01-01", "2027-01-01", ended = "2025-06-01")
        val c = mandate("MB", petar, "2025-06-01", "2027-06-01")
        record(a, b, c)
        assertThat(c.succeeds(a)).isFalse()
        assertThat(succession.successorsOf(ivan, block, Role.MB, day("2025-03-01")).map { it.partyId }).containsExactly(maria)
        assertThat(succession.successorsOf(ivan, block, Role.MB, day("2025-08-01")).map { it.partyId }).containsExactly(petar)
    }

    @Test
    fun `PM-GOV-004 a board member is succeeded only by a member elected on or after the day their term ended — a colleague by-elected meanwhile succeeds nobody`() {
        // the audit's case: A serves 2024-01-01 → 2026-01-01; B is by-elected 2025-03-01 → 2027-03-01
        val a = mandate("MB", ivan, "2024-01-01", "2026-01-01")
        val b = mandate("MB", maria, "2025-03-01", "2027-03-01")
        record(a, b)
        assertThat(succession.successorsOf(ivan, block, Role.MB, day("2026-06-01"))).isEmpty()
        assertThat(succession.hasSucceeded(maria, ivan, block, Role.MB, day("2026-06-01"))).isFalse()
        val c = mandate("MB", petar, "2026-01-01", "2028-01-01")
        record(a, b, c)
        assertThat(succession.successorsOf(ivan, block, Role.MB, day("2026-06-01"))).containsExactly(Successor(petar, Role.MB, c.id, day("2026-01-01")))
    }

    @Test
    fun `PM-GOV-004 a board follows a manager, and a manager a board — each successor named with the role it holds`() {
        val manager = mandate("BM", ivan, "2022-03-01", "2024-03-01")
        val m1 = mandate("MB", maria, "2024-03-01", "2026-03-01")
        val m2 = mandate("MB", petar, "2024-03-01", "2026-03-01")
        record(manager, m1, m2)
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2025-01-01"))).containsExactlyInAnyOrder(
            Successor(maria, Role.MB, m1.id, day("2024-03-01")), Successor(petar, Role.MB, m2.id, day("2024-03-01")),
        )
        val next = mandate("BM", elena, "2026-03-01", "2028-03-01")
        record(manager, m1, m2, next)
        assertThat(succession.successorsOf(maria, block, Role.MB, day("2026-06-01")).map { it.partyId to it.role }).containsExactly(elena to Role.BM)
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2026-06-01")).map { it.partyId }).containsExactly(elena)
    }

    @Test
    fun `a party re-elected to the office is nobody's successor — not their own`() {
        val first = mandate("BM", ivan, "2020-03-01", "2022-03-01")
        val again = mandate("BM", ivan, "2022-03-01", "2024-03-01")
        record(first, again)
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2023-01-01"))).isEmpty()
        val next = mandate("BM", maria, "2024-03-01", "2026-03-01")
        record(first, again, next)
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2025-01-01")).map { it.partyId }).containsExactly(maria)
    }

    @Test
    fun `nobody succeeds in another office, another entrance, a role the party never held, or on a day before anybody followed`() {
        val manager = mandate("BM", ivan, "2022-03-01", "2024-03-01")
        val controller = mandate("CTL", georgi, "2024-03-01", "2026-03-01")                         // a controller follows nobody
        val nextDoor = mandate("BM", elena, "2024-03-01", "2026-03-01", at = UUID.randomUUID())
        val nobodyNamed = mandate("BM", null, "2024-03-01", "2026-03-01")
        record(manager, controller, nextDoor, nobodyNamed)
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2025-01-01"))).isEmpty()     // the office passed to nobody named
        assertThat(succession.successorsOf(ivan, block, Role.MB, day("2025-01-01"))).isEmpty()     // he was never on the board
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2023-01-01"))).isEmpty()
        assertThat(succession.successorsOf(UUID.randomUUID(), block, Role.BM, day("2025-01-01"))).isEmpty()
    }

    @Test
    fun `a successor whose own mandate has ended since is no longer the one who holds the place — the question is as at a date`() {
        val first = mandate("BM", ivan, "2020-03-01", "2022-03-01")
        val resigned = mandate("BM", maria, "2022-03-01", "2024-03-01", ended = "2023-01-01")      // resigned, nobody elected yet
        record(first, resigned)
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2022-06-01")).map { it.partyId }).containsExactly(maria)
        assertThat(succession.successorsOf(ivan, block, Role.BM, day("2023-06-01"))).isEmpty()     // the office is empty that day
    }
}
