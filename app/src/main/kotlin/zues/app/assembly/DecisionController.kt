package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
import zues.app.policy.Action
import zues.app.policy.Asking
import java.math.BigDecimal
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
    /** PROVISIONAL or FINAL on today's legal date (PM-VOTE-013, PM-GA-023) — derived, the stored decision is never edited. */
    val status: String,
    /** The last day an absent owner may vote (PM-VOTE-013). */
    val absenteeCloses: String,
    /** The day the protocol was posted (чл. 16 ал. 7), and the last day to seek annulment — none until it is entered (PM-GA-023). */
    val protocolPostedOn: String?,
    val annulmentCloses: String?,
    /** The day the decision becomes FINAL — none until the protocol's posting is entered. */
    val finalOn: String?,
    val basisHash: String,
    val decidedAt: Instant,
    val closedBy: UUID,
)

/** The day an assembly's protocol was posted, as entered (PM-GA-023). */
data class PostProtocol(val postedOn: LocalDate)

/** An item is closed by whoever runs the assembly — closing ends the vote record, so it is that record's line (RECORD_VOTE). */
@Service
class DecisionActs(
    private val acts: AssemblyActs,
    private val decisions: DecisionService,
    private val reads: AssemblyReads,
    private val agenda: AgendaItemRepository,
    private val stored: DecisionRepository,
    private val postings: ProtocolPostingRepository,
    private val clock: Clock,
) {
    // Rule: PM-SEC-001
    @Transactional
    fun close(who: Asking, entranceId: UUID, assemblyId: UUID, itemId: UUID): Decision {
        val by = acts.runBy(who, entranceId, assemblyId, Action.RECORD_VOTE)
        return decisions.close(entranceId, assemblyId, itemId, by)
    }

    /** The decision on [itemId], for anyone who reads the entrance's assemblies — asked before anything is read. None yet is a 404. */
    // Rule: PM-SEC-001
    // Rule: PM-SEC-002
    @Transactional(readOnly = true)
    fun read(who: Asking, entranceId: UUID, assemblyId: UUID, itemId: UUID): Decision {
        reads.require(entranceId, who)
        agenda.findById(itemId).filter { it.assemblyId == assemblyId && it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no item $itemId on the agenda of assembly $assemblyId") }
        return stored.findByAgendaItemId(itemId) ?: throw NoSuchElementException("item $itemId is not closed: it has no decision yet")
    }

    /** Where [decision] stands today, and the day its protocol was posted, if that is entered. */
    // Rule: PM-VOTE-013
    // Rule: PM-GA-023
    fun standingOf(decision: Decision): Pair<Standing, LocalDate?> {
        val posted = postings.findByAssemblyId(decision.assemblyId)?.postedOn
        return standing(toSofiaDate(decision.decidedAt), posted?.toString(), toSofiaDate(clock.instant())) to posted
    }
}

/**
 * The decision on an item of the agenda (PM-VOTE-011, PM-VOTE-016): closing the item tallies its votes in effect and
 * stores the decision; after it no vote on the item is taken (PM-VOTE-014). Closed by whoever runs the assembly, signed
 * in, and anybody else is a 403 naming the rule (PM-SEC-001). Read by anyone holding a role in the entrance: the result,
 * never how each owner voted (the votes' own read, PM-SEC-002).
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
        view(acts.close(who.of(signedIn), entranceId, assemblyId, itemId))

    // Rule: PM-VOTE-011
    // Rule: PM-VOTE-013
    // Rule: PM-VOTE-016
    // Rule: PM-GA-023
    // Rule: PM-SEC-001
    // Rule: PM-SEC-002
    @GetMapping
    fun read(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @PathVariable itemId: UUID, signedIn: JwtAuthenticationToken?): DecisionView =
        view(acts.read(who.of(signedIn), entranceId, assemblyId, itemId))

    private fun view(it: Decision): DecisionView {
        val (standing, posted) = acts.standingOf(it)
        return DecisionView(
            it.id, it.agendaItemId, it.sessionNo, it.majorityRuleId, it.denominator, it.comparison, it.thresholdPct.plain(), it.represented.plain(),
            it.tallyFor.plain(), it.tallyAgainst.plain(), it.tallyAbstain.plain(), it.basis.lines().first().substringAfter(": "),
            it.outcome, standing.status, standing.absenteeCloses, posted?.toString(), standing.annulmentCloses, standing.finalOn, it.basisHash, it.decidedAt, it.closedBy,
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

/**
 * The day an assembly's protocol was posted under чл. 16 ал. 7 (PM-GA-023): the 30 days to seek annulment of its
 * decisions run from it. Entered once, by whoever runs the assembly (PM-SEC-001); anybody else is a 403 naming the rule.
 */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/protocol-posting")
class ProtocolPostingController(private val postings: ProtocolPostingService, private val who: WhoAsks) {

    // Rule: PM-GA-023
    // Rule: PM-SEC-001
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun record(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @RequestBody request: PostProtocol, signedIn: JwtAuthenticationToken?): Map<String, Any> =
        postings.record(who.of(signedIn), entranceId, assemblyId, request.postedOn).let { mapOf("id" to it.id, "postedOn" to it.postedOn) }

    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused): Map<String, String> = mapOf("error" to (e.message ?: "not allowed"), "rule" to e.ruleId)

    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** No sitting opened, a day outside the sitting's to today, already entered. */
    @ExceptionHandler(IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: IllegalStateException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))
}
