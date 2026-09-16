package zues.app.money

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Issues (persists) a charge run for an entrance from its stored units. Distinct from the
 * `/preview` path, which only computes. The stored run is immutable (PM-FEE-015).
 */
@RestController
@RequestMapping("/api/money/entrances/{entranceId}/charge-runs")
class ChargeRunStoreController(private val store: ChargeRunStore) {

    @PostMapping
    fun issue(
        @PathVariable entranceId: UUID,
        @RequestBody request: StoredChargeRunRequest,
    ): ResponseEntity<ChargeRunIssued> =
        ResponseEntity.status(HttpStatus.CREATED).body(store.issue(entranceId, request))

    /** Unlawful run, unknown key, or PER_PERSON before occupancy exists → 400. */
    @ExceptionHandler(IllegalStateException::class, IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: RuntimeException): Map<String, String> = mapOf("error" to (e.message ?: "invalid charge run"))

    /** No entrance, or an entrance with no units. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** The period is already billed — a bill is not reissued. */
    @ExceptionHandler(ChargeRunAlreadyIssued::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onDuplicate(e: ChargeRunAlreadyIssued): Map<String, String> = mapOf("error" to (e.message ?: "already issued"))
}
