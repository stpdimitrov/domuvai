package zues.app.registry

import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * The registry's published view of a unit for charging — the subset another module needs
 * to compute a run. Money depends on this port, never on `registry`'s tables (ADR-003).
 *
 * `occupants` is the current headcount and `childrenUnder6` the subset that a management or
 * maintenance charge excludes (Rules PM-FEE-008, PM-FEE-005); `animals` are the current pets,
 * each an occupant-equivalent (Rule PM-FEE-009).
 */
data class UnitForCharging(
    val unitId: UUID,
    val designation: String,
    val idealParts: String,        // exact decimal percent, e.g. "60.0000"
    val separateEntrance: Boolean,
    val occupants: Int = 0,
    val childrenUnder6: Int = 0,
    val animals: Int = 0,
)

/** The registry module's API for reading units. Implemented in-module by [UnitsAdapter]. */
interface Units {
    /**
     * The entrance's units with occupancy **as of [on]** — the billing period's legal date,
     * not today. A charge for a past period must use that period's headcount, or the bill is
     * both wrong and irreproducible (PM-FEE-014).
     */
    fun forEntrance(entranceId: UUID, on: LocalDate): List<UnitForCharging>
}

@Component
class UnitsAdapter(
    private val units: PropertyUnitRepository,
    private val household: HouseholdMemberRepository,
    private val animals: AnimalRepository,
) : Units {
    override fun forEntrance(entranceId: UUID, on: LocalDate): List<UnitForCharging> =
        units.findByEntranceId(entranceId).map { unit ->
            val residents = household.findByUnitId(unit.id).filter { current(it.validFrom, it.validTo, on) }
            val pets = animals.findByUnitId(unit.id).filter { current(it.validFrom, it.validTo, on) }
            UnitForCharging(
                unitId = unit.id,
                designation = unit.designation,
                idealParts = unit.idealPartsPct.toPlainString(),
                separateEntrance = unit.separateEntrance,
                occupants = residents.size,
                childrenUnder6 = residents.count { it.isChildUnder6 },
                animals = pets.size,
            )
        }

    /** In residence on [on]: the half-open interval [validFrom, validTo) contains it. */
    private fun current(from: LocalDate, to: LocalDate?, on: LocalDate) = from <= on && (to == null || on < to)
}
