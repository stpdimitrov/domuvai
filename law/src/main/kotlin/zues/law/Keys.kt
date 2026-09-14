package zues.law

/** Rule: PM-FEE-002, PM-FEE-003, PM-FEE-004 */
enum class AllocationKey { PER_PERSON, BY_IDEAL_PARTS, PER_UNIT }

enum class CostStream { MANAGEMENT, MAINTENANCE, REPAIR_FUND }

/**
 * The statutory default key per stream. The general assembly may change
 * MANAGEMENT and MAINTENANCE (PM-FEE-003); REPAIR_FUND is fixed by чл. 48–50 and
 * PM-FUND-003 and the assembly cannot move it.
 */
fun defaultKey(stream: CostStream): AllocationKey =
    if (stream == CostStream.REPAIR_FUND) AllocationKey.BY_IDEAL_PARTS else AllocationKey.PER_PERSON

fun keyIsChangeableByAssembly(stream: CostStream): Boolean = stream != CostStream.REPAIR_FUND
