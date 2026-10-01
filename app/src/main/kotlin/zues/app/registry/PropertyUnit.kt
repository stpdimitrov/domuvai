package zues.app.registry

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import zues.kernel.IdealParts
import zues.kernel.allocateByWeight
import zues.kernel.assertPartsSumTo100
import zues.kernel.eur
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
    /** Rule: PM-ORG-009 — the unit has a separate street entrance. It does not say the unit is used for business. */
    val separateEntrance: Boolean,
    /** Provenance if adopted from a fee-sheet import (STAGE1-ADDENDUM §1); null if registered directly. */
    val importId: UUID? = null,
    /** Rule: PM-FEE-010 — used for business or professional activity. A fact of its own: it does not say how the unit is reached. */
    val businessUse: Boolean = false,
    /** Rule: PM-ORG-003 — DECLARED from the title deed, or DERIVED from the built-up area ratio. */
    val idealPartsSource: String = IdealPartsSource.DECLARED.name,
)

/** Where a unit's ideal parts came from (PM-ORG-003). A DERIVED value carries a warning wherever it is weighed. */
enum class IdealPartsSource { DECLARED, DERIVED }

interface PropertyUnitRepository : ListCrudRepository<PropertyUnit, UUID> {
    fun findByEntranceId(entranceId: UUID): List<PropertyUnit>
    fun findByImportId(importId: UUID): List<PropertyUnit>
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

/**
 * Rule: PM-ORG-003 — ideal parts derived from the built-up area ratio, for units whose title deeds
 * do not state them: each unit's area over the entrance's total. Exact and float-free: areas are
 * counted in hundredths of a square metre (the schema's numeric(10,2)), the 100% is counted in the
 * schema's steps of 0.0001%, and the kernel's largest-remainder split (ties to the earlier unit)
 * hands out the last steps, so the result sums to exactly 100.0000% (PM-ORG-002).
 */
object IdealPartsDerivation {

    private const val STEPS_IN_WHOLE = 1_000_000L   // 100.0000% in steps of 0.0001%
    private val MAX_AREA_M2 = BigDecimal("99999999.99")   // the schema's numeric(10,2); keeps the arithmetic in Long

    fun byArea(areasM2: List<BigDecimal>): List<IdealParts> {
        require(areasM2.isNotEmpty()) { "no units to derive ideal parts for (PM-ORG-003)" }
        val weights = areasM2.map { area ->
            require(area.signum() > 0 && area <= MAX_AREA_M2) {
                "a unit's area must be positive and at most $MAX_AREA_M2 m² to derive its ideal parts; got $area m² (PM-ORG-003)"
            }
            try {
                area.movePointRight(2).longValueExact()
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("an area has at most 2 decimals (schema numeric(10,2)); got $area m²")
            }
        }
        // allocateByWeight splits money; here the "pot" is the 1,000,000 steps that make 100%.
        return allocateByWeight(eur(STEPS_IN_WHOLE), weights).map { steps ->
            IdealParts.of("${steps.amountMinor / 10_000}.${(steps.amountMinor % 10_000).toString().padStart(4, '0')}")
        }
    }
}
