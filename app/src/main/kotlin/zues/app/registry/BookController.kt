package zues.app.registry

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Reads the Book of the Condominium for an entrance (PM-BOOK-001 — the electronic book is the
 * system of record). The book resolves as of a date; omit it and it is today.
 */
@RestController
@RequestMapping("/api/registry/entrances/{entranceId}/book")
class BookController(private val book: BookService, private val clock: Clock) {

    @GetMapping
    fun book(@PathVariable entranceId: UUID, @RequestParam(required = false) on: String?): CondominiumBook =
        book.forEntrance(entranceId, on?.let { LocalDate.parse(it) } ?: LocalDate.now(clock))

    /** No such entrance. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** A malformed `on` date is the caller's error. */
    @ExceptionHandler(DateTimeParseException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onBadDate(e: DateTimeParseException): Map<String, String> = mapOf("error" to "on must be an ISO date (YYYY-MM-DD)")
}
