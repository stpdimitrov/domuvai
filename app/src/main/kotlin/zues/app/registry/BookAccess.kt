package zues.app.registry

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * One access to the book (Rule: PM-BOOK-007): who, why, when — and, for a read, the date the book was read as of —
 * with how it was decided: served or refused, and by which rule (Rule: PM-BOOK-006). The actor is the party the
 * sign-in's login is tied to, the login kept beside it; a refusal may have no party. Insert-only.
 */
@Table("book_access")
data class BookAccessRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val actor: UUID?,
    val purpose: String,
    val kind: String,                       // BookAccessKind
    val bookDate: LocalDate?,
    val at: Instant,
    val outcome: String = BookAccessOutcome.SERVED.name,
    val ruleId: String? = null,
    val loginIssuer: String? = null,
    val loginSubject: String? = null,
)

enum class BookAccessOutcome { SERVED, REFUSED }

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

/** A read off the record — no purpose, or one too long to be a purpose: a 400, no entry and no book. */
class BookAccessRefused(message: String) : RuntimeException(message)

/** What a reader gets: the thing asked for, or the rule that refuses them. A refusal is an answer, not a failure — its entry is kept. */
sealed interface BookAnswer<out T> {
    data class Served<T>(val value: T) : BookAnswer<T>
    data class Refused(val ruleId: String) : BookAnswer<Nothing>
}

/** A purpose is a line, not a document: the table refuses a longer one too. */
const val PURPOSE_MAX = 500

/** An entry as exported: the actor by name beside the id, so the log reads without a second lookup. */
data class BookAccessView(
    val id: UUID,
    val actor: UUID?,
    val actorName: String?,
    val purpose: String,
    val kind: String,
    val bookDate: LocalDate?,
    val at: Instant,
    val outcome: String,
    val ruleId: String?,
    val loginSubject: String?,
)

/**
 * The book, read by those the policy allows and on the record (Rule: PM-BOOK-006, PM-BOOK-007). Who is asking comes
 * from the sign-in, never from the caller; whether they may is the policy's to say, as at today in Sofia — the day of
 * the access, not the date the book is read as of. Every answer writes its entry in its own transaction: a served
 * read's entry before the book is assembled — no entry, no book — and a refusal's entry instead of the book. The log
 * is exported the same way, and logs itself.
 */
@Service
class BookAccessService(
    private val book: BookService,
    private val entrances: EntranceRepository,
    private val parties: PartyRepository,
    private val log: BookAccessRepository,
    private val aggregates: JdbcAggregateTemplate,
    private val policy: Policy,
    private val clock: Clock,
) {
    // Rule: PM-BOOK-006
    // Rule: PM-BOOK-007
    @Transactional
    fun read(entranceId: UUID, on: LocalDate, who: Asking, purpose: String): BookAnswer<CondominiumBook> =
        decided(entranceId, who, purpose, BookAccessKind.BOOK_READ, on) { book.forEntrance(entranceId, on) }

    /** Rule: PM-BOOK-007 — the entrance's entries, oldest first, this export's own among them. Read by whoever may read the book (Rule: PM-BOOK-006). */
    @Transactional
    fun export(entranceId: UUID, who: Asking, purpose: String): BookAnswer<List<BookAccessView>> =
        decided(entranceId, who, purpose, BookAccessKind.LOG_EXPORT, null) {
            val rows = log.findByEntranceIdOrderByAtAscIdAsc(entranceId)
            val names = parties.findAllById(rows.mapNotNull { it.actor }.toSet()).associate { it.id to it.fullName }
            rows.map { BookAccessView(it.id, it.actor, names[it.actor], it.purpose, it.kind, it.bookDate, it.at, it.outcome, it.ruleId, it.loginSubject) }
        }

    /** The decision, then its entry, then — only if allowed — what was asked for. */
    private fun <T> decided(entranceId: UUID, who: Asking, purpose: String, kind: BookAccessKind, bookDate: LocalDate?, serve: () -> T): BookAnswer<T> {
        if (!entrances.existsById(entranceId)) throw NoSuchElementException("no entrance $entranceId")
        val why = purpose.trim()
        if (why.isEmpty()) throw BookAccessRefused("a purpose is required to read the book (PM-BOOK-007)")
        if (why.length > PURPOSE_MAX) throw BookAccessRefused("a purpose is at most $PURPOSE_MAX characters (PM-BOOK-007)")
        val now = clock.instant()
        val decision = policy.decide(who, Action.READ_BOOK, Resource.OfEntrance(entranceId), LocalDate.parse(toSofiaDate(now)))
        // A served read is a party's: an allowance to a login tied to no party would have nobody to put on the record.
        val served = decision.allowed && who.party != null
        aggregates.insert(
            BookAccessRow(
                UUID.randomUUID(), entranceId, who.party, why, kind.name, bookDate, now.truncatedTo(ChronoUnit.MICROS),
                (if (served) BookAccessOutcome.SERVED else BookAccessOutcome.REFUSED).name, decision.ruleId, who.login?.issuer, who.login?.subject,
            ),
        )
        return if (served) BookAnswer.Served(serve()) else BookAnswer.Refused(decision.ruleId)
    }
}
