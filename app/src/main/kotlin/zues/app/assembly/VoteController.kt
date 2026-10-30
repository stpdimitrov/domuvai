package zues.app.assembly

import org.springframework.data.jdbc.core.JdbcAggregateTemplate
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
import zues.app.identity_org.PartyNames
import zues.app.identity_org.WhoAsks
import zues.app.policy.Action
import zues.app.policy.Asking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** How [ownerPartyId] voted, as whoever runs the assembly records it; [correction] says why a recorded vote is corrected. */
data class RecordVoteRequest(val ownerPartyId: UUID, val choice: String, val correction: String? = null)

/** One row of the record: an owner's vote for one unit, its weight, how they were present, what it reverses, and whether a later row reverses it. */
data class VoteView(
    val id: UUID,
    val sessionNo: Int,
    val ownerPartyId: UUID,
    val unitId: UUID,
    val weight: String,
    val choice: String,
    val channel: String,
    val castAt: Instant,
    val reverses: UUID?,
    val correction: String?,
    val recordedBy: UUID,
    val inEffect: Boolean,
    /** The owner's name, to the manager, the board and the controller — absent where names are withheld. */
    val names: Map<UUID, String>? = null,
)

/** A vote is recorded by whoever runs the assembly (the owner's decision of 2026-10-24): the policy's line, then theirs to run. */
@Service
class VoteActs(
    private val acts: AssemblyActs,
    private val votes: VoteService,
    private val reads: AssemblyReads,
    private val agenda: AgendaItemRepository,
    private val recorded: VoteRepository,
    private val aggregates: JdbcAggregateTemplate,
    private val clock: Clock,
) {
    // Rule: PM-SEC-001
    @Transactional
    fun record(who: Asking, entranceId: UUID, assemblyId: UUID, itemId: UUID, request: RecordVoteRequest): List<Vote> {
        val by = acts.runBy(who, entranceId, assemblyId, Action.RECORD_VOTE)
        return votes.record(entranceId, assemblyId, itemId, by, request.ownerPartyId, request.choice, request.correction)
    }

    /**
     * Every row of [itemId]'s vote record, in the order recorded — corrections and what they reverse included. How each
     * owner voted is another person's: the manager, the board and the controller only, asked before anything is read.
     * Every answer is entered (Rule: PM-SEC-004): a refusal, kept though the read fails; a served read, before the
     * votes are read — no entry, no votes; with sign-in switched off, as given to nobody. An item that is not there
     * serves nothing and enters nothing.
     */
    // Rule: PM-SEC-002
    // Rule: PM-GOV-008
    // Rule: PM-SEC-004
    @Transactional(noRollbackFor = [ActRefused::class])
    fun read(who: Asking, entranceId: UUID, assemblyId: UUID, itemId: UUID): List<Vote> {
        val decision = reads.decided(entranceId, who, Action.READ_VOTES)
        if (decision != null && !decision.allowed) {
            entered(entranceId, itemId, who, VoteReadOutcome.REFUSED, decision.ruleId)
            throw ActRefused(decision.ruleId)
        }
        agenda.findById(itemId).filter { it.assemblyId == assemblyId && it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no item $itemId on the agenda of assembly $assemblyId") }
        if (decision == null) entered(entranceId, itemId, who, VoteReadOutcome.OPEN, null)
        else entered(entranceId, itemId, who, VoteReadOutcome.SERVED, decision.ruleId)
        return recorded.findByAgendaItemId(itemId).sortedWith(compareBy({ it.castAt }, { it.id.toString() }))   // as Postgres orders a uuid: by its hex
    }

    // Rule: PM-SEC-004
    private fun entered(entranceId: UUID, itemId: UUID, who: Asking, outcome: VoteReadOutcome, ruleId: String?) {
        aggregates.insert(
            VoteRead(UUID.randomUUID(), entranceId, itemId, who.party, who.login?.issuer, who.login?.subject, clock.instant().truncatedTo(ChronoUnit.MICROS), outcome.name, ruleId),
        )
    }
}

/**
 * The votes on an item of the agenda (PM-VOTE-001, PM-VOTE-014, PM-ORG-005). Recorded by whoever runs the assembly,
 * signed in — never by a field of the request — and anybody else is a 403 naming the rule (PM-SEC-001). Read, row by
 * row, by the manager, the board and the controller only; anybody else is a 403 naming PM-SEC-002 (the owner's
 * decision of 2026-10-24) and reads the item's decision instead.
 */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/votes")
class VoteController(private val acts: VoteActs, private val who: WhoAsks, private val names: PartyNames) {

    // Rule: PM-VOTE-001
    // Rule: PM-VOTE-014
    // Rule: PM-ORG-005
    // Rule: PM-SEC-001
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        @PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @PathVariable itemId: UUID, @RequestBody request: RecordVoteRequest,
        signedIn: JwtAuthenticationToken?,
    ): List<VoteView> = views(acts.record(who.of(signedIn), entranceId, assemblyId, itemId, request), reversed = emptySet())

    // Rule: PM-VOTE-014
    // Rule: PM-SEC-001
    // Rule: PM-SEC-002
    // Rule: PM-GOV-008
    // Rule: PM-BOOK-006
    // Rule: PM-BOOK-007
    // Rule: PM-BOOK-011
    @GetMapping
    fun read(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @PathVariable itemId: UUID, signedIn: JwtAuthenticationToken?): List<VoteView> {
        val asking = who.of(signedIn)
        val rows = acts.read(asking, entranceId, assemblyId, itemId)
        val served = names.beside(entranceId, asking, rows.map { it.partyId }, NamedRead.VOTES)
        return views(rows, rows.mapNotNull { it.reverses }.toSet(), served)
    }

    private fun views(rows: List<Vote>, reversed: Set<UUID>, served: Map<UUID, String>? = null) = rows.map {
        VoteView(
            it.id, it.sessionNo, it.partyId, it.unitId, it.weight.plain(), it.choice, it.channel, it.castAt, it.reverses, it.correction, it.recordedBy, it.id !in reversed,
            served.of(it.partyId),
        )
    }

    private fun BigDecimal.plain() = stripTrailingZeros().toPlainString()

    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused): Map<String, String> = mapOf("error" to (e.message ?: "not allowed"), "rule" to e.ruleId)

    /** An unknown choice, an owner not present, a correction with nothing to correct. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid request"))

    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** Not open, not the sitting's day, a vote already recorded with no why, a unit that is not weighed. */
    @ExceptionHandler(IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: IllegalStateException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))
}
