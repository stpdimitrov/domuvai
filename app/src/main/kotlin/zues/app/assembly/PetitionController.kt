package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class OpenPetitionRequest(val openedBy: UUID, val subject: String)

data class SignPetitionRequest(val partyId: UUID)

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

/** The owners' petition to convene a general assembly, and convening on it (PM-GA-003). */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/petitions")
class PetitionController(private val petitions: PetitionService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@PathVariable entranceId: UUID, @RequestBody request: OpenPetitionRequest): PetitionView =
        view(petitions.read(entranceId, petitions.open(entranceId, request.openedBy, request.subject).id))

    @PostMapping("/{petitionId}/signatures")
    @ResponseStatus(HttpStatus.CREATED)
    fun sign(@PathVariable entranceId: UUID, @PathVariable petitionId: UUID, @RequestBody request: SignPetitionRequest): PetitionView {
        petitions.sign(entranceId, petitionId, request.partyId)
        return view(petitions.read(entranceId, petitionId))
    }

    @GetMapping("/{petitionId}")
    fun read(@PathVariable entranceId: UUID, @PathVariable petitionId: UUID): PetitionView = view(petitions.read(entranceId, petitionId))

    @PostMapping("/{petitionId}/assembly")
    @ResponseStatus(HttpStatus.CREATED)
    fun convene(@PathVariable entranceId: UUID, @PathVariable petitionId: UUID, @RequestBody request: ConveneOnPetition): PetitionConvened =
        petitions.convene(entranceId, petitionId, request).let { (assembly, unlock) ->
            PetitionConvened(assembly.id, assembly.status, assembly.convenedAs, unlock.heldPct.percent(), unlock.thresholdPct.percent())
        }

    private fun view(read: PetitionRead) = read.weight.let { w ->
        PetitionView(
            read.petition.id, read.petition.subject, read.petition.openedBy, read.petition.openedAt, read.signatories, w.on,
            w.heldPct.percent(), w.thresholdPct.percent(), w.thresholdSource, w.thresholdVerified, w.cannotWeigh, w.derivedParts,
            w.unlocked, read.assemblyId,
        )
    }

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
