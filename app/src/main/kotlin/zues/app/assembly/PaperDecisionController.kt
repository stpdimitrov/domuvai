package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
import java.util.UUID

/**
 * Decisions the general assembly took on paper, recorded from their protocols by the deployment's administrator and by
 * nobody else (PM-SEC-001); read as an assembly is. The administrator is the login of the request's token, never a
 * parameter, and they typed what they get back, the custodian among it.
 */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/paper-decisions")
class PaperDecisionController(private val decisions: PaperDecisions, private val who: WhoAsks) {

    // Rule: PM-FEE-012
    // Rule: PM-GA-022
    // Rule: PM-DOC-007
    // Rule: PM-SEC-001
    // Rule: PM-SEC-004
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun record(@PathVariable entranceId: UUID, @RequestBody request: RecordPaperDecision, signedIn: JwtAuthenticationToken?): PaperDecisionView =
        PaperDecisionView.of(decisions.record(who.of(signedIn), entranceId, request), namesCustodian = true)

    // Rule: PM-GA-022
    // Rule: PM-DOC-007
    // Rule: PM-SEC-001
    // Rule: PM-SEC-002
    @GetMapping
    fun list(@PathVariable entranceId: UUID, signedIn: JwtAuthenticationToken?): List<PaperDecisionView> =
        decisions.list(who.of(signedIn), entranceId)

    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused): Map<String, String> = mapOf("error" to (e.message ?: "not allowed"), "rule" to e.ruleId)

    /** No such entrance → 404 — said only to the administrator. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** Something the record lacks, or a protocol dated in the future → 400. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid"))
}
