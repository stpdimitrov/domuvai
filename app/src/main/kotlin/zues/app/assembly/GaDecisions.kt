package zues.app.assembly

import org.springframework.stereotype.Component
import java.util.UUID

/** The assembly's published answer on its decisions. Money depends on this port, never on `assembly`'s tables (ADR-003). */
interface GaDecisions {
    /**
     * Whether [decisionId] names a decision the general assembly of [entranceId] took and that stands as passed — taken
     * at a sitting, or recorded from a paper protocol. Standing PROVISIONAL counts (the owner's answer on #326,
     * 2026-10-31); an unknown id, another entrance's and anything that is no decision's id answer the same.
     */
    fun passed(entranceId: UUID, decisionId: String): Boolean
}

/**
 * Once an item's absentee ballots are merged its outcome is the merged decision's, whichever of the two ids is asked
 * after (PM-VOTE-013). A paper decision claims no tally: being recorded is what it has to show (DEC-01). Nothing records
 * an annulment yet, so no decision holds ANNULLED today; the column allows it and this refuses it.
 */
@Component
class GaDecisionsAdapter(
    private val decisions: DecisionRepository,
    private val merged: MergedDecisionRepository,
    private val paper: PaperDecisionRepository,
) : GaDecisions {
    // Rule: PM-FEE-012
    // Rule: PM-VOTE-013
    override fun passed(entranceId: UUID, decisionId: String): Boolean {
        val id = uuidOf(decisionId) ?: return false
        val merge = merged.findById(id).orElse(null)
        val decision = decisions.findById(merge?.supersedes ?: id).orElse(null)
            ?: return paper.findById(id).filter { it.entranceId == entranceId }.isPresent
        val outcome = (merge ?: merged.findBySupersedes(decision.id))?.outcome ?: decision.outcome
        return decision.entranceId == entranceId && decision.status != ANNULLED && outcome == PASSED
    }

    /** The id as a line keeps it: [UUID.fromString] also takes a shortened or upper-case spelling of a stored one. */
    private fun uuidOf(id: String): UUID? = runCatching { UUID.fromString(id) }.getOrNull()?.takeIf { it.toString() == id }

    private companion object {
        const val PASSED = "PASSED"
        const val ANNULLED = "ANNULLED"
    }
}
