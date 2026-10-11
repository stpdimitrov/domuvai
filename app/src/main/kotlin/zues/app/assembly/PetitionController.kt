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
import zues.app.policy.Action
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What the petition demands an assembly for. Who opens it is the sign-in's party. */
data class OpenPetitionRequest(val subject: String)

/**
 * A petition as it stands on [weighedOn]: the share its signatories own, the threshold with its source and
 * whether counsel has confirmed it, and whether the owners may convene. [cannotWeigh] lists why it cannot be
 * weighed at all; [derivedParts] warns that some of the weight rests on ideal parts derived from area.
 */
data class PetitionView(
    val id: UUID,
    val subject: String,
    val openedBy: UUID,
    val openedAt: Instant,
    val signatories: List<UUID>,
    val weighedOn: LocalDate,
    val heldPct: String,
    val thresholdPct: String,
    val thresholdSource: String,
    val thresholdVerified: Boolean,
    val cannotWeigh: List<String>,
    val derivedParts: Boolean,
    val unlocked: Boolean,
    val assemblyId: UUID?,
)

data class PetitionConvened(val assemblyId: UUID, val status: String, val convenedAs: String, val heldPct: String, val thresholdPct: String)

/**
 * The owners' petition to convene a general assembly, and convening on it (PM-GA-003). Who opens, signs and convenes
 * is the party the sign-in is tied to — never a field of the request — and only an owner of the entrance on the day
 * may (PM-SEC-001): anybody else is a 403 naming the rule, told nothing more. A person signs for themselves only.
 */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/petitions")
class PetitionController(private val petitions: PetitionService, private val who: WhoAsks, private val acting: Acting) {

    // Rule: PM-GA-003
    // Rule: PM-SEC-001
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@PathVariable entranceId: UUID, @RequestBody request: OpenPetitionRequest, signedIn: JwtAuthenticationToken?): PetitionView {
        val owner = acting.party(who.of(signedIn), Action.SIGN_PETITION, entranceId)
        return view(petitions.read(entranceId, petitions.open(entranceId, owner, request.subject).id))
    }

    // Rule: PM-GA-003
    // Rule: PM-SEC-001
    @PostMapping("/{petitionId}/signatures")
    @ResponseStatus(HttpStatus.CREATED)
    fun sign(@PathVariable entranceId: UUID, @PathVariable petitionId: UUID, signedIn: JwtAuthenticationToken?): PetitionView {
        petitions.sign(entranceId, petitionId, acting.party(who.of(signedIn), Action.SIGN_PETITION, entranceId))
        return view(petitions.read(entranceId, petitionId))
    }

    @GetMapping("/{petitionId}")
    fun read(@PathVariable entranceId: UUID, @PathVariable petitionId: UUID): PetitionView = view(petitions.read(entranceId, petitionId))

    // Rule: PM-GA-003
    // Rule: PM-SEC-001
    @PostMapping("/{petitionId}/assembly")
    @ResponseStatus(HttpStatus.CREATED)
    fun convene(
        @PathVariable entranceId: UUID, @PathVariable petitionId: UUID, @RequestBody request: ConveneOnPetition, signedIn: JwtAuthenticationToken?,
    ): PetitionConvened =
        petitions.convene(entranceId, petitionId, acting.party(who.of(signedIn), Action.CONVENE_ON_PETITION, entranceId), request).let { (assembly, unlock) ->
            PetitionConvened(assembly.id, assembly.status, assembly.convenedAs, unlock.heldPct.percent(), unlock.thresholdPct.percent())
        }

    private fun view(read: PetitionRead) = read.weight.let { w ->
        PetitionView(
            read.petition.id, read.petition.subject, read.petition.openedBy, read.petition.openedAt, read.signatories, w.on,
            w.heldPct.percent(), w.thresholdPct.percent(), w.thresholdSource, w.thresholdVerified, w.cannotWeigh, w.derivedParts,
            w.unlocked, read.assemblyId,
        )
    }

    /** The policy refuses, or nobody a record could name is asking. */
    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused): Map<String, String> = mapOf("error" to (e.message ?: "not allowed"), "rule" to e.ruleId)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid request"))

    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** Already signed, already convened on, cannot be weighed, or holds too little. */
    @ExceptionHandler(IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: IllegalStateException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))
}
