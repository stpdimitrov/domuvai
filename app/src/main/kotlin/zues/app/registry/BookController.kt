package zues.app.registry

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Reads the Book of the Condominium for an entrance (PM-BOOK-001 — the electronic book is the
 * system of record). The book resolves as of a date; omit it and it is today in Sofia (PM-SYS-004).
 * It is read only by those the policy allows (PM-BOOK-006): who is asking is the party of the sign-in, never a
 * parameter, and a reader the policy refuses gets a 403. Every read says why, and every answer — served or refused —
 * is logged (PM-BOOK-007); the log is exported the same way.
 */
@RestController
@RequestMapping("/api/registry/entrances/{entranceId}/book")
class BookController(
    private val access: BookAccessService,
    private val retention: BookRetentionService,
    private val who: WhoAsks,
    private val clock: Clock,
) {

    @GetMapping
    fun book(
        @PathVariable entranceId: UUID,
        @RequestParam(required = false) on: String?,
        @RequestParam purpose: String,
        signedIn: JwtAuthenticationToken?,
    ): CondominiumBook =
        served(access.read(entranceId, on?.let { LocalDate.parse(it) } ?: LocalDate.parse(toSofiaDate(clock.instant())), who.of(signedIn), purpose))

    /** Who read the book, or was refused it, why and when (PM-BOOK-007) — the entrance's entries, oldest first. This export is an entry too. */
    @GetMapping("/access-log")
    fun accessLog(@PathVariable entranceId: UUID, @RequestParam purpose: String, signedIn: JwtAuthenticationToken?): List<BookAccessView> =
        served(access.export(entranceId, who.of(signedIn), purpose))

    /** The refusal leaves the service as an answer, so its entry is kept; it becomes a 403 only here. */
    private fun <T> served(answer: BookAnswer<T>): T = when (answer) {
        is BookAnswer.Served -> answer.value
        is BookAnswer.Refused -> throw BookReadForbidden(answer.ruleId)
    }

    /**
     * Anonymise the field groups past their retention window — a resident's link to a named person,
     * an animal's passport number — as of today in Sofia (PM-BOOK-010, PM-SYS-004). It takes no date,
     * and no window starts before its move-out was recorded, so nothing is anonymised early.
     * Irreversible.
     */
    @PostMapping("/retention")
    fun applyRetention(@PathVariable entranceId: UUID): RetentionApplied =
        retention.anonymiseDue(entranceId, LocalDate.parse(toSofiaDate(clock.instant())))

    /** No such entrance. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** Not one of those who may read the book (PM-BOOK-006) → 403, naming the rule. Nobody signed in is refused the same way. */
    @ExceptionHandler(BookReadForbidden::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onForbidden(e: BookReadForbidden): Map<String, String> = mapOf("error" to "the book is read by the manager, the board and the controller of this entrance", "rule" to e.ruleId)

    /** No purpose, or one too long → 400: the book is not served off the record. */
    @ExceptionHandler(BookAccessRefused::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onRefused(e: BookAccessRefused): Map<String, String> = mapOf("error" to (e.message ?: "the book is read on the record"))

    /** A malformed `on` date is the caller's error. */
    @ExceptionHandler(DateTimeParseException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onBadDate(e: DateTimeParseException): Map<String, String> = mapOf("error" to "on must be an ISO date (YYYY-MM-DD)")
}

/** The policy refused this reader; the entry of the refusal is already written. */
class BookReadForbidden(val ruleId: String) : RuntimeException("refused by $ruleId")
