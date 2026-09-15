package zues.app.money

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Charge runs computed from an entrance's stored units. The tariff is posted; the units are
 * read from `registry`. Still stateless — persistence is the next slice.
 */
@RestController
@RequestMapping("/api/money/entrances/{entranceId}/charge-runs")
class StoredChargeRunController(private val service: ChargeRunService) {

    @PostMapping("/preview")
    fun preview(
        @PathVariable entranceId: UUID,
        @RequestBody request: StoredChargeRunRequest,
    ): ChargeRunResponse = service.preview(entranceId, request)

    /** An unlawful run, an unknown allocation key, or PER_PERSON before occupancy exists → 400. */
    @ExceptionHandler(IllegalStateException::class, IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: RuntimeException): Map<String, String> = mapOf("error" to (e.message ?: "invalid charge run"))

    /** No entrance, or an entrance with no units. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))
}
