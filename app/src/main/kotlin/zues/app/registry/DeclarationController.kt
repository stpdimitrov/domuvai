package zues.app.registry

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
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

/** A declaration to file: who declares, for which unit, and why. The filing date is never the caller's. */
data class FileDeclarationRequest(val unitId: UUID, val partyId: UUID, val kind: String = DeclarationKind.ACQUISITION.name)

data class DeclarationFiled(val id: UUID, val filedOn: LocalDate, val templateVersion: String)

/**
 * Declarations for entry in the book (PM-BOOK-003, PM-BOOK-004) and who owes one as of a date —
 * omit it and it is today in Sofia (PM-SYS-004).
 */
@RestController
@RequestMapping("/api/registry/entrances/{entranceId}/book/declarations")
class DeclarationController(
    private val declarations: DeclarationService,
    private val lists: BookLists,
    private val who: WhoAsks,
    private val clock: Clock,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun file(@PathVariable entranceId: UUID, @RequestBody request: FileDeclarationRequest): DeclarationFiled =
        declarations.file(entranceId, request.unitId, request.partyId, request.kind)
            .let { DeclarationFiled(it.id, it.filedOn, it.templateVersion) }

    /**
     * Who is past the deadline to declare for the book, by name — read by the manager, the board and the controller of
     * the entrance, as the sign-in says who is asking; anybody else is a 403 (PM-BOOK-006). Every answer is entered in
     * the book's access log (PM-BOOK-007).
     */
    @GetMapping("/overdue")
    fun overdue(@PathVariable entranceId: UUID, @RequestParam(required = false) on: String?, signedIn: JwtAuthenticationToken?): List<OverdueDeclaration> =
        lists.overdue(entranceId, on?.let { LocalDate.parse(it) } ?: LocalDate.parse(toSofiaDate(clock.instant())), who.of(signedIn)).orForbidden()

    /** An unknown declaration kind, or a malformed `on` date, is the caller's error. */
    @ExceptionHandler(IllegalArgumentException::class, DateTimeParseException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: RuntimeException): Map<String, String> = mapOf("error" to (e.message ?: "invalid request"))

    /** No such unit, party or entrance. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))
}
