package zues.app.identity_org

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/** The day a mandate ended, and the protocol or act that ended it. */
data class EndMandate(val on: LocalDate? = null, val protocolRef: String? = null)

/**
 * Who holds an entrance's offices — its manager, board, controller, cashier — said by the deployment's administrator
 * from the protocol that elected them, and by nobody else (PM-SEC-001). The administrator is the login of the request's
 * token, never a parameter; everybody else gets a 403. A new manager or board ends the mandates it succeeds
 * (PM-GOV-004). Every act, and every refused attempt, is on the record (PM-SEC-004).
 */
@RestController
@RequestMapping("/api/identity/entrances/{entranceId}/mandates")
class MandateController(private val administration: MandateAdministration, private val who: WhoAsks) {

    @PostMapping
    fun record(@PathVariable entranceId: UUID, @RequestBody request: RecordMandate, signedIn: JwtAuthenticationToken?): MandateRecorded =
        done(administration.record(who.of(signedIn), entranceId, request))

    @PostMapping("/{mandateId}/end")
    fun end(@PathVariable entranceId: UUID, @PathVariable mandateId: UUID, @RequestBody request: EndMandate, signedIn: JwtAuthenticationToken?): MandateEnded =
        done(administration.end(who.of(signedIn), entranceId, mandateId, request.on, request.protocolRef))

    /** The refusal leaves the service as an answer, so its entry is kept; it becomes a 403 only here. */
    private fun <T> done(answer: MandateAnswer<T>): T = when (answer) {
        is MandateAnswer.Done -> answer.value
        is MandateAnswer.Refused -> throw MandateAdministrationForbidden(answer.ruleId)
    }

    /** Not the deployment's administrator → 403, naming the rule. Nobody signed in is refused the same way. */
    @ExceptionHandler(MandateAdministrationForbidden::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onForbidden(e: MandateAdministrationForbidden): Map<String, String> = mapOf("error" to "a mandate is recorded by the deployment's administrator", "rule" to e.ruleId)

    /** No such entrance, or no such mandate in it → 404 — said only to the administrator. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** A mandate beside or behind one already recorded → 409: mandates are recorded in the order they began. */
    @ExceptionHandler(MandateOutOfOrder::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onOutOfOrder(e: MandateOutOfOrder): Map<String, String> = mapOf("error" to (e.message ?: "out of order"))

    /** The mandate has an end already → 409: the day is recorded once. */
    @ExceptionHandler(MandateAlreadyEnded::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onEnded(e: MandateAlreadyEnded): Map<String, String> = mapOf("error" to (e.message ?: "already ended"))

    /** Something the mandate lacks, a party nobody registered, a mandate too long → 400. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid"))
}

/** The policy refused this caller; the entry of the refusal, if they are signed in, is already written. */
class MandateAdministrationForbidden(val ruleId: String) : RuntimeException("refused by $ruleId")
