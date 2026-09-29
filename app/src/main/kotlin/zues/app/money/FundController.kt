package zues.app.money

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
import java.util.UUID

/** A disbursement to sign off (PM-FUND-006…008). Until sign-in exists, the caller names the signatory. */
data class CommitDisbursementRequest(
    val amountMinor: Long,
    val purpose: String,                          // WORKS | PASSPORT_MEASURE | GA_PURPOSE
    val authorisedBy: UUID,                       // the party holding the fund's account (PM-FUND-007)
    val decisionId: String? = null,               // the GA decision — or, instead,
    val emergencyJustification: String? = null,   // an emergency's written justification (PM-FUND-008)
    val passportMeasure: String? = null,          // required for PASSPORT_MEASURE (PM-FUND-006)
)

/**
 * The repair and renewal fund (Rule: PM-FUND-009): its balance, what is committed and what is
 * available, and the disbursements signed off against it (Rule: PM-FUND-006, PM-FUND-007, PM-FUND-008).
 */
@RestController
@RequestMapping("/api/money/entrances/{entranceId}/fund")
class FundController(private val fund: FundService) {

    @GetMapping
    fun view(@PathVariable entranceId: UUID): FundView = fund.view(entranceId)

    @PostMapping("/disbursements")
    @ResponseStatus(HttpStatus.CREATED)
    fun commit(@PathVariable entranceId: UUID, @RequestBody request: CommitDisbursementRequest): DisbursementView =
        fund.commit(
            entranceId,
            CommitDisbursement(
                request.amountMinor, request.purpose, request.authorisedBy,
                request.decisionId, request.emergencyJustification, request.passportMeasure,
            ),
        )

    /** An unknown purpose, a missing basis or measure, or a signatory who does not hold the account → 400. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid disbursement"))

    /** No repair and renewal fund account for the entrance → 404. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** An emergency the available balance does not cover, or an account holder nobody can sign for → 409. */
    @ExceptionHandler(FundShortfall::class, IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: RuntimeException): Map<String, String> = mapOf("error" to (e.message ?: "conflicts with the fund's state"))

    /** Backstop for the table's own checks. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onIntegrity(e: DataIntegrityViolationException): Map<String, String> =
        mapOf("error" to "the disbursement conflicts with the fund's records")
}
