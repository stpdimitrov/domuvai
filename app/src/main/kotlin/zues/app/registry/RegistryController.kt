package zues.app.registry

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** A new building's [address], or the [condominiumId] of the building the entrance joins (PM-ORG-001). */
data class RegisterEntranceRequest(
    val address: String? = null,
    val label: String,
    val managementForm: String,
    val condominiumId: UUID? = null,
)

data class EntranceCreatedResponse(
    val entranceId: UUID,
    val condominiumId: UUID,
)

data class EntranceView(
    val id: UUID,
    val condominiumId: UUID,
    val label: String,
    val managementForm: String,
)

data class RegisterUnitsRequest(val units: List<NewUnitRequest>)

data class NewUnitRequest(
    val designation: String,
    val unitType: String,
    val areaM2: BigDecimal? = null,
    val idealParts: String? = null,  // exact decimal percent, e.g. "4.2000"; omit for every unit to derive from area (PM-ORG-003)
    val separateEntrance: Boolean = false,   // PM-ORG-009 — a separate street entrance
    val businessUse: Boolean = false,        // PM-FEE-010 — business or professional use; a fact apart from the entrance
)

data class UnitsCreatedResponse(val unitIds: List<UUID>)

data class UnitView(
    val id: UUID,
    val designation: String,
    val unitType: String,
    val areaM2: BigDecimal?,
    val idealPartsPct: BigDecimal,
    val separateEntrance: Boolean,
    val businessUse: Boolean,
    val idealPartsSource: String,    // PM-ORG-003 — DECLARED | DERIVED; a DERIVED value is shown with a warning
)

data class RegisterHouseholdRequest(val members: List<NewMemberRequest>)

data class NewMemberRequest(
    val isChildUnder6: Boolean = false,
    val validFrom: String? = null,   // ISO date; defaults to today
)

data class HouseholdRegisteredResponse(val memberIds: List<UUID>)

data class RegisterAnimalsRequest(val animals: List<NewAnimalRequest>)

data class NewAnimalRequest(
    val species: String,
    val vetPassportNo: String? = null,
    val validFrom: String? = null,
)

data class AnimalsRegisteredResponse(val animalIds: List<UUID>)

data class RegisterAbsencesRequest(val absences: List<NewAbsenceRequest>)

data class NewAbsenceRequest(
    val absentFrom: String,      // ISO date, inclusive
    val absentTo: String,        // ISO date, exclusive — the first day back
)

data class AbsencesRegisteredResponse(val absenceIds: List<UUID>)

/** The day a resident or an animal left the unit, as declared — ISO date, the first day not there. */
data class EndStayRequest(val on: String)

data class StayEnded(val id: UUID, val validFrom: LocalDate, val validTo: LocalDate)

/** The registry module's HTTP edge — entrances and their units. */
@RestController
@RequestMapping("/api/registry/entrances")
class RegistryController(private val registry: RegistryService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody request: RegisterEntranceRequest): EntranceCreatedResponse {
        val created = registry.registerEntrance(
            RegisterEntrance(request.address, request.label, request.managementForm, request.condominiumId),
        )
        return EntranceCreatedResponse(created.entranceId, created.condominiumId)
    }

    @GetMapping
    fun list(): List<EntranceView> =
        registry.listEntrances().map { EntranceView(it.id, it.condominiumId, it.label, it.managementForm) }

    @PostMapping("/{entranceId}/units")
    @ResponseStatus(HttpStatus.CREATED)
    fun registerUnits(
        @PathVariable entranceId: UUID,
        @RequestBody request: RegisterUnitsRequest,
    ): UnitsCreatedResponse {
        val ids = registry.registerUnits(
            entranceId,
            request.units.map {
                RegisterUnit(it.designation, it.unitType, it.areaM2, it.idealParts, it.separateEntrance, it.businessUse)
            },
        )
        return UnitsCreatedResponse(ids)
    }

    @PostMapping("/{entranceId}/units/{unitId}/household")
    @ResponseStatus(HttpStatus.CREATED)
    fun registerHousehold(
        @PathVariable entranceId: UUID,
        @PathVariable unitId: UUID,
        @RequestBody request: RegisterHouseholdRequest,
    ): HouseholdRegisteredResponse {
        val ids = registry.registerHousehold(
            entranceId,
            unitId,
            request.members.map { RegisterMember(it.isChildUnder6, it.validFrom) },
        )
        return HouseholdRegisteredResponse(ids)
    }

    @PostMapping("/{entranceId}/units/{unitId}/animals")
    @ResponseStatus(HttpStatus.CREATED)
    fun registerAnimals(
        @PathVariable entranceId: UUID,
        @PathVariable unitId: UUID,
        @RequestBody request: RegisterAnimalsRequest,
    ): AnimalsRegisteredResponse {
        val ids = registry.registerAnimals(
            entranceId,
            unitId,
            request.animals.map { RegisterAnimal(it.species, it.vetPassportNo, it.validFrom) },
        )
        return AnimalsRegisteredResponse(ids)
    }

    @PostMapping("/{entranceId}/units/{unitId}/absences")
    @ResponseStatus(HttpStatus.CREATED)
    fun registerAbsences(
        @PathVariable entranceId: UUID,
        @PathVariable unitId: UUID,
        @RequestBody request: RegisterAbsencesRequest,
    ): AbsencesRegisteredResponse {
        val ids = registry.registerAbsence(
            entranceId,
            unitId,
            request.absences.map { RegisterAbsence(it.absentFrom, it.absentTo) },
        )
        return AbsencesRegisteredResponse(ids)
    }

    @PostMapping("/{entranceId}/units/{unitId}/household/{memberId}/end")
    fun endHouseholdStay(
        @PathVariable entranceId: UUID,
        @PathVariable unitId: UUID,
        @PathVariable memberId: UUID,
        @RequestBody request: EndStayRequest,
    ): StayEnded {
        val on = LocalDate.parse(request.on)
        return registry.endHouseholdStay(entranceId, unitId, memberId, on).let { StayEnded(it.id, it.validFrom, on) }
    }

    @PostMapping("/{entranceId}/units/{unitId}/animals/{animalId}/end")
    fun endAnimalStay(
        @PathVariable entranceId: UUID,
        @PathVariable unitId: UUID,
        @PathVariable animalId: UUID,
        @RequestBody request: EndStayRequest,
    ): StayEnded {
        val on = LocalDate.parse(request.on)
        return registry.endAnimalStay(entranceId, unitId, animalId, on).let { StayEnded(it.id, it.validFrom, on) }
    }

    @GetMapping("/{entranceId}/units")
    fun listUnits(@PathVariable entranceId: UUID): List<UnitView> =
        registry.listUnits(entranceId).map {
            UnitView(it.id, it.designation, it.unitType, it.areaM2, it.idealPartsPct, it.separateEntrance, it.businessUse, it.idealPartsSource)
        }

    /**
     * Ideal parts that do not sum to 100%, or a malformed value, are a bad request (PM-ORG-002); so
     * are a malformed date and a move-out not after the move-in.
     */
    @ExceptionHandler(IllegalStateException::class, IllegalArgumentException::class, DateTimeParseException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: RuntimeException): Map<String, String> = mapOf("error" to (e.message ?: "invalid request"))

    /** A unit set aimed at an entrance that does not exist. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** A label its building already uses for another entrance, or a designation its entrance already has. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onIntegrity(e: DataIntegrityViolationException): Map<String, String> =
        mapOf("error" to "already registered: an entrance label repeats in its building, or a unit designation in its entrance")
}
