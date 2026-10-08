package zues.app.registry

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** One access to the book (Rule: PM-BOOK-007): who, why, when — and, for a read, the date the book was read as of. Insert-only. */
@Table("book_access")
data class BookAccessRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val actor: UUID,
    val purpose: String,
    val kind: String,                       // BookAccessKind
    val bookDate: LocalDate?,
    val at: Instant,
)

enum class BookAccessKind {
    /** the Book of the Condominium was read */
    BOOK_READ,
    /** this log was exported */
    LOG_EXPORT,
}

/** Reads only: an entry is written once, through the aggregate template, and never saved over or deleted. */
interface BookAccessRepository : Repository<BookAccessRow, UUID> {
    fun findByEntranceIdOrderByAtAscIdAsc(entranceId: UUID): List<BookAccessRow>
}

/** A read off the record — no purpose, one too long to be a purpose, or an actor who is not a registered party: a 400, and no book. */
class BookAccessRefused(message: String) : RuntimeException(message)

/** A purpose is a line, not a document: the table refuses a longer one too. */
const val PURPOSE_MAX = 500

/** An entry as exported: the actor by name beside the id, so the log reads without a second lookup. */
data class BookAccessView(
    val id: UUID,
    val actor: UUID,
    val actorName: String,
    val purpose: String,
    val kind: String,
    val bookDate: LocalDate?,
    val at: Instant,
)

/**
 * The book, read on the record (Rule: PM-BOOK-007): every read names who reads and why, and writes its entry in
 * the read's own transaction — no entry, no book. Until sign-in exists the caller names the actor, a registered
 * party; who may read at all (PM-BOOK-006) is not decided here. The log is exported the same way, and logs itself.
 */
@Service
class BookAccessService(
    private val book: BookService,
    private val entrances: EntranceRepository,
    private val parties: PartyRepository,
    private val log: BookAccessRepository,
    private val aggregates: JdbcAggregateTemplate,
    private val clock: Clock,
) {
    // Rule: PM-BOOK-007
    @Transactional
    fun read(entranceId: UUID, on: LocalDate, actor: UUID, purpose: String): CondominiumBook {
        record(entranceId, actor, purpose, BookAccessKind.BOOK_READ, on)
        return book.forEntrance(entranceId, on)
    }

    /** Rule: PM-BOOK-007 — the entrance's entries, oldest first, this export's own among them. */
    @Transactional
    fun export(entranceId: UUID, actor: UUID, purpose: String): List<BookAccessView> {
        record(entranceId, actor, purpose, BookAccessKind.LOG_EXPORT, null)
        val rows = log.findByEntranceIdOrderByAtAscIdAsc(entranceId)
        val names = parties.findAllById(rows.map { it.actor }.toSet()).associate { it.id to it.fullName }
        return rows.map { BookAccessView(it.id, it.actor, names.getValue(it.actor), it.purpose, it.kind, it.bookDate, it.at) }
    }

    private fun record(entranceId: UUID, actor: UUID, purpose: String, kind: BookAccessKind, bookDate: LocalDate?) {
        if (!entrances.existsById(entranceId)) throw NoSuchElementException("no entrance $entranceId")
        val why = purpose.trim()
        if (why.isEmpty()) throw BookAccessRefused("a purpose is required to read the book (PM-BOOK-007)")
        if (why.length > PURPOSE_MAX) throw BookAccessRefused("a purpose is at most $PURPOSE_MAX characters (PM-BOOK-007)")
        if (!parties.existsById(actor)) throw BookAccessRefused("actor $actor is not a registered party (PM-BOOK-007)")
        aggregates.insert(
            BookAccessRow(UUID.randomUUID(), entranceId, actor, why, kind.name, bookDate, clock.instant().truncatedTo(ChronoUnit.MICROS)),
        )
    }
}
