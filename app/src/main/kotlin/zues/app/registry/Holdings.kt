package zues.app.registry

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One title in force on a date, as another module needs it to weigh a holder: who, which unit, as owner
 * or holder of a right of use, for what share, and the ideal parts that share stands for. It carries ids and
 * numbers only — no name, no identity number, no address: nothing PM-BOOK-011 keeps from a list.
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
    val unitIdealParts: String,     // exact decimal percent, e.g. "12.5"
    val idealParts: String,         // unitIdealParts × share, exact, e.g. "6.25"
    val idealPartsSource: String,   // DECLARED | DERIVED
)

/** A household member in residence on a date who is a known party. The book holds no date of birth, so no age. */
data class Resident(val partyId: UUID, val unitId: UUID)

/**
 * The titles of one role in force on one date, read once: the [holdings], and the units they over-hold.
 *
 * A unit in [overHeldUnitIds] has shares of this role that sum to more than the whole unit — more than the
 * rounding of its shares can explain. Its holdings add up to more than the unit's ideal parts, so weighing
 * them counts the unit more than once (Rule: PM-ORG-005): a consumer refuses such a unit rather than weigh it.
 */
data class HeldOn(val holdings: List<Holding>, val overHeldUnitIds: List<UUID>)

/**
 * The registry's published view of who holds what — the port another module weighs holders through,
 * never `registry`'s tables (ADR-003). Every answer is **as of the date asked** (Rule: PM-ORG-011).
 */
interface Holdings {
    /**
     * The entrance's titles of one [role] in force on [on] — the half-open `[validFrom, validTo)` contains it.
     * One role per call, on purpose: an owner and a holder of a right of use over the same unit each carry
     * the unit's ideal parts, so a sum across roles would count the unit twice (Rule: PM-ORG-005). Which
     * of them is weighed when a unit has both is the caller's decision, not the registry's.
     */
    fun inForce(entranceId: UUID, on: LocalDate, role: TitleRole): HeldOn

    /** The entrance's household members in residence on [on] who are tied to a party. */
    fun residents(entranceId: UUID, on: LocalDate): List<Resident>
}

/** Half a unit of a share's last decimal as the schema stores it — `title.share numeric(7,6)`. A precision, not a legal number. */
private val HALF_LAST_DECIMAL = BigDecimal("0.0000005")

@Component
class HoldingsAdapter(
    private val titles: TitleRepository,
    private val units: PropertyUnitRepository,
    private val household: HouseholdMemberRepository,
) : Holdings {

    // Rule: PM-ORG-011
    // Rule: PM-ORG-004
    // Rule: PM-ORG-005
    @Transactional(readOnly = true)
    override fun inForce(entranceId: UUID, on: LocalDate, role: TitleRole): HeldOn {
        val unitById = units.findByEntranceId(entranceId).associateBy { it.id }
        // A title filed under this entrance for a unit of another is answered for neither: the unit is not this
        // entrance's to weigh. The schema does not forbid such a row; the registry's own writes never make one.
        val inForce = titles.findByEntranceId(entranceId)
            .filter { it.titleRole == role.name && it.unitId in unitById && current(it.validFrom, it.validTo, on) }
        val holdings = inForce.map { title ->
            val unit = unitById.getValue(title.unitId)
            Holding(
                partyId = title.partyId,
                unitId = title.unitId,
                titleRole = title.titleRole,
                share = plain(title.share),
                unitIdealParts = plain(unit.idealPartsPct),
                idealParts = plain(unit.idealPartsPct * title.share),
                idealPartsSource = unit.idealPartsSource,
            )
        }
        val overHeld = inForce.groupBy({ it.unitId }, { it.share }).filterValues(::exceedsTheWhole).keys.sortedBy { it.toString() }
        return HeldOn(holdings, overHeld)
    }

    @Transactional(readOnly = true)
    override fun residents(entranceId: UUID, on: LocalDate): List<Resident> =
        household.findByEntranceId(entranceId)
            .filter { current(it.validFrom, it.validTo, on) }
            .mapNotNull { member -> member.partyId?.let { Resident(it, member.unitId) } }

    /**
     * More than the whole, beyond what rounding explains. A share is stored to a fixed number of decimals, so
     * six co-owners of a sixth each are six times 0.166667 — a sum just over 1 that is nobody's second title.
     * Each stored share is off by at most half a unit of its last decimal; a sum past the whole by more than
     * that, share for share, is a real excess. The bound follows from the column's precision; it is no legal number.
     */
    private fun exceedsTheWhole(shares: List<BigDecimal>): Boolean =
        shares.fold(BigDecimal.ZERO, BigDecimal::add) > BigDecimal.ONE + HALF_LAST_DECIMAL * BigDecimal(shares.size)

    /** In force on [on]: the half-open interval [from, to) contains it. */
    private fun current(from: LocalDate, to: LocalDate?, on: LocalDate) = from <= on && (to == null || on < to)

    /** An exact decimal with no padding zeros and no exponent: 6.250000 → "6.25", 100.0000 × 1 → "100". */
    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}
