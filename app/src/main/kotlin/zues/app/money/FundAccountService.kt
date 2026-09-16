package zues.app.money

import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** File a condominium's external bank account (Rule: PM-FUND-004). Holder name and kind required;
 *  `holderPartyId` links the holder to a book party when there is one. */
data class RegisterFundAccount(
    val iban: String,
    val purpose: String,       // FundPurpose
    val holderName: String,
    val holderKind: String,    // HolderKind
    val holderPartyId: String? = null,
)

/** What registering an account returns. */
data class FundAccountRegistered(
    val fundAccountId: UUID,
    val entranceId: UUID,
    val iban: String,
    val purpose: String,
)

/**
 * An account of this purpose already exists for the entrance, or this IBAN is already
 * registered — the fund cannot equal the operating account (PM-FUND-004) and monies cannot be
 * commingled (PM-FUND-005).
 */
class FundAccountConflict(message: String) : RuntimeException(message)

/**
 * Registers an entrance's external чл. 50 account (Rule: PM-FUND-001, PM-FUND-004). The platform
 * holds no money (ADR-007), so only the account's identity is stored — never a balance. Uniqueness
 * is checked here and enforced again by the table: no two accounts share an IBAN, and an entrance
 * keeps at most one account per purpose, so the fund is always distinct from the operating account
 * (PM-FUND-004) and cannot be commingled (PM-FUND-005).
 */
@Service
class FundAccountService(
    private val aggregates: JdbcAggregateTemplate,
    private val accounts: FundAccountRepository,
) {
    @Transactional
    fun register(entranceId: UUID, command: RegisterFundAccount): FundAccountRegistered {
        val purpose = enumValueOf<FundPurpose>(command.purpose)   // unknown value -> 400
        val holderKind = enumValueOf<HolderKind>(command.holderKind)
        require(command.holderName.isNotBlank()) { "holderName must not be blank" }
        val holderParty = command.holderPartyId?.let { UUID.fromString(it) }   // malformed uuid -> 400
        val iban = Iban.normalize(command.iban)

        // Rule: PM-FUND-005 — one IBAN across all entrances, so fund monies are never commingled.
        if (accounts.existsByIban(iban)) {
            throw FundAccountConflict("IBAN $iban is already registered — fund monies are not commingled (PM-FUND-005)")
        }
        // Rule: PM-FUND-004 — one account per purpose, so the fund cannot equal the operating account.
        if (accounts.existsByEntranceIdAndPurpose(entranceId, purpose.name)) {
            throw FundAccountConflict("entrance $entranceId already has a ${purpose.name} account")
        }
        val row = aggregates.insert(
            FundAccountRow(
                id = UUID.randomUUID(),
                entranceId = entranceId,
                iban = iban,
                purpose = purpose.name,
                holderName = command.holderName,
                holderKind = holderKind.name,
                holderParty = holderParty,   // the FK backstops a party that does not exist
            ),
        )
        return FundAccountRegistered(row.id, entranceId, row.iban, row.purpose)
    }

    @Transactional(readOnly = true)
    fun list(entranceId: UUID): List<FundAccountRow> = accounts.findByEntranceId(entranceId)
}
