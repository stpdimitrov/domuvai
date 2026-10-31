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
import java.time.Instant
import java.util.UUID

/** One entered ballot row: an absent owner's choice for one unit, its weight, and the declaration it came from. */
data class AbsenteeBallotView(
    val id: UUID,
    val ownerPartyId: UUID,
    val unitId: UUID,
    val weight: String,
    val choice: String,
    val signature: String,
    val documentRef: String,
    val documentSha256: String,
    val receivedOn: String,
    val recordedBy: UUID,
    val recordedAt: Instant,
)

/**
 * Absent owners' ballots on an item tagged for them (PM-GA-017): entered after its sitting, by day 7, from a
 * handwritten-signed declaration (PM-GA-018), by whoever runs the assembly, signed in — anybody else, the owner
 * included, is a 403 naming the rule (PM-SEC-001). Read, as how each owner voted, by the manager, the board and the
 * controller only; anybody else, the owner whose ballot it is included, is a 403 naming PM-SEC-002 (the owner's answer
 * on #298, 2026-10-27), and every read is entered (PM-SEC-004).
 */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/absentee-ballots")
class AbsenteeBallotController(private val ballots: AbsenteeBallotService, private val acts: VoteActs, private val who: WhoAsks) {

    // Rule: PM-GA-017
    // Rule: PM-GA-018
    // Rule: PM-SEC-001
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun enter(
        @PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @PathVariable itemId: UUID, @RequestBody request: BallotRequest,
        signedIn: JwtAuthenticationToken?,
    ): List<AbsenteeBallotView> = ballots.enter(who.of(signedIn), entranceId, assemblyId, itemId, request).map { it.view() }

    // Rule: PM-SEC-002
    // Rule: PM-SEC-001
    // Rule: PM-GOV-008
    // Rule: PM-SEC-004
    @GetMapping
    fun read(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @PathVariable itemId: UUID, signedIn: JwtAuthenticationToken?): List<AbsenteeBallotView> =
        acts.readBallots(who.of(signedIn), entranceId, assemblyId, itemId).map { it.view() }

    private fun AbsenteeBallot.view() =
        AbsenteeBallotView(id, partyId, unitId, weight.stripTrailingZeros().toPlainString(), choice, signature, documentRef, documentSha256, receivedOn.toString(), recordedBy, recordedAt)

    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused): Map<String, String> = mapOf("error" to (e.message ?: "not allowed"), "rule" to e.ruleId)

    /** An unknown choice or signature, no declaration or hash, a day outside the window, an owner who was represented. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid request"))

    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** An item not tagged or not closed, the window closed, a ballot already entered, a QES, a unit not weighed. */
    @ExceptionHandler(IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: IllegalStateException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))
}
