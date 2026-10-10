package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Login
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private const val AN_ISSUER = "https://id.example.test/realms/domuvai"

/**
 * The names lists and the retention pass as a caller is given them, with the real policy over roles the test dates and
 * the services mocked — no Spring, no database. Proves who is given each, as at today in Sofia, that every answer is
 * entered and a refusal runs nothing (PM-BOOK-006, PM-BOOK-007, PM-BOOK-010), and what sign-in switched off means.
 */
class BookListsTest {

    private val ownership: OwnershipService = mock()
    private val declarations: DeclarationService = mock()
    private val retention: BookRetentionService = mock()
    private val entrances: EntranceRepository = mock()
    private val aggregates: JdbcAggregateTemplate = mock()
    // 00:30 on 1 June in Sofia; still 31 May in UTC
    private val clock = Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.parse("2026-06-01")

    private data class Dated(val party: UUID, val held: Held, val from: LocalDate, val until: LocalDate?)
    private val entranceId = UUID.randomUUID()
    private val holdings = mutableListOf<Dated>()
    private val asked = mutableListOf<LocalDate>()
    private val policy = Policy(
        listOf(
            RoleSource { who, at, asAt ->
                asked += asAt
                holdings.filter { it.party == who.party && at == entranceId && !asAt.isBefore(it.from) && (it.until == null || asAt.isBefore(it.until)) }.mapTo(mutableSetOf()) { it.held }
            },
        ),
    )

    private fun lists(mode: String = "") =
        BookLists(BookAccessService(mock(), entrances, mock(), mock(), aggregates, policy, clock, mode), ownership, declarations, retention)

    private fun person(subject: String, role: Role? = null, from: LocalDate = today.minusYears(1), until: LocalDate? = null) =
        Asking(Login(AN_ISSUER, subject), UUID.randomUUID()).also { if (role != null) holdings += Dated(it.party!!, Held(role, entranceId, setOf(UUID.randomUUID())), from, until) }

    private val on = LocalDate.parse("2025-01-15")
    private val theOwners = listOf(OwnerView(UUID.randomUUID(), "Иван Петров", "OWN", BigDecimal.ONE))
    private val theOverdue = listOf(OverdueDeclaration(UUID.randomUUID(), "ап. 1", "Мария Георгиева", "OWN", on.minusMonths(1), on.minusDays(10)))
    private val applied = RetentionApplied(today, householdUnlinked = 1, animalPassportsCleared = 2)
    private val written = mutableListOf<BookAccessRow>()

    @BeforeEach
    fun onRecord() {
        whenever(entrances.existsById(entranceId)).thenReturn(true)
        whenever(ownership.ownersAsOf(entranceId, on)).thenReturn(theOwners)
        whenever(declarations.overdue(entranceId, on)).thenReturn(theOverdue)
        whenever(retention.anonymiseDue(entranceId, today)).thenReturn(applied)
        whenever(aggregates.insert(any<BookAccessRow>())).thenAnswer { (it.arguments[0] as BookAccessRow).also { row -> written += row } }
    }

    @Test
    fun `PM-BOOK-006 the owners and the overdue declarations are listed to the manager, the board and the controller — and to no other role`() {
        val expected = mapOf(
            Role.OWN to false, Role.USR to false, Role.OCC to false, Role.BM to true, Role.MB to true, Role.CTL to true,
            Role.CSH to false, Role.PMC_STAFF to false, Role.MUN to false, Role.SYS_ADMIN to false,
        )
        for (role in Role.entries) {
            val who = person("login-$role", role)
            assertThat(lists().owners(entranceId, on, who)).describedAs("$role").isEqualTo(if (expected.getValue(role)) BookAnswer.Served(theOwners) else BookAnswer.Refused("PM-BOOK-006"))
            assertThat(lists().overdue(entranceId, on, who)).describedAs("$role").isEqualTo(if (expected.getValue(role)) BookAnswer.Served(theOverdue) else BookAnswer.Refused("PM-BOOK-006"))
        }
        for (nobody in listOf(person("stranger"), Asking(Login(AN_ISSUER, "untied"), null), Asking(null, null)))
            assertThat(lists().owners(entranceId, on, nobody)).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
    }

    @Test
    fun `PM-BOOK-010 what is past its retention window is anonymised by the manager and the board — the controller, an owner and nobody are refused, and nothing is run`() {
        for (role in listOf(Role.CTL, Role.OWN, Role.CSH, Role.SYS_ADMIN))
            assertThat(lists().anonymiseDue(entranceId, today, person("login-$role", role))).describedAs("$role").isEqualTo(BookAnswer.Refused("PM-BOOK-010"))
        assertThat(lists().anonymiseDue(entranceId, today, Asking(Login(AN_ISSUER, "untied"), null))).isEqualTo(BookAnswer.Refused("PM-BOOK-010"))
        assertThat(lists().anonymiseDue(entranceId, today, Asking(null, null))).isEqualTo(BookAnswer.Refused("PM-BOOK-010"))
        verifyNoInteractions(retention)
        assertThat(written.map { it.kind to it.outcome }).containsOnly("RETENTION" to "REFUSED")
        assertThat(written.map { it.ruleId }).containsOnly("PM-BOOK-010")
        for (role in listOf(Role.BM, Role.MB)) assertThat(lists().anonymiseDue(entranceId, today, person("keeper-$role", role))).isEqualTo(BookAnswer.Served(applied))
    }

    @Test
    fun `PM-BOOK-007 every list given and every refusal is entered — who, which list, of which day, by which rule — the entry before the list`() {
        val manager = person("manager", Role.BM)
        val owner = person("owner", Role.OWN)
        lists().owners(entranceId, on, manager)
        lists().overdue(entranceId, on, owner)
        lists().anonymiseDue(entranceId, today, manager)
        assertThat(written.map { listOf(it.kind, it.outcome, it.actor, it.loginSubject, it.bookDate, it.ruleId, it.purpose) }).containsExactly(
            listOf("OWNERS_LIST", "SERVED", manager.party, "manager", on, "PM-BOOK-006", BookLists.OWNERS_PURPOSE),
            listOf("OVERDUE_LIST", "REFUSED", owner.party, "owner", on, "PM-BOOK-006", BookLists.OVERDUE_PURPOSE),
            listOf("RETENTION", "SERVED", manager.party, "manager", today, "PM-BOOK-010", BookLists.RETENTION_PURPOSE),
        )
        verify(declarations, never()).overdue(any(), any())                                        // the refused list was not drawn
    }

    @Test
    fun `PM-BOOK-007 the entry is written before the list is drawn and before anything is anonymised`() {
        val manager = person("manager", Role.BM)
        lists().owners(entranceId, on, manager)
        inOrder(aggregates, ownership) {
            verify(aggregates).insert(any<BookAccessRow>())
            verify(ownership).ownersAsOf(entranceId, on)
        }
        org.mockito.Mockito.clearInvocations(aggregates)
        lists().anonymiseDue(entranceId, today, manager)
        inOrder(aggregates, retention) {
            verify(aggregates).insert(any<BookAccessRow>())
            verify(retention).anonymiseDue(entranceId, today)
        }
    }

    @Test
    fun `PM-BOOK-006 who is given a list is decided as at today in Sofia — not the day the list is asked for`() {
        val former = person("former", Role.BM, from = LocalDate.parse("2024-01-01"), until = LocalDate.parse("2026-01-01"))
        assertThat(lists().owners(entranceId, on, former)).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))   // the manager on 2025-01-15, not today
        val newly = person("newly", Role.BM, from = today)                                                 // from 1 June: today in Sofia, tomorrow in UTC
        assertThat(lists().overdue(entranceId, on, newly)).isEqualTo(BookAnswer.Served(theOverdue))
        assertThat(asked).containsOnly(today)
    }

    @Test
    fun `PM-BOOK-006 a refused caller learns nothing of the entrance — the same refusal for one nobody registered, and no entry where there is none to enter it under`() {
        val unregistered = UUID.randomUUID()
        assertThat(lists().owners(unregistered, on, person("owner", Role.OWN))).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        assertThat(lists().anonymiseDue(unregistered, today, person("manager", Role.BM))).isEqualTo(BookAnswer.Refused("PM-BOOK-010"))   // the manager of another entrance
        assertThat(written).isEmpty()
        verifyNoInteractions(ownership, retention)
    }

    @Test
    fun `with sign-in switched off nobody is asking and the lists stay open, with nothing entered — but only then, and only for nobody`() {
        val open = lists(mode = " OFF ")
        assertThat(open.owners(entranceId, on, Asking(null, null))).isEqualTo(BookAnswer.Served(theOwners))
        assertThat(open.overdue(entranceId, on, Asking(null, null))).isEqualTo(BookAnswer.Served(theOverdue))
        assertThat(open.anonymiseDue(entranceId, today, Asking(null, null))).isEqualTo(BookAnswer.Served(applied))
        assertThat(written).isEmpty()
        // somebody who is asking is decided on, sign-in off or not; and with sign-in configured, nobody is refused
        assertThat(open.owners(entranceId, on, person("owner", Role.OWN))).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        assertThat(open.owners(entranceId, on, Asking(null, UUID.randomUUID()))).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        assertThat(lists().owners(entranceId, on, Asking(null, null))).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        // open is said in so many words: a setting that is missing, empty or anything else is not open
        for (notOff in listOf("", " ", "of", "open", "true", "0ff"))
            assertThat(lists(mode = notOff).owners(entranceId, on, Asking(null, null))).describedAs("'$notOff'").isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
    }

    @Test
    fun `the book and its log are never read under a standing purpose — whatever asks for it, and sign-in off or not`() {
        for (kind in listOf(BookAccessKind.BOOK_READ, BookAccessKind.LOG_EXPORT)) for (mode in listOf("", "off"))
            org.assertj.core.api.Assertions.assertThatThrownBy {
                BookAccessService(mock(), entrances, mock(), mock(), aggregates, policy, clock, mode)
                    .standing(entranceId, Asking(null, null), kind, zues.app.policy.Action.READ_BOOK, on, "x") { "the book" }
            }.describedAs("$kind $mode").isInstanceOf(IllegalArgumentException::class.java)
        assertThat(written).isEmpty()
    }
}
