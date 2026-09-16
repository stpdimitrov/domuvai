package zues.app.registry

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The registry's published view of a unit for charging — the subset another module needs
 * to compute a run. Money depends on this port, never on `registry`'s tables (ADR-003).
 *
 * `occupants` is the current headcount and `childrenUnder6` the subset that a management or
 * maintenance charge excludes (Rules PM-FEE-008, PM-FEE-005).
 */
data class UnitForCharging(
    val unitId: UUID,
    val designation: String,
    val idealParts: String,        // exact decimal percent, e.g. "60.0000"
    val separateEntrance: Boolean,
    val occupants: Int = 0,
    val childrenUnder6: Int = 0,
)

/** The registry module's API for reading units. Implemented in-module by [UnitsAdapter]. */
interface Units {
    fun forEntrance(entranceId: UUID): List<UnitForCharging>
}

@Component
class UnitsAdapter(
    private val units: PropertyUnitRepository,
    private val household: HouseholdMemberRepository,
) : Units {
    override fun forEntrance(entranceId: UUID): List<UnitForCharging> =
        units.findByEntranceId(entranceId).map { unit ->
            val current = household.findByUnitId(unit.id).filter { it.validTo == null }
            UnitForCharging(
                unitId = unit.id,
                designation = unit.designation,
                idealParts = unit.idealPartsPct.toPlainString(),
                separateEntrance = unit.separateEntrance,
                occupants = current.size,
                childrenUnder6 = current.count { it.isChildUnder6 },
            )
        }
}
