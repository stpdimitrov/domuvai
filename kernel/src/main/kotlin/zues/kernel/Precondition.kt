package zues.kernel

import java.time.Instant

/**
 * A statutory precondition is met, or it is passed by a documented [Deviation]. There is no third way: no
 * "skip", no flag that ignores it. Rule: PM-SYS-006
 */
sealed interface PreconditionOutcome {
    val precondition: String

    /** The precondition holds. Rule: PM-SYS-006 */
    data class Met(override val precondition: String) : PreconditionOutcome

    /**
     * A documented deviation: which precondition, why, who recorded it and when. It is evidence — the value a
     * caller keeps and compliance reporting shows — never silence. Rule: PM-SYS-006
     */
    data class Deviation(
        override val precondition: String,
        val reason: String,
        val recordedBy: String,
        val recordedAt: Instant,
    ) : PreconditionOutcome {
        init {
            require(precondition.isNotBlank()) { "a deviation names the precondition it departs from (PM-SYS-006)" }
            require(reason.isNotBlank()) { "a deviation from \"$precondition\" needs a reason (PM-SYS-006)" }
            require(recordedBy.isNotBlank()) { "a deviation from \"$precondition\" names who recorded it (PM-SYS-006)" }
        }
    }
}

/** An unmet statutory precondition with no documented deviation for it. Rule: PM-SYS-006 */
class PreconditionNotMet(val precondition: String, detail: String) : IllegalStateException("$detail (PM-SYS-006)")

/**
 * The one gate for a statutory precondition. Met → [PreconditionOutcome.Met]. Unmet → the [deviation] supplied for
 * this same precondition, or [PreconditionNotMet]. A deviation offered for a precondition that holds is not kept:
 * there is nothing to depart from. Rule: PM-SYS-006
 */
fun checkPrecondition(
    precondition: String,
    met: Boolean,
    deviation: PreconditionOutcome.Deviation? = null,
): PreconditionOutcome {
    require(precondition.isNotBlank()) { "a precondition has a name (PM-SYS-006)" }
    if (met) return PreconditionOutcome.Met(precondition)
    if (deviation == null) {
        throw PreconditionNotMet(precondition, "\"$precondition\" is not met and cannot be skipped; record a deviation with a reason")
    }
    if (deviation.precondition != precondition) {
        throw PreconditionNotMet(precondition, "\"$precondition\" is not met; a deviation from \"${deviation.precondition}\" does not cover it")
    }
    return deviation
}
