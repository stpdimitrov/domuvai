package zues.app.money

import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Records a payment for a unit and reads back how it was allocated (Rule: PM-DEBT-008). The
 * `Idempotency-Key` makes a retry safe: the same key returns the payment already recorded, with
 * the same response. Access will be narrowed by the authorization module (ADR-002).
 */
@RestController
@RequestMapping("/api/money/entrances/{entranceId}/payments")
class PaymentController(private val payments: PaymentService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        @PathVariable entranceId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: RecordPaymentRequest,
    ): PaymentView = payments.record(entranceId, idempotencyKey, request)

    @GetMapping("/{paymentId}")
    fun find(@PathVariable entranceId: UUID, @PathVariable paymentId: UUID): PaymentView =
        payments.find(entranceId, paymentId)

    /** A non-positive amount, a malformed or future date, an unknown or unregistered account, a debt not owed → 400. */
    @ExceptionHandler(IllegalArgumentException::class, DateTimeParseException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: RuntimeException): Map<String, String> = mapOf("error" to (e.message ?: "invalid payment"))

    /** No such payment, or a unit that is not in the entrance. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** Dated before a payment already recorded for the unit — record it on or after that date. */
    @ExceptionHandler(PaymentOutOfOrder::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onOutOfOrder(e: PaymentOutOfOrder): Map<String, String> = mapOf("error" to (e.message ?: "out of order"))

    /** The key was already used for a different payment — found, or lost in a concurrent race. */
    @ExceptionHandler(IdempotencyKeyReused::class, DuplicateKeyException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onReused(e: RuntimeException): Map<String, String> =
        mapOf("error" to "Idempotency-Key was already used for a different payment")
}
