package zues.app.registry

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The registry's published view of a unit for charging — the subset another module needs
 * to compute a run. Money depends on this port, never on `registry`'s tables (ADR-003).
 */
data class UnitForCharging(
    val unitId: UUID,
    val designation: String,
    val idealParts: String,        // exact decimal percent, e.g. "60.0000"
    val separateEntrance: Boolean,
)

/** The registry module's API for reading units. Implemented in-module by [UnitsAdapter]. */
interface Units {
    fun forEntrance(entranceId: UUID): List<UnitForCharging>
}

@Component
class UnitsAdapter(private val units: PropertyUnitRepository) : Units {
    override fun forEntrance(entranceId: UUID): List<UnitForCharging> =
        units.findByEntranceId(entranceId).map {
            UnitForCharging(it.id, it.designation, it.idealPartsPct.toPlainString(), it.separateEntrance)
        }
}
