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
    private val lists: BookLists,
    private val who: WhoAsks,
    private val clock: Clock,
) {

    @GetMapping
    fun book(
        @PathVariable entranceId: UUID,
        @RequestParam(required = false) on: String?,
        @RequestParam(required = false) purpose: String?,
        signedIn: JwtAuthenticationToken?,
    ): CondominiumBook =
        access.read(entranceId, on?.let { LocalDate.parse(it) } ?: LocalDate.parse(toSofiaDate(clock.instant())), who.of(signedIn), purpose.orEmpty()).orForbidden()

    /** Who read the book, or was refused it, why and when (PM-BOOK-007) — the entrance's entries, oldest first. This export is an entry too. */
    @GetMapping("/access-log")
    fun accessLog(@PathVariable entranceId: UUID, @RequestParam(required = false) purpose: String?, signedIn: JwtAuthenticationToken?): List<BookAccessView> =
        access.export(entranceId, who.of(signedIn), purpose.orEmpty()).orForbidden()

    /**
     * Anonymise the field groups past their retention window — a resident's link to a named person,
     * an animal's passport number — as of today in Sofia (PM-BOOK-010, PM-SYS-004). It takes no date,
     * and no window starts before its move-out was recorded, so nothing is anonymised early.
     * Irreversible — run by the manager and the board only, and entered in the log (PM-BOOK-006, PM-BOOK-007).
     */
    @PostMapping("/retention")
    fun applyRetention(@PathVariable entranceId: UUID, signedIn: JwtAuthenticationToken?): RetentionApplied =
        lists.anonymiseDue(entranceId, LocalDate.parse(toSofiaDate(clock.instant())), who.of(signedIn)).orForbidden()

    /** No such entrance. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** No purpose, or one too long, from a reader who may read → 400: the book is not served off the record. */
    @ExceptionHandler(BookAccessRefused::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onRefused(e: BookAccessRefused): Map<String, String> = mapOf("error" to (e.message ?: "the book is read on the record"))

    /** A malformed `on` date is the caller's error. */
    @ExceptionHandler(DateTimeParseException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onBadDate(e: DateTimeParseException): Map<String, String> = mapOf("error" to "on must be an ISO date (YYYY-MM-DD)")
}

/** The policy refused this caller; for a reader, the entry of the refusal is already written. [why] says what the rule's own words would not. */
class BookReadForbidden(val ruleId: String, val why: String? = null) : RuntimeException("refused by $ruleId")
