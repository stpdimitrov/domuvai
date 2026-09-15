package zues.app.registry

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import zues.kernel.IdealParts
import zues.kernel.assertPartsSumTo100
import java.math.BigDecimal
import java.util.UUID

/**
 * A самостоятелен обект (property unit) as the registry stores it — the system of record
 * for its ideal parts and identity. Named `PropertyUnit`, like the charge engine's input
 * type, because it is the same concept; the two differ only in representation.
 *
 * Mapped unqualified; the search_path resolves `unit` to `registry.unit`.
 */
@Table("unit")
data class PropertyUnit(
    @Id val id: UUID,
    val entranceId: UUID,
    val designation: String,
    val unitType: String,
    val areaM2: BigDecimal?,
    /** exact percent, numeric(7,4) — the schema's precision, not the kernel's six decimals */
    val idealPartsPct: BigDecimal,
    /** Rule: PM-ORG-009 — separate street entrance for business use, drives the fee multiplier */
    val separateEntrance: Boolean,
)

interface PropertyUnitRepository : ListCrudRepository<PropertyUnit, UUID> {
    fun findByEntranceId(entranceId: UUID): List<PropertyUnit>
}

/**
 * The registry's ideal-parts rules, kept pure so they are proved without a database.
 * The DB carries the same invariant as a deferred trigger (a backstop, not the only check).
 */
object UnitValidation {

    // 4 decimals: the schema stores ideal parts as numeric(7,4). ppmPct is millionths of a
    // percent, so a value with at most 4 decimals is a multiple of 100.
    private const val PPM_PER_SCHEMA_STEP = 100

    /**
     * Rule: PM-ORG-002 — an entrance's units MUST carry ideal parts summing to exactly 100%.
     * Throws with the delta shown when they do not (the acceptance criterion).
     */
    fun requirePartsSumTo100(idealParts: List<String>) {
        val parts = idealParts.map { IdealParts.of(it) }
        parts.forEach {
            require(it.ppmPct % PPM_PER_SCHEMA_STEP == 0) {
                "ideal parts support at most 4 decimals (schema numeric(7,4)); got ${it.format()}% (PM-ORG-002)"
            }
        }
        assertPartsSumTo100(parts)
    }

    /** ppmPct (millionths of a percent) as the numeric(7,4) percent the column stores. */
    fun toColumn(idealParts: IdealParts): BigDecimal =
        BigDecimal(idealParts.ppmPct).movePointLeft(6).setScale(4)
}
