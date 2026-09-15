package zues.app.registry

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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

/** The registry module's HTTP edge for the walking skeleton. */
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
}
