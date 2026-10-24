package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
import zues.app.policy.Action
import zues.app.policy.Asking
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** An item's decision: the majority applied, the tally on ideal parts, the result line, and the status. */
data class DecisionView(
    val id: UUID,
    val agendaItemId: UUID,
    val sessionNo: Int,
    val majorityRuleId: String,
    val denominator: String,
    val comparison: String,
    val thresholdPct: String,
    val represented: String,
    val tallyFor: String,
    val tallyAgainst: String,
    val tallyAbstain: String,
    val result: String,
    val outcome: String,
    val status: String,
    val basisHash: String,
    val decidedAt: Instant,
    val closedBy: UUID,
)

/** An item is closed by whoever runs the assembly — closing ends the vote record, so it is that record's line (RECORD_VOTE). */
@Service
class DecisionActs(private val acts: AssemblyActs, private val decisions: DecisionService) {
    // Rule: PM-SEC-001
    @Transactional
    fun close(who: Asking, entranceId: UUID, assemblyId: UUID, itemId: UUID): Decision {
        val by = acts.runBy(who, entranceId, assemblyId, Action.RECORD_VOTE)
        return decisions.close(entranceId, assemblyId, itemId, by)
    }
}

/**
 * The decision on an item of the agenda (PM-VOTE-011, PM-VOTE-016): closing the item tallies its votes in effect and
 * stores the decision; after it no vote on the item is taken (PM-VOTE-014). Closed by whoever runs the assembly, signed
 * in, and anybody else is a 403 naming the rule (PM-SEC-001).
 */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/decision")
class DecisionController(private val acts: DecisionActs, private val who: WhoAsks) {

    // Rule: PM-VOTE-002
    // Rule: PM-VOTE-011
    // Rule: PM-VOTE-012
    // Rule: PM-VOTE-014
    // Rule: PM-VOTE-016
    // Rule: PM-SEC-001
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun close(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @PathVariable itemId: UUID, signedIn: JwtAuthenticationToken?): DecisionView =
        acts.close(who.of(signedIn), entranceId, assemblyId, itemId).let {
            DecisionView(
                it.id, it.agendaItemId, it.sessionNo, it.majorityRuleId, it.denominator, it.comparison, it.thresholdPct.plain(), it.represented.plain(),
                it.tallyFor.plain(), it.tallyAgainst.plain(), it.tallyAbstain.plain(), it.basis.lines().first().substringAfter(": "),
                it.outcome, it.status, it.basisHash, it.decidedAt, it.closedBy,
            )
        }

    private fun BigDecimal.plain() = stripTrailingZeros().toPlainString()

    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused): Map<String, String> = mapOf("error" to (e.message ?: "not allowed"), "rule" to e.ruleId)

    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** Not open, not the sitting's day, already closed, a vote from an owner the sitting did not weigh. */
    @ExceptionHandler(IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: IllegalStateException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))
}
