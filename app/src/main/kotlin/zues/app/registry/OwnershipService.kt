package zues.app.registry

import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Register a party the book will record (Rule: PM-BOOK-002). Identity is optional. */
data class RegisterParty(
    val fullName: String,
    val idType: String? = null,
    val idValue: String? = null,
)

/** Assign a party a title over a unit (Rule: PM-ORG-005, PM-ORG-011). `validFrom` defaults to today;
 *  `validTo` bounds it — a sale ends the seller's title on the day the buyer's begins. */
data class AssignTitle(
    val partyId: UUID,
    val titleRole: String,
    val share: String = "1",
    val validFrom: String? = null,
    val validTo: String? = null,
)

/**
 * The owner or user of a unit as of a date — the resident-visible view. It carries the party's
 * **name only**: identity numbers never appear in a list other residents can read (Rule:
 * PM-BOOK-011).
 */
data class OwnerView(
    val unitId: UUID,
    val partyName: String,
    val titleRole: String,
    val share: BigDecimal,
)

/**
 * Parties and their titles over units — who a charge is owed by. Ownership is effective-dated, so
 * the liable party resolves **as of** the charge, vote or arrears date, never as of today (Rule:
 * PM-ORG-011). A co-owned unit is several titles that split by share (Rule: PM-ORG-005).
 */
@Service
class OwnershipService(
    private val aggregates: JdbcAggregateTemplate,
    private val parties: PartyRepository,
    private val titles: TitleRepository,
    private val units: PropertyUnitRepository,
    private val clock: Clock,
) {
    @Transactional
    fun registerParty(command: RegisterParty): UUID {
        require(command.fullName.isNotBlank()) { "fullName must not be blank" }
        command.idType?.let { enumValueOf<IdType>(it) }   // unknown id type -> 400
        return aggregates.insert(
            Party(UUID.randomUUID(), command.fullName, command.idType, command.idValue),
        ).id
    }

    @Transactional
    fun assignTitle(entranceId: UUID, unitId: UUID, command: AssignTitle): UUID {
        val unit = units.findById(unitId).orElseThrow { NoSuchElementException("no unit $unitId") }
        if (unit.entranceId != entranceId) {
            throw NoSuchElementException("unit $unitId is not in entrance $entranceId")
        }
        if (!parties.existsById(command.partyId)) {
            throw NoSuchElementException("no party ${command.partyId}")
        }
        val role = enumValueOf<TitleRole>(command.titleRole)                  // unknown role -> 400
        val share = BigDecimal(command.share)
        require(share > BigDecimal.ZERO && share <= BigDecimal.ONE) {         // Rule: PM-ORG-005
            "share must be within (0, 1]: ${command.share}"
        }
        val from = command.validFrom?.let { LocalDate.parse(it) } ?: LocalDate.now(clock)
        val to = command.validTo?.let { LocalDate.parse(it) }
        require(to == null || to > from) { "validTo ($to) must be after validFrom ($from)" }
        return aggregates.insert(
            Title(
                id = UUID.randomUUID(),
                entranceId = entranceId,
                unitId = unitId,
                partyId = command.partyId,
                titleRole = role.name,
                share = share,
                validFrom = from,
                validTo = to,
            ),
        ).id
    }

    /**
     * The entrance's titles in force on [on] (Rule: PM-ORG-011), each with its party's name — never
     * an identity number (Rule: PM-BOOK-011).
     */
    @Transactional(readOnly = true)
    fun ownersAsOf(entranceId: UUID, on: LocalDate): List<OwnerView> {
        val current = titles.findByEntranceId(entranceId).filter { inForce(it.validFrom, it.validTo, on) }
        val nameById = parties.findAllById(current.map { it.partyId }.distinct()).associate { it.id to it.fullName }
        return current.map { OwnerView(it.unitId, nameById[it.partyId] ?: "?", it.titleRole, it.share) }
    }

    /** In force on [on]: the half-open interval [validFrom, validTo) contains it. */
    private fun inForce(from: LocalDate, to: LocalDate?, on: LocalDate) = from <= on && (to == null || on < to)
}
