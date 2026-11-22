package zues.app.registry

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * "Which units are mine, in which entrance" — the one question every resident screen starts from. It answers for the
 * party the sign-in is tied to (ADR-002), never for a parameter, and returns only that party's own titles: no resource
 * is chosen, so the policy has nothing to refuse and gains no line (Rule: PM-SEC-001). Nobody asking is a 401; a login
 * tied to no party is signed in and holds nothing, so it gets the same empty list as a party with no titles.
 */
@RestController
@RequestMapping("/api/registry/me")
class MyUnitsController(private val mine: MyUnits, private val who: WhoAsks, private val clock: Clock) {

    // Rule: PM-ORG-011
    // Rule: PM-SYS-004
    @GetMapping("/units")
    fun units(@RequestParam(required = false) on: String?, signedIn: JwtAuthenticationToken?): ResponseEntity<List<MyUnit>> {
        val asking = who.of(signedIn)
        if (asking.login == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val date = on?.let { LocalDate.parse(it) } ?: LocalDate.parse(toSofiaDate(clock.instant()))
        return ResponseEntity.ok(asking.party?.let { mine.of(it, date) } ?: emptyList())
    }

    /** A malformed `on` date is the caller's error. */
    @ExceptionHandler(DateTimeParseException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onBadDate(e: DateTimeParseException): Map<String, String> = mapOf("error" to "on must be an ISO date (YYYY-MM-DD)")
}
