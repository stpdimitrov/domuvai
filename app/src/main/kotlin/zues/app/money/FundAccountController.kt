package zues.app.money

import org.springframework.dao.DataIntegrityViolationException
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
import java.util.UUID

data class RegisterFundAccountRequest(
    val iban: String,
    val purpose: String,        // REPAIR_RENEWAL | OPERATING
    val holderName: String,
    val holderKind: String,     // MANAGER | ASSOCIATION
    val holderPartyId: String? = null,
)

data class FundAccountView(
    val id: UUID,
    val iban: String,
    val purpose: String,
    val holderName: String,
    val holderKind: String,
    val holderParty: UUID?,
)

/**
 * The fund's account record (Rule: PM-FUND-001, PM-FUND-004). Registers and reads an entrance's
 * external чл. 50 account; the platform never holds the money (ADR-007), so no balance is served
 * here — that is a later, derived read.
 */
@RestController
@RequestMapping("/api/money/entrances/{entranceId}/fund-accounts")
class FundAccountController(private val fund: FundAccountService) {

    @PostMapping
    fun register(
        @PathVariable entranceId: UUID,
        @RequestBody request: RegisterFundAccountRequest,
    ): ResponseEntity<FundAccountRegistered> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            fund.register(
                entranceId,
                RegisterFundAccount(
                    request.iban, request.purpose, request.holderName, request.holderKind, request.holderPartyId,
                ),
            ),
        )

    @GetMapping
    fun list(@PathVariable entranceId: UUID): List<FundAccountView> =
        fund.list(entranceId).map {
            FundAccountView(it.id, it.iban, it.purpose, it.holderName, it.holderKind, it.holderParty)
        }

    /** A malformed IBAN, an unknown purpose or holder kind, or a blank holder → 400. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> =
        mapOf("error" to (e.message ?: "invalid fund account"))

    /** A duplicate IBAN, or a second account of the same purpose → 409. */
    @ExceptionHandler(FundAccountConflict::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: FundAccountConflict): Map<String, String> =
        mapOf("error" to (e.message ?: "already registered"))

    /** Backstop for the table's own constraints: a raced duplicate, or an unknown entrance. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onIntegrity(e: DataIntegrityViolationException): Map<String, String> =
        mapOf("error" to "the account conflicts with an existing record, or the entrance or holder party does not exist")
}
