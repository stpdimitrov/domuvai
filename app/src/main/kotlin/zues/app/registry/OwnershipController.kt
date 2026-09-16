package zues.app.registry

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class RegisterPartyRequest(
    val fullName: String,
    val idType: String? = null,
    val idValue: String? = null,
)

data class PartyRegisteredResponse(val partyId: UUID)

data class AssignTitleRequest(
    val partyId: UUID,
    val titleRole: String,       // OWN | USR
    val share: String = "1",     // exact decimal in (0, 1]
    val validFrom: String? = null,
    val validTo: String? = null,
)

data class TitleAssignedResponse(val titleId: UUID)

/** The resident-visible owner line — name only, never an identity number (PM-BOOK-011). */
data class OwnerResponse(
    val unitId: UUID,
    val partyName: String,
    val titleRole: String,
    val share: BigDecimal,
)

/** Parties, their titles over units, and the owners read — who a charge is owed by. */
@RestController
@RequestMapping("/api/registry")
class OwnershipController(private val ownership: OwnershipService) {

    @PostMapping("/parties")
    fun registerParty(@RequestBody request: RegisterPartyRequest): ResponseEntity<PartyRegisteredResponse> {
        val id = ownership.registerParty(RegisterParty(request.fullName, request.idType, request.idValue))
        return ResponseEntity.status(HttpStatus.CREATED).body(PartyRegisteredResponse(id))
    }

    @PostMapping("/entrances/{entranceId}/units/{unitId}/titles")
    fun assignTitle(
        @PathVariable entranceId: UUID,
        @PathVariable unitId: UUID,
        @RequestBody request: AssignTitleRequest,
    ): ResponseEntity<TitleAssignedResponse> {
        val id = ownership.assignTitle(
            entranceId,
            unitId,
            AssignTitle(request.partyId, request.titleRole, request.share, request.validFrom, request.validTo),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(TitleAssignedResponse(id))
    }

    /** The owners/users of the entrance's units as of [on] (PM-ORG-011). Names only (PM-BOOK-011). */
    @GetMapping("/entrances/{entranceId}/owners")
    fun owners(@PathVariable entranceId: UUID, @RequestParam on: String): List<OwnerResponse> {
        val date = runCatching { LocalDate.parse(on) }
            .getOrElse { throw IllegalArgumentException("on must be an ISO date (YYYY-MM-DD): $on") }
        return ownership.ownersAsOf(entranceId, date)
            .map { OwnerResponse(it.unitId, it.partyName, it.titleRole, it.share) }
    }

    /** A blank name, an unknown id type or role, or a share outside (0, 1] → 400. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> =
        mapOf("error" to (e.message ?: "invalid request"))

    /** No such unit, entrance or party. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> =
        mapOf("error" to (e.message ?: "not found"))

    /** The title table's own guards: an overlapping same-role title, or a share out of range. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onIntegrity(e: DataIntegrityViolationException): Map<String, String> =
        mapOf("error" to "the title overlaps an existing one, or violates a table constraint")
}
