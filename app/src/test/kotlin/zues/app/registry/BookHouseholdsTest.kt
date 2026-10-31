package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
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
 * The household test as a registration asks it, with the real policy and the real book access over a mocked [Holdings]
 * — no Spring, no database. Proves that every test is entered once with who, why and when, and what each asker is told
 * (PM-BOOK-006, PM-BOOK-007, PM-SEC-004). That the entry survives the refusal is `ProxyPersistenceIT`'s.
 */
class BookHouseholdsTest {

    private val holdings: Holdings = mock()
    private val aggregates: JdbcAggregateTemplate = mock()
    // 00:30 on 1 June in Sofia; still 31 May in UTC
    private val now = Instant.parse("2026-05-31T21:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val today = LocalDate.parse("2026-06-01")

    private val entranceId = UUID.randomUUID()
    private val roles = mutableMapOf<UUID, Held>()
    private val policy = Policy(listOf(RoleSource { who, at, _ -> setOfNotNull(roles[who.party]?.takeIf { at == entranceId }) }))
    private val households = BookHouseholdsAdapter(holdings, BookAccessService(mock(), mock(), mock(), mock(), aggregates, policy, clock))

    private fun person(subject: String, role: Role? = null) =
        Asking(Login(AN_ISSUER, subject), UUID.randomUUID()).also { who -> role?.let { roles[who.party!!] = Held(it, entranceId, setOf(UUID.randomUUID())) } }

    private val principal = person("principal", Role.OWN)
    private val home = UUID.randomUUID()
    private val grown = UUID.randomUUID()
    private val child = UUID.randomUUID()
    private val elsewhere = UUID.randomUUID()
    private val written = mutableListOf<BookAccessRow>()

    @BeforeEach
    fun theBook() {
        whenever(holdings.residents(entranceId, today)).thenReturn(
            listOf(
                Resident(principal.party!!, home, childUnder6 = false), Resident(grown, home, childUnder6 = false),
                Resident(child, home, childUnder6 = true), Resident(elsewhere, UUID.randomUUID(), childUnder6 = false),
            ),
        )
        whenever(aggregates.insert(any<BookAccessRow>())).thenAnswer { (it.arguments[0] as BookAccessRow).also { row -> written += row } }
    }

    private fun asked(who: Asking, agent: UUID, service: BookHouseholds = households) =
        service.member(entranceId, who, principal.party!!, agent, setOf(home), today)

    @Test
    fun `PM-BOOK-007 a passed check enters one entry - the actor, the purpose, the date and the time, in the access log's own kind`() {
        assertThat(asked(principal, grown)).isEqualTo(HouseholdAnswer.MEMBER)

        val entry = written.single()
        assertThat(entry.kind).isEqualTo(BookAccessKind.HOUSEHOLD_CHECK.name)
        assertThat(entry.entranceId).isEqualTo(entranceId)
        assertThat(entry.actor).isEqualTo(principal.party)
        assertThat(entry.loginIssuer).isEqualTo(AN_ISSUER)
        assertThat(entry.loginSubject).isEqualTo("principal")
        assertThat(entry.purpose).isEqualTo(BookHouseholdsAdapter.PURPOSE)
        assertThat(entry.bookDate).isEqualTo(today)
        assertThat(entry.at).isEqualTo(now)
        assertThat(entry.outcome).isEqualTo("SERVED")
        assertThat(entry.ruleId).isEqualTo("PM-BOOK-006")
    }

    @Test
    fun `PM-BOOK-007 each refusal enters one entry of its own`() {
        assertThat(asked(principal, elsewhere)).isEqualTo(HouseholdAnswer.NOT_LIVING_THERE)
        assertThat(written).hasSize(1)
        assertThat(asked(principal, child)).isEqualTo(HouseholdAnswer.CHILD_UNDER_6)
        assertThat(written).hasSize(2)
        assertThat(written).allSatisfy {
            assertThat(it.kind).isEqualTo("HOUSEHOLD_CHECK")
            assertThat(it.actor).isEqualTo(principal.party)
            assertThat(it.purpose).isEqualTo(BookHouseholdsAdapter.PURPOSE)
            assertThat(it.bookDate).isEqualTo(today)
        }
    }

    @Test
    fun `PM-BOOK-007 the entry is written before the book is read - no entry, no book`() {
        asked(principal, grown)
        inOrder(aggregates, holdings) {
            verify(aggregates).insert(any<BookAccessRow>())
            verify(holdings).residents(entranceId, today)
        }
    }

    @Test
    fun `PM-BOOK-006 a recorder the policy lets read the book keeps both refusals, and the entry says they were told`() {
        for (role in listOf(Role.BM, Role.MB, Role.CTL)) {
            written.clear()
            val reader = person("reader-$role", role)
            assertThat(asked(reader, elsewhere)).describedAs("$role").isEqualTo(HouseholdAnswer.NOT_LIVING_THERE)
            assertThat(asked(reader, child)).describedAs("$role").isEqualTo(HouseholdAnswer.CHILD_UNDER_6)
            assertThat(written.map { it.actor to it.outcome }).containsOnly(reader.party to "SERVED")
        }
    }

    @Test
    fun `PM-BOOK-006 a recorder the policy does not let read is told neither fact, whatever the book says, and the entry says so`() {
        for (role in Role.entries - setOf(Role.BM, Role.MB, Role.CTL)) {
            written.clear()
            val recorder = person("recorder-$role", role)
            assertThat(asked(recorder, elsewhere)).describedAs("$role").isEqualTo(HouseholdAnswer.NOT_BORNE_OUT)
            assertThat(asked(recorder, child)).describedAs("$role").isEqualTo(HouseholdAnswer.NOT_BORNE_OUT)
            assertThat(written.map { it.actor to it.outcome }).containsOnly(recorder.party to "REFUSED")
            assertThat(written.map { it.ruleId }).containsOnly("PM-BOOK-006")
            // a test that passes passes for them too: the appointment is what they hold the document for
            assertThat(asked(recorder, grown)).describedAs("$role").isEqualTo(HouseholdAnswer.MEMBER)
        }
    }

    @Test
    fun `PM-GA-009 where the principal lives counts only if they hold a title there - the home is one of the units given`() {
        assertThat(households.member(entranceId, principal, principal.party!!, grown, setOf(UUID.randomUUID()), today)).isEqualTo(HouseholdAnswer.NOT_LIVING_THERE)
    }

    @Test
    fun `PM-BOOK-006 an allowance with no party behind it tells nothing`() {
        val anyone = Policy(listOf(RoleSource { _, at, _ -> setOf(Held(Role.BM, at)) }))
        val trusting = BookHouseholdsAdapter(holdings, BookAccessService(mock(), mock(), mock(), mock(), aggregates, anyone, clock))

        assertThat(asked(Asking(Login(AN_ISSUER, "untied"), null), child, trusting)).isEqualTo(HouseholdAnswer.NOT_BORNE_OUT)
        assertThat(written.single().outcome).isEqualTo("REFUSED")
    }
}
