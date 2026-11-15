package zues.app.money

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
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
    val mandateId: UUID?,
)

/**
 * The fund's account record (Rule: PM-FUND-001, PM-FUND-004). Registers and reads an entrance's
 * external чл. 50 account; the platform never holds the money (ADR-007), so no balance is served
 * here — that is a later, derived read.
 */
@RestController
@RequestMapping("/api/money/entrances/{entranceId}/fund-accounts")
class FundAccountController(private val fund: FundAccountService, private val access: MoneyAccess, private val who: WhoAsks) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @PathVariable entranceId: UUID,
        @RequestBody request: RegisterFundAccountRequest,
        signedIn: JwtAuthenticationToken?,
    ): FundAccountRegistered {
        access.keep(entranceId, who.of(signedIn))
        return fund.register(
            entranceId,
            RegisterFundAccount(
                request.iban, request.purpose, request.holderName, request.holderKind, request.holderPartyId,
            ),
        )
    }

    @GetMapping
    fun list(@PathVariable entranceId: UUID, signedIn: JwtAuthenticationToken?): List<FundAccountView> {
        val asking = who.of(signedIn)
        access.fund(entranceId, asking)
        val parties = access.seesFundParties(entranceId, asking)                       // Rule: PM-SEC-002
        return fund.list(entranceId).map {
            FundAccountView(it.id, it.iban, it.purpose, it.holderName, it.holderKind, it.holderParty.takeIf { parties }, it.mandateId.takeIf { parties })
        }
    }

    /** A malformed IBAN, an unknown purpose or holder kind, a blank holder, or a fund's manager with no mandate → 400. */
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
