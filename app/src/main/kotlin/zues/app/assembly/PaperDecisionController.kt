package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
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
    // Rule: PM-SYS-014
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        @PathVariable entranceId: UUID, @RequestBody request: RecordPaperDecision,
        @RequestHeader("Idempotency-Key") idempotencyKey: String, signedIn: JwtAuthenticationToken?,
    ): PaperDecisionView = PaperDecisionView.of(decisions.record(who.of(signedIn), entranceId, idempotencyKey, request), namesCustodian = true)

    // Rule: PM-GA-022
    // Rule: PM-DOC-007
    // Rule: PM-SEC-001
    // Rule: PM-SEC-002
    @GetMapping
    fun list(@PathVariable entranceId: UUID, signedIn: JwtAuthenticationToken?): List<PaperDecisionView> =
        decisions.list(who.of(signedIn), entranceId)

    /** Who read a paper decision's custodian, oldest first, this read among them: the manager, the board and the controller only. */
    // Rule: PM-SEC-001
    // Rule: PM-SEC-002
    // Rule: PM-SEC-004
    // Rule: PM-GOV-008
    @GetMapping("/reads")
    fun reads(@PathVariable entranceId: UUID, signedIn: JwtAuthenticationToken?): List<PaperDecisionReadView> =
        decisions.reads(who.of(signedIn), entranceId)

    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused): Map<String, String> = mapOf("error" to (e.message ?: "not allowed"), "rule" to e.ruleId)

    /** No such entrance → 404 — said only to the administrator. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** Something the record lacks, a protocol dated in the future, or a blank Idempotency-Key → 400. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid"))

    /** The Idempotency-Key was used for another request, or by a racing one (PM-SYS-014) → 409. */
    @ExceptionHandler(IdempotencyKeyReused::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onReused(e: IdempotencyKeyReused): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))
}
