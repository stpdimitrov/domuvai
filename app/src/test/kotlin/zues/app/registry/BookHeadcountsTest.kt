package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Login
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private const val AN_ISSUER = "https://id.example.test/realms/domuvai"

/**
 * The declared counts as a reader is given them, with the real policy and the real book access over a mocked [Units] —
 * no Spring, no database. Proves who is given them, that every answer that gives or refuses is entered, and what a
 * period with no run and sign-in switched off mean (PM-BOOK-006, PM-BOOK-007, PM-SEC-004).
 */
class BookHeadcountsTest {

    private val units: Units = mock()
    private val entrances: EntranceRepository = mock()
    private val aggregates: JdbcAggregateTemplate = mock()
    // 00:30 on 1 June in Sofia; still 31 May in UTC
    private val clock = Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.parse("2026-06-01")

    private val entranceId = UUID.randomUUID()
    private val holdings = mutableMapOf<UUID, Held>()
    private val policy = Policy(listOf(RoleSource { who, at, _ -> setOfNotNull(holdings[who.party]?.takeIf { at == entranceId }) }))

    private fun headcounts(mode: String = "") =
        BookHeadcountsAdapter(units, BookAccessService(mock(), entrances, mock(), mock(), aggregates, policy, clock, mode))

    private fun person(subject: String, role: Role) =
        Asking(Login(AN_ISSUER, subject), UUID.randomUUID()).also { holdings[it.party!!] = Held(role, entranceId, setOf(UUID.randomUUID())) }

    private val on = LocalDate.parse("2026-05-01")
    private val flat = UnitForCharging(UUID.randomUUID(), "ап. 1", "100.0000", separateEntrance = false, occupants = 3, childrenUnder6 = 1, animals = 2)
    private val theCounts = listOf(DeclaredHeadcount(flat.unitId, "ап. 1", 3, 1))
    private val written = mutableListOf<BookAccessRow>()

    @BeforeEach
    fun onRecord() {
        whenever(entrances.existsById(entranceId)).thenReturn(true)
        whenever(units.forEntrance(entranceId, on)).thenReturn(listOf(flat))
        whenever(aggregates.insert(any<BookAccessRow>())).thenAnswer { (it.arguments[0] as BookAccessRow).also { row -> written += row } }
    }

    @Test
    fun `PM-BOOK-006 the declared counts go to the manager, the board and the controller — and to no other role`() {
        val readers = setOf(Role.BM, Role.MB, Role.CTL)
        for (role in Role.entries) {
            val answer = headcounts().declared(entranceId, person("login-$role", role), on)
            assertThat(answer).describedAs("$role").isEqualTo(if (role in readers) BookAnswer.Served(theCounts) else BookAnswer.Refused("PM-BOOK-006"))
        }
        for (nobody in listOf(Asking(Login(AN_ISSUER, "untied"), null), Asking(null, null))) {
            assertThat(headcounts().declared(entranceId, nobody, on)).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        }
    }

    @Test
    fun `PM-BOOK-007 a served read is entered with its actor, the standing purpose and the run's date, before the counts are read`() {
        val manager = person("login-manager", Role.BM)

        headcounts().declared(entranceId, manager, on)

        val entry = written.single()
        assertThat(listOf(entry.entranceId, entry.actor, entry.purpose, entry.kind, entry.bookDate, entry.outcome, entry.ruleId, entry.loginSubject))
            .containsExactly(entranceId, manager.party, BookHeadcountsAdapter.PURPOSE, "HEADCOUNT_READ", on, "SERVED", "PM-BOOK-006", "login-manager")
        assertThat(entry.at).isEqualTo(clock.instant())
        inOrder(aggregates, units) {
            verify(aggregates).insert(any<BookAccessRow>())
            verify(units).forEntrance(entranceId, on)
        }
    }

    @Test
    fun `PM-SEC-004 a refusal is entered too, with no counts read`() {
        val owner = person("login-owner", Role.OWN)

        assertThat(headcounts().declared(entranceId, owner, on)).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))

        val entry = written.single()
        assertThat(listOf(entry.actor, entry.kind, entry.bookDate, entry.outcome, entry.ruleId)).containsExactly(owner.party, "HEADCOUNT_READ", on, "REFUSED", "PM-BOOK-006")
        verify(units, never()).forEntrance(any(), any())
    }

    @Test
    fun `PM-BOOK-007 with no run to date the read a refusal is entered as of today in Sofia, and an allowed reader is given nothing and enters nothing`() {
        val owner = person("login-owner", Role.OWN)
        assertThat(headcounts().declared(entranceId, owner, null)).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        assertThat(written.single().bookDate).isEqualTo(today)
        written.clear()

        assertThat(headcounts().declared(entranceId, person("login-manager", Role.BM), null)).isEqualTo(BookAnswer.Served(emptyList<DeclaredHeadcount>()))

        assertThat(written).isEmpty()
        verify(units, never()).forEntrance(any(), any())
    }

    @Test
    fun `PM-BOOK-007 with sign-in switched off the counts are given to nobody and entered so, and somebody signed in is decided all the same`() {
        assertThat(headcounts("off").declared(entranceId, Asking(null, null), on)).isEqualTo(BookAnswer.Served(theCounts))
        val entry = written.single()
        assertThat(listOf(entry.actor, entry.kind, entry.bookDate, entry.outcome)).containsExactly(null, "HEADCOUNT_READ", on, "OPEN")
        written.clear()

        assertThat(headcounts("off").declared(entranceId, Asking(null, null), null)).isEqualTo(BookAnswer.Served(emptyList<DeclaredHeadcount>()))
        assertThat(written).isEmpty()

        assertThat(headcounts("off").declared(entranceId, person("login-owner", Role.OWN), on)).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        assertThat(headcounts("off").declared(entranceId, person("login-owner", Role.OWN), null)).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        assertThat(written.map { it.outcome }).containsExactly("REFUSED", "REFUSED")
    }
}
