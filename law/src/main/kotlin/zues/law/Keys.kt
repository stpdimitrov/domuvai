package zues.law

/**
 * Rule: PM-FEE-002, PM-FEE-003, PM-FEE-004 — the statutory keys, PER_PERSON, BY_IDEAL_PARTS and PER_UNIT.
 * METERED is not one of them: it marks a consumption line, billed from each unit's own meter and never
 * allocated by a key (Rule: PM-FEE-017). No tariff line takes it.
 */
enum class AllocationKey { PER_PERSON, BY_IDEAL_PARTS, PER_UNIT, METERED }

/** Rule: PM-FEE-001 — three cost streams, and only three: every charge line is typed to one of them. */
enum class CostStream { MANAGEMENT, MAINTENANCE, REPAIR_FUND }

/**
 * The statutory default key per stream. The general assembly may change
 * MANAGEMENT and MAINTENANCE (PM-FEE-003); REPAIR_FUND is fixed by чл. 48–50 and
 * PM-FUND-003 and the assembly cannot move it.
 */
fun defaultKey(stream: CostStream): AllocationKey =
    if (stream == CostStream.REPAIR_FUND) AllocationKey.BY_IDEAL_PARTS else AllocationKey.PER_PERSON

fun keyIsChangeableByAssembly(stream: CostStream): Boolean = stream != CostStream.REPAIR_FUND

/**
 * A cost the law ties to a stream by name. Concierge (портиер) costs follow the allocation rules of
 * maintenance — its key, and with it its exemptions and business multiplier — so a concierge line
 * is a maintenance line, not a fourth stream (PM-FEE-001). Rule: PM-FEE-011
 */
enum class CostItem(val stream: CostStream, val metered: Boolean = false) {
    CONCIERGE(CostStream.MAINTENANCE),

    /** Each unit's own water meter, in m³ — a metered maintenance cost, never a fourth stream (Rule: PM-FEE-017). */
    WATER(CostStream.MAINTENANCE, metered = true),

    /** Each unit's own heat meter or allocator, in kWh (Rule: PM-FEE-017). */
    HEATING(CostStream.MAINTENANCE, metered = true),
}
