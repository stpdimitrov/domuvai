package zues.app.registry

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

data class RegisterEntranceRequest(
    val address: String,
    val label: String,
    val managementForm: String,
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
    val idealParts: String,          // exact decimal percent, e.g. "4.2000"
    val separateEntrance: Boolean = false,
)

data class UnitsCreatedResponse(val unitIds: List<UUID>)

data class UnitView(
    val id: UUID,
    val designation: String,
    val unitType: String,
    val areaM2: BigDecimal?,
    val idealPartsPct: BigDecimal,
    val separateEntrance: Boolean,
)

/** The registry module's HTTP edge — entrances and their units. */
@RestController
@RequestMapping("/api/registry/entrances")
class RegistryController(private val registry: RegistryService) {

    @PostMapping
    fun register(@RequestBody request: RegisterEntranceRequest): ResponseEntity<EntranceCreatedResponse> {
        val created = registry.registerEntrance(
            RegisterEntrance(request.address, request.label, request.managementForm),
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(EntranceCreatedResponse(created.entranceId, created.condominiumId))
    }

    @GetMapping
    fun list(): List<EntranceView> =
        registry.listEntrances().map { EntranceView(it.id, it.condominiumId, it.label, it.managementForm) }

    @PostMapping("/{entranceId}/units")
    fun registerUnits(
        @PathVariable entranceId: UUID,
        @RequestBody request: RegisterUnitsRequest,
    ): ResponseEntity<UnitsCreatedResponse> {
        val ids = registry.registerUnits(
            entranceId,
            request.units.map {
                RegisterUnit(it.designation, it.unitType, it.areaM2, it.idealParts, it.separateEntrance)
            },
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(UnitsCreatedResponse(ids))
    }

    @GetMapping("/{entranceId}/units")
    fun listUnits(@PathVariable entranceId: UUID): List<UnitView> =
        registry.listUnits(entranceId).map {
            UnitView(it.id, it.designation, it.unitType, it.areaM2, it.idealPartsPct, it.separateEntrance)
        }

    /** Ideal parts that do not sum to 100%, or a malformed value, are a bad request (PM-ORG-002). */
    @ExceptionHandler(IllegalStateException::class, IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: RuntimeException): Map<String, String> = mapOf("error" to (e.message ?: "invalid request"))

    /** A unit set aimed at an entrance that does not exist. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))
}
