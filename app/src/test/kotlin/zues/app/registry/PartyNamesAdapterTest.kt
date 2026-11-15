package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Decision
import zues.app.policy.Login
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.app.policy.Role
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The names beside parties' ids, with the policy and the repositories mocked — no Spring, no database. Proves the
 * adapter asks the book's own decision and nothing of its own, as at today in Sofia; that served names are entered
 * and withheld ones are not (Q5 on #300); and that the answer is the name only.
 */
class PartyNamesAdapterTest {

    private val entrances: EntranceRepository = mock()
    private val parties: PartyRepository = mock()
    private val aggregates: JdbcAggregateTemplate = mock()
    private val policy: Policy = mock()
    // 00:30 on 1 June in Sofia; still 31 May in UTC
    private val clock = Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.parse("2026-06-01")
    private fun adapter(mode: String = "") = PartyNamesAdapter(BookAccessService(mock(), entrances, parties, mock(), aggregates, policy, clock, mode))

    private val entranceId = UUID.randomUUID()
    private val ivan = Party(UUID.randomUUID(), "Иван Петров", "EGN", "7501020018")
    private val reader = Asking(Login("https://id.example.test/realms/domuvai", "login-controller"), UUID.randomUUID())
    private val purpose = "the entrance's mandates, named"
    private val written = mutableListOf<BookAccessRow>()

    @BeforeEach
    fun registered() {
        whenever(entrances.existsById(entranceId)).thenReturn(true)
        whenever(parties.findAllById(any<Iterable<UUID>>())).thenReturn(listOf(ivan))
        whenever(aggregates.insert(any<BookAccessRow>())).thenAnswer { (it.arguments[0] as BookAccessRow).also { row -> written += row } }
    }

    private fun decides(allowed: Boolean) =
        whenever(policy.decide(any(), any(), any(), any())).thenReturn(Decision(allowed, "PM-BOOK-006", Role.CTL.takeIf { allowed }, Policy.MATRIX_VERSION))

    @Test
    fun `PM-SEC-001 the names follow the book's readers — the adapter asks READ_BOOK and nothing of its own`() {
        decides(true)
        assertThat(adapter().named(entranceId, reader, listOf(ivan.id), purpose)).isEqualTo(mapOf(ivan.id to "Иван Петров"))
        val actions = argumentCaptor<Action>()
        verify(policy).decide(org.mockito.kotlin.eq(reader), actions.capture(), org.mockito.kotlin.eq(Resource.OfEntrance(entranceId)), org.mockito.kotlin.eq(today))
        assertThat(actions.allValues).containsExactly(Action.READ_BOOK)                   // once, the book's own — not the read's it sits in
    }

    @Test
    fun `PM-BOOK-007 names given are entered with actor, login, purpose, the Sofia date and the time — names withheld enter nothing`() {
        decides(true)
        adapter().named(entranceId, reader, listOf(ivan.id), purpose)
        assertThat(written).singleElement().satisfies({ row ->
            assertThat(row.kind).isEqualTo("PARTY_NAMES")
            assertThat(row.outcome).isEqualTo("SERVED")
            assertThat(row.actor).isEqualTo(reader.party)
            assertThat(row.loginSubject).isEqualTo("login-controller")
            assertThat(row.purpose).isEqualTo(purpose)
            assertThat(row.bookDate).isEqualTo(today)                                     // the Sofia day, not the UTC one
            assertThat(row.at).isEqualTo(clock.instant())
        })
        written.clear()
        decides(false)
        assertThat(adapter().named(entranceId, reader, listOf(ivan.id), purpose)).isNull()
        assertThat(written).isEmpty()
        verify(parties).findAllById(any<Iterable<UUID>>())                                // only the served read looked a name up
    }

    @Test
    fun `PM-BOOK-007 an allowance to a login tied to no party, or an entrance not registered, gives no names and enters nothing`() {
        decides(true)
        assertThat(adapter().named(entranceId, Asking(reader.login, null), listOf(ivan.id), purpose)).isNull()
        assertThat(adapter().named(UUID.randomUUID(), reader, listOf(ivan.id), purpose)).isNull()
        verify(aggregates, never()).insert(any<BookAccessRow>())
    }

    @Test
    fun `PM-BOOK-011 a name beside an id is the name only — the identity number the registry keeps is not in the answer`() {
        decides(true)
        val answer = adapter().named(entranceId, reader, listOf(ivan.id), purpose)!!
        assertThat(answer.values.joinToString()).doesNotContain(ivan.idValue)
    }

    @Test
    fun `PM-SEC-001 with sign-in switched off nobody is asking — the names are given and entered as given to nobody, and nothing is decided`() {
        val nobody = Asking(null, null)
        assertThat(adapter("off").named(entranceId, nobody, listOf(ivan.id), purpose)).isEqualTo(mapOf(ivan.id to "Иван Петров"))
        assertThat(written).singleElement().satisfies({ row ->
            assertThat(row.outcome).isEqualTo("OPEN")
            assertThat(row.actor).isNull()
            assertThat(row.bookDate).isEqualTo(today)
        })
        verify(policy, never()).decide(any(), any(), any(), any())
        // configured for sign-in, nobody is refused like anybody else — and enters nothing
        decides(false)
        assertThat(adapter().named(entranceId, nobody, listOf(ivan.id), purpose)).isNull()
        assertThat(written).hasSize(1)
    }
}
