package zues.app.money

import org.springframework.dao.DataIntegrityViolationException
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
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** A handover to state the fund for (PM-FUND-010): ISO dates, the two sides, and the balance on the bank's own statement. */
data class IssueHandoverRequest(
    val handoverOn: String,
    val from: String? = null,                     // the period's first day; without it, from the fund's first record
    val outgoingPartyId: UUID,
    val incomingPartyId: UUID,
    val bankBalanceMinor: Long? = null,           // required: the balance on the bank's own statement, never a default
)

/**
 * The repair fund's handover statements (Rule: PM-FUND-010): issued once, read back as issued, listed newest first.
 * Issuing takes an `Idempotency-Key` (DEVBRIEF §8): a repeat returns the statement first issued, never a second one.
 */
@RestController
@RequestMapping("/api/money/entrances/{entranceId}/fund/handover-statements")
class FundHandoverController(private val handovers: FundHandoverService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(
        @PathVariable entranceId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: IssueHandoverRequest,
    ): HandoverStatementView =
        handovers.issue(
            entranceId, idempotencyKey,
            IssueHandover(
                LocalDate.parse(request.handoverOn), request.from?.let(LocalDate::parse),
                request.outgoingPartyId, request.incomingPartyId,
                requireNotNull(request.bankBalanceMinor) { "bankBalanceMinor is the balance on the bank's own statement for the handover date — required" },
            ),
        )

    @GetMapping
    fun list(@PathVariable entranceId: UUID): List<HandoverStatementView> = handovers.list(entranceId)

    @GetMapping("/{statementId}")
    fun find(@PathVariable entranceId: UUID, @PathVariable statementId: UUID): HandoverStatementView = handovers.find(entranceId, statementId)

    /** A missing bank balance, a handover date after today, a period starting after it, or one party on both sides → 400. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid handover"))

    @ExceptionHandler(DateTimeParseException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onBadDate(e: DateTimeParseException): Map<String, String> = mapOf("error" to "handoverOn and from are ISO dates (YYYY-MM-DD)")

    /** No repair fund account, or no such statement for the entrance → 404. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** A key already used for a different request, or taken by one racing this → 409. */
    @ExceptionHandler(IdempotencyKeyReused::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onKeyReused(e: RuntimeException): Map<String, String> =
        mapOf("error" to "Idempotency-Key was already used for a different request")

    /** Backstop for the table: a party that is not registered, or a check the statement fails → 409. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onIntegrity(e: DataIntegrityViolationException): Map<String, String> =
        mapOf("error" to "the statement conflicts with the fund's records, or a party named on it is not registered")
}
