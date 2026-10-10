package zues.app.registry

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One title in force on a date, as another module needs it to weigh a holder: who, which unit, as owner
 * or user, for what share, and the ideal parts that share stands for. It carries ids and numbers only —
 * no name, no identity number, no address (Rule: PM-BOOK-011).
 *
 * [idealParts] is the unit's ideal parts × the title's share, an exact decimal percent (ADR-006): a
 * holder's weight is a sum of these, never a count of units (Rule: PM-ORG-004), and the co-owners of a
 * unit sum to the unit's parts (Rule: PM-ORG-005). [idealPartsSource] says whether the unit's parts were
 * declared or derived from its area (PM-ORG-003), so whoever weighs them can warn.
 */
data class Holding(
    val partyId: UUID,
    val unitId: UUID,
    val titleRole: String,          // OWN | USR
    val share: String,              // exact decimal in (0, 1], e.g. "0.5"
    val unitIdealParts: String,     // exact decimal percent, e.g. "12.5000"
    val idealParts: String,         // unitIdealParts × share, exact, e.g. "6.25"
    val idealPartsSource: String,   // DECLARED | DERIVED
)

/** A household member in residence on a date who is a known party, with the book's one fact about age. */
data class Resident(val partyId: UUID, val unitId: UUID, val childUnder6: Boolean)

/**
 * The registry's published view of who holds what — the port another module weighs holders through,
 * never `registry`'s tables (ADR-003). Every answer is **as of the date asked** (Rule: PM-ORG-011).
 */
interface Holdings {
    /** The entrance's titles in force on [on] — the half-open `[validFrom, validTo)` contains it. */
    fun inForce(entranceId: UUID, on: LocalDate): List<Holding>

    /**
     * The units whose ownership shares in force on [on] sum to more than the whole unit. Their owners'
     * holdings add up to more than the unit's ideal parts, so counting them counts the unit more than
     * once (Rule: PM-ORG-005): a consumer refuses such a unit rather than weigh it.
     */
    fun overOwnedUnits(entranceId: UUID, on: LocalDate): List<UUID>

    /** The entrance's household members in residence on [on] who are tied to a party. */
    fun residents(entranceId: UUID, on: LocalDate): List<Resident>
}

@Component
class HoldingsAdapter(
    private val titles: TitleRepository,
    private val units: PropertyUnitRepository,
    private val household: HouseholdMemberRepository,
) : Holdings {

    // Rule: PM-ORG-011
    // Rule: PM-ORG-004
    // Rule: PM-BOOK-011
    @Transactional(readOnly = true)
    override fun inForce(entranceId: UUID, on: LocalDate): List<Holding> {
        val unitById = units.findByEntranceId(entranceId).associateBy { it.id }
        return titlesInForce(entranceId, on).map { title ->
            val unit = unitById.getValue(title.unitId)
            Holding(
                partyId = title.partyId,
                unitId = title.unitId,
                titleRole = title.titleRole,
                share = plain(title.share),
                unitIdealParts = unit.idealPartsPct.toPlainString(),
                idealParts = plain(unit.idealPartsPct * title.share),
                idealPartsSource = unit.idealPartsSource,
            )
        }
    }

    // Rule: PM-ORG-005
    @Transactional(readOnly = true)
    override fun overOwnedUnits(entranceId: UUID, on: LocalDate): List<UUID> =
        titlesInForce(entranceId, on).filter { it.titleRole == TitleRole.OWN.name }
            .groupBy({ it.unitId }, { it.share })
            .filterValues { shares -> shares.fold(BigDecimal.ZERO, BigDecimal::add) > BigDecimal.ONE }
            .keys.sortedBy { it.toString() }

    // Rule: PM-ORG-011
    @Transactional(readOnly = true)
    override fun residents(entranceId: UUID, on: LocalDate): List<Resident> =
        household.findByEntranceId(entranceId)
            .filter { inForce(it.validFrom, it.validTo, on) }
            .mapNotNull { member -> member.partyId?.let { Resident(it, member.unitId, member.isChildUnder6) } }

    private fun titlesInForce(entranceId: UUID, on: LocalDate) =
        titles.findByEntranceId(entranceId).filter { inForce(it.validFrom, it.validTo, on) }

    /** In force on [on]: the half-open interval [from, to) contains it. */
    private fun inForce(from: LocalDate, to: LocalDate?, on: LocalDate) = from <= on && (to == null || on < to)

    /** An exact decimal with no padding zeros and no exponent: 6.250000 → "6.25", 100.0000 × 1 → "100". */
    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}
