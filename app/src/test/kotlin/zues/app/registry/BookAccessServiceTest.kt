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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The book read on the record, with the repositories mocked — no Spring, no database. Proves a read writes its
 * entry before the book is assembled, that nothing is served or written for a reader who does not say who and why,
 * and that the log's export is an entry too (PM-BOOK-007). BookPersistenceIT tries the table's own refusals.
 */
class BookAccessServiceTest {

    private val book: BookService = mock()
    private val entrances: EntranceRepository = mock()
    private val parties: PartyRepository = mock()
    private val log: BookAccessRepository = mock()
    private val aggregates: JdbcAggregateTemplate = mock()
    private val now = Instant.parse("2026-05-31T21:30:00.123456789Z")
    private val service = BookAccessService(book, entrances, parties, log, aggregates, Clock.fixed(now, ZoneOffset.UTC))

    private val entranceId = UUID.randomUUID()
    private val manager = UUID.randomUUID()
    private val on = LocalDate.parse("2026-06-01")
    private val theBook = CondominiumBook(entranceId, on, emptyList(), complete = false)
    private val written = mutableListOf<BookAccessRow>()

    @BeforeEach
    fun onRecord() {
        whenever(entrances.existsById(entranceId)).thenReturn(true)
        whenever(parties.existsById(manager)).thenReturn(true)
        whenever(book.forEntrance(entranceId, on)).thenReturn(theBook)
        whenever(aggregates.insert(any<BookAccessRow>())).thenAnswer { (it.arguments[0] as BookAccessRow).also { row -> written += row } }
    }

    @Test
    fun `PM-BOOK-007 a read of the book writes who read it, why, as of which date and when — before the book is assembled`() {
        assertThat(service.read(entranceId, on, manager, "  годишен отчет ")).isEqualTo(theBook)
        val entry = written.single()
        assertThat(listOf(entry.entranceId, entry.actor, entry.purpose, entry.kind, entry.bookDate))
            .containsExactly(entranceId, manager, "годишен отчет", "BOOK_READ", on)
        assertThat(entry.at).isEqualTo(Instant.parse("2026-05-31T21:30:00.123456Z"))            // as the database keeps it
        inOrder(aggregates, book) {
            verify(aggregates).insert(any<BookAccessRow>())
            verify(book).forEntrance(entranceId, on)
        }
    }

    @Test
    fun `PM-BOOK-007 nothing is served and nothing written without a purpose, a registered actor and a registered entrance`() {
        assertThatThrownBy { service.read(entranceId, on, manager, " ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.read(entranceId, on, UUID.randomUUID(), "годишен отчет") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.read(UUID.randomUUID(), on, manager, "годишен отчет") }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { service.export(entranceId, manager, "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(written).isEmpty()
        verify(book, never()).forEntrance(any(), any())
        verify(log, never()).findByEntranceIdOrderByAtAscIdAsc(any())
    }

    @Test
    fun `PM-BOOK-007 the log is exported oldest first with each actor's name, and the export is an entry of its own`() {
        val earlier = BookAccessRow(UUID.randomUUID(), entranceId, manager, "годишен отчет", "BOOK_READ", on, now.minusSeconds(60))
        whenever(log.findByEntranceIdOrderByAtAscIdAsc(entranceId)).thenAnswer { listOf(earlier) + written }
        whenever(parties.findAllById(setOf(manager))).thenReturn(listOf(Party(manager, "Мария Иванова", null, null)))

        val exported = service.export(entranceId, manager, "проверка на КЗЛД")

        assertThat(exported.map { it.kind }).containsExactly("BOOK_READ", "LOG_EXPORT")
        assertThat(exported.map { it.actorName }).containsOnly("Мария Иванова")
        assertThat(exported.last().purpose).isEqualTo("проверка на КЗЛД")
        assertThat(exported.last().bookDate).isNull()
        assertThat(written.single().kind).isEqualTo("LOG_EXPORT")
    }
}
