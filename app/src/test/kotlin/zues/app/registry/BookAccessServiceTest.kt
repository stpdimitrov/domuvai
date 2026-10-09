package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

/**
 * The book read by those the policy allows and on the record, with the repositories mocked and the real policy over
 * roles the test dates — no Spring, no database. Proves who may read is decided before anything is read and as at
 * today in Sofia, that a refusal is an entry and no book (PM-BOOK-006), that a served read writes its entry before
 * the book is assembled, and that the log's export is guarded and logged the same way (PM-BOOK-007).
 */
class BookAccessServiceTest {

    private val book: BookService = mock()
    private val entrances: EntranceRepository = mock()
    private val parties: PartyRepository = mock()
    private val log: BookAccessRepository = mock()
    private val aggregates: JdbcAggregateTemplate = mock()
    // 00:30 on 1 June in Sofia; still 31 May in UTC
    private val now = Instant.parse("2026-05-31T21:30:00.123456789Z")
    private val today = LocalDate.parse("2026-06-01")

    /** The roles of the test: a party, a role, from a day up to and not including another. */
    private data class Dated(val party: UUID, val entranceId: UUID, val held: Held, val from: LocalDate, val until: LocalDate?)
    private val holdings = mutableListOf<Dated>()
    private val asked = mutableListOf<LocalDate>()
    private val policy = Policy(
        listOf(
            RoleSource { who, entranceId, asAt ->
                asked += asAt
                holdings.filter { it.party == who.party && it.entranceId == entranceId && !asAt.isBefore(it.from) && (it.until == null || asAt.isBefore(it.until)) }
                    .mapTo(mutableSetOf()) { it.held }
            },
        ),
    )
    private val service = BookAccessService(book, entrances, parties, log, aggregates, policy, Clock.fixed(now, ZoneOffset.UTC))

    private val entranceId = UUID.randomUUID()
    private val flat1 = UUID.randomUUID()
    private val on = LocalDate.parse("2025-01-15")
    private val theBook = CondominiumBook(entranceId, on, emptyList(), complete = false)
    private val written = mutableListOf<BookAccessRow>()

    private fun person(subject: String) = Asking(Login("https://id.example.test/realms/domuvai", subject), UUID.randomUUID())
    private fun holding(who: Asking, role: Role, units: Set<UUID> = emptySet(), from: LocalDate = today.minusYears(1), until: LocalDate? = null) =
        who.also { holdings += Dated(it.party!!, entranceId, Held(role, entranceId, units), from, until) }

    private val manager = holding(person("manager"), Role.BM)
    private val ownerA = holding(person("owner-a"), Role.OWN, setOf(flat1))

    @BeforeEach
    fun onRecord() {
        whenever(entrances.existsById(entranceId)).thenReturn(true)
        whenever(book.forEntrance(entranceId, on)).thenReturn(theBook)
        whenever(aggregates.insert(any<BookAccessRow>())).thenAnswer { (it.arguments[0] as BookAccessRow).also { row -> written += row } }
    }

    @Test
    fun `PM-BOOK-007 a served read writes who read it, why, by which rule, as of which date and when — before the book is assembled`() {
        assertThat(service.read(entranceId, on, manager, "  годишен отчет ")).isEqualTo(BookAnswer.Served(theBook))
        val entry = written.single()
        assertThat(listOf(entry.entranceId, entry.actor, entry.purpose, entry.kind, entry.bookDate, entry.outcome, entry.ruleId, entry.loginIssuer, entry.loginSubject))
            .containsExactly(entranceId, manager.party, "годишен отчет", "BOOK_READ", on, "SERVED", "PM-BOOK-006", manager.login!!.issuer, "manager")
        assertThat(entry.at).isEqualTo(Instant.parse("2026-05-31T21:30:00.123456Z"))            // as the database keeps it
        inOrder(aggregates, book) {
            verify(aggregates).insert(any<BookAccessRow>())
            verify(book).forEntrance(entranceId, on)
        }
    }

    @Test
    fun `PM-BOOK-006 owner A asking for the book — owner B's household in it — is refused, with an entry of the refusal and nothing read`() {
        assertThat(service.read(entranceId, on, ownerA, "искам да видя съседите")).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        val entry = written.single()
        assertThat(listOf(entry.actor, entry.purpose, entry.kind, entry.bookDate, entry.outcome, entry.ruleId, entry.loginSubject))
            .containsExactly(ownerA.party, "искам да видя съседите", "BOOK_READ", on, "REFUSED", "PM-BOOK-006", "owner-a")
        verify(book, never()).forEntrance(any(), any())
    }

    @Test
    fun `PM-BOOK-006 a person with no role, a login tied to no party and nobody at all are refused, each on the record as far as they are known`() {
        val stranger = person("stranger")
        val untied = Asking(Login("https://id.example.test/realms/domuvai", "untied"), null)
        val nobody = Asking(null, null)                                                           // sign-in switched off: there is no token
        for (who in listOf(stranger, untied, nobody)) assertThat(service.read(entranceId, on, who, "проверка")).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        assertThat(written.map { it.outcome }).containsOnly("REFUSED")
        assertThat(written.map { it.actor }).containsExactly(stranger.party, null, null)
        assertThat(written.map { it.loginSubject }).containsExactly("stranger", "untied", null)
        verify(book, never()).forEntrance(any(), any())
    }

    @Test
    fun `PM-BOOK-006 who may read is decided as at today in Sofia — not the date the book is asked for, and not the clock's own day`() {
        val former = holding(person("former"), Role.BM, from = LocalDate.parse("2024-01-01"), until = LocalDate.parse("2026-01-01"))
        assertThat(service.read(entranceId, on, former, "проверка")).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))   // the manager on 2025-01-15, not today
        val newly = holding(person("newly"), Role.BM, from = today)                                                  // from 1 June: today in Sofia, tomorrow in UTC
        assertThat(service.read(entranceId, on, newly, "проверка")).isEqualTo(BookAnswer.Served(theBook))
        assertThat(asked).containsOnly(today)
    }

    @Test
    fun `PM-BOOK-006 a served read is a party's — an allowance with no party to put on the record is a refusal`() {
        val anyone = Policy(listOf(RoleSource { _, at, _ -> setOf(Held(Role.BM, at)) }))
        val trusting = BookAccessService(book, entrances, parties, log, aggregates, anyone, Clock.fixed(now, ZoneOffset.UTC))
        val untied = Asking(Login("https://id.example.test/realms/domuvai", "untied"), null)
        assertThat(trusting.read(entranceId, on, untied, "проверка")).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        assertThat(written.single().outcome).isEqualTo("REFUSED")
        verify(book, never()).forEntrance(any(), any())
    }

    @Test
    fun `PM-BOOK-007 nothing is served and nothing written without a purpose and a registered entrance, whoever asks`() {
        for (who in listOf(manager, ownerA)) {
            assertThatThrownBy { service.read(entranceId, on, who, " ") }.isInstanceOf(BookAccessRefused::class.java)
            assertThatThrownBy { service.read(entranceId, on, who, "о".repeat(PURPOSE_MAX + 1)) }.isInstanceOf(BookAccessRefused::class.java)
            assertThatThrownBy { service.read(UUID.randomUUID(), on, who, "годишен отчет") }.isInstanceOf(NoSuchElementException::class.java)
            assertThatThrownBy { service.export(entranceId, who, "") }.isInstanceOf(BookAccessRefused::class.java)
        }
        assertThat(written).isEmpty()
        verify(book, never()).forEntrance(any(), any())
        verify(log, never()).findByEntranceIdOrderByAtAscIdAsc(any())
        service.read(entranceId, on, manager, " " + "о".repeat(PURPOSE_MAX) + " ")                 // the longest purpose, once trimmed
        assertThat(written.single().purpose).hasSize(PURPOSE_MAX)
    }

    @Test
    fun `PM-BOOK-007 the log is exported oldest first with each reader's name and how each read was decided, and the export is an entry of its own`() {
        val earlier = BookAccessRow(UUID.randomUUID(), entranceId, manager.party, "годишен отчет", "BOOK_READ", on, now.minusSeconds(120))
        val refused = BookAccessRow(UUID.randomUUID(), entranceId, null, "любопитство", "BOOK_READ", on, now.minusSeconds(60), "REFUSED", "PM-BOOK-006", "https://id.example.test/realms/domuvai", "untied")
        whenever(log.findByEntranceIdOrderByAtAscIdAsc(entranceId)).thenAnswer { listOf(earlier, refused) + written }
        whenever(parties.findAllById(setOf(manager.party!!))).thenReturn(listOf(Party(manager.party!!, "Мария Иванова", null, null)))

        val exported = (service.export(entranceId, manager, "проверка на КЗЛД") as BookAnswer.Served).value

        assertThat(exported.map { it.kind }).containsExactly("BOOK_READ", "BOOK_READ", "LOG_EXPORT")
        assertThat(exported.map { it.outcome }).containsExactly("SERVED", "REFUSED", "SERVED")
        assertThat(exported.map { it.actorName }).containsExactly("Мария Иванова", null, "Мария Иванова")
        assertThat(exported[1].ruleId).isEqualTo("PM-BOOK-006")
        assertThat(exported[1].loginSubject).isEqualTo("untied")
        assertThat(exported.last().purpose).isEqualTo("проверка на КЗЛД")
        assertThat(exported.last().bookDate).isNull()
        assertThat(written.single().kind).isEqualTo("LOG_EXPORT")
    }

    @Test
    fun `PM-BOOK-006 the log of who read the book is refused to those who may not read the book — with an entry, and no log`() {
        assertThat(service.export(entranceId, ownerA, "кой ме е гледал")).isEqualTo(BookAnswer.Refused("PM-BOOK-006"))
        assertThat(listOf(written.single().kind, written.single().outcome, written.single().actor)).containsExactly("LOG_EXPORT", "REFUSED", ownerA.party)
        verify(log, never()).findByEntranceIdOrderByAtAscIdAsc(any())
    }
}
