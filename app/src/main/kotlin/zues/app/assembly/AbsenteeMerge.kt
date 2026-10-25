package zues.app.assembly

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.registry.Holdings
import zues.app.registry.TitleRole
import zues.kernel.toSofiaDate
import zues.law.CATALOGUE_VERSION
import zues.law.ENGINE_VERSION
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * An eligible item's decision with the absent owners' ballots merged in (Rule: PM-VOTE-013): it [supersedes] the
 * provisional one, which stays as it was stored. [represented] is the provisional's plus the absent owners' parts (the
 * owner's, 2026-10-25); [basis] names the superseded decision and its hash, every vote and ballot counted and every
 * holding weighed present, so it can be recomputed. Once per item; never edited.
 */
@Table("merged_decision")
data class MergedDecision(
    @Id val id: UUID,
    val entranceId: UUID,
    val assemblyId: UUID,
    val agendaItemId: UUID,
    val supersedes: UUID,
    val represented: BigDecimal,
    val tallyFor: BigDecimal,
    val tallyAgainst: BigDecimal,
    val tallyAbstain: BigDecimal,
    val outcome: String,            // PASSED | NOT_PASSED
    val basis: String,
    val basisHash: String,
    val lawVersion: String,
    val engineVersion: String,
    val mergedAt: Instant,
    val mergedBy: UUID,
    val loginIssuer: String,
    val loginSubject: String,
)

interface MergedDecisionRepository : ListCrudRepository<MergedDecision, UUID> {
    fun findBySupersedes(decisionId: UUID): MergedDecision?
}

/** The represented share at the merge: the sitting's, with each absent owner's ballot weight added (Rule: PM-GA-017). */
// Rule: PM-GA-017
fun representedWithBallots(atTheClose: BigDecimal, ballots: List<AbsenteeBallot>): BigDecimal =
    ballots.fold(atTheClose) { sum, it -> sum + it.weight }

@Service
class AbsenteeMergeService(
    private val acts: AssemblyActs,
    private val assemblies: AssemblyRepository,
    private val agenda: AgendaItemRepository,
    private val sittings: SittingRepository,
    private val votes: VoteRepository,
    private val decisions: DecisionRepository,
    private val ballots: AbsenteeBallotRepository,
    private val merged: MergedDecisionRepository,
    private val holdings: Holdings,
    private val majorities: Majorities,
    private val aggregates: JdbcAggregateTemplate,
    private val clock: Clock,
) {
    /**
     * Merges the absentee ballots on [itemId] into its tally, by whoever runs the assembly (RECORD_VOTE, PM-SEC-001),
     * once day 7 has closed — "the tally reopens until day 7": the votes in effect and every ballot, against the majority
     * the item was bound to. Entered on an eligible item even with no ballot: it is what makes the item final.
     */
    // Rule: PM-VOTE-013
    // Rule: PM-GA-017
    // Rule: PM-SEC-001
    @Transactional
    fun merge(who: Asking, entranceId: UUID, assemblyId: UUID, itemId: UUID): Pair<Decision, MergedDecision> {
        val by = acts.runBy(who, entranceId, assemblyId, Action.RECORD_VOTE)
        assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        val item = agenda.findById(itemId).filter { it.assemblyId == assemblyId && it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no item $itemId on the agenda of assembly $assemblyId") }
        check(item.absenteeEligible) { "this item is not tagged for absentee ballots: its decision has nothing to merge (PM-GA-017)" }
        val decision = decisions.findByAgendaItemId(itemId) ?: throw IllegalStateException("the item is not closed at its sitting: it has no tally to merge into")
        val closes = standing(toSofiaDate(decision.decidedAt), null, toSofiaDate(clock.instant())).absenteeCloses
        check(toSofiaDate(clock.instant()) > closes) { "absentee ballots are entered until $closes, day 7: they are merged after it (PM-VOTE-013)" }
        check(merged.findBySupersedes(decision.id) == null) { "the absentee ballots on this item are merged: once (PM-VOTE-013)" }

        val sitting = sittings.findByAssemblyIdOrderBySessionNo(assemblyId).single { it.sessionNo == decision.sessionNo }
        val all = votes.findByAgendaItemId(itemId)
        val reversed = all.mapNotNull { it.reverses }.toSet()
        val inEffect = all.filter { it.id !in reversed }.map { Cast(it.id, it.partyId, it.unitId, it.weight, VoteChoice.valueOf(it.choice)) }
        val absent = ballots.findByAgendaItemId(itemId)
        val represented = representedWithBallots(decision.represented, absent)
        val rule = majorities.on(item.itemType, decision.majorityRuleId.substringAfter('@'))
        check(rule.id == decision.majorityRuleId) { "the item was decided on ${decision.majorityRuleId}, the law now names ${rule.id}" }
        val unitParts = holdings.inForce(entranceId, sitting.weighedOn, TitleRole.OWN).holdings.associate { it.unitId to BigDecimal(it.unitIdealParts) }
        val result = tally(inEffect + absent.map { Cast(it.id, it.partyId, it.unitId, it.weight, VoteChoice.valueOf(it.choice)) }, unitParts, represented, rule)
        fun plain(it: BigDecimal) = it.stripTrailingZeros().toPlainString()
        val basis = (
            listOf(
                "item ${item.id} at sitting ${sitting.sessionNo}, merged after day 7 ($closes), ${plain(represented)} represented with ${absent.size} absentee ballots: ${result.line}",
                "supersedes ${decision.id} ${decision.basisHash}",
            ) +
                (inEffect.map { "${it.partyId} ${it.unitId} ${plain(it.weight)} ${it.choice} by ${it.voteId}" } +
                    absent.map { "absentee ${it.partyId} ${it.unitId} ${plain(it.weight)} ${it.choice} by ${it.id} ${it.documentSha256}" }).sorted() +
                decision.basis.lines().filter { it.startsWith("present ") }
            ).joinToString("\n")
        val stored = aggregates.insert(
            MergedDecision(
                UUID.randomUUID(), entranceId, assemblyId, item.id, decision.id, represented, result.forParts, result.againstParts, result.abstainParts,
                if (result.passed) "PASSED" else "NOT_PASSED", basis,
                MessageDigest.getInstance("SHA-256").digest(basis.toByteArray()).joinToString("") { "%02x".format(it) },
                CATALOGUE_VERSION, ENGINE_VERSION, clock.instant(), by.party, by.loginIssuer, by.loginSubject,
            ),
        )
        return decision to stored
    }
}
