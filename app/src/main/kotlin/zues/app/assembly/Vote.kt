package zues.app.assembly

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.registry.Holdings
import zues.app.registry.TitleRole
import zues.kernel.toSofiaDate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * How an owner voted on an item, for one unit they own, as recorded at the sitting (Rule: PM-ORG-005): [weight] is
 * their title share of that unit's ideal parts on the sitting's day, exact. Never edited: a correction is another row
 * that [reverses] this one and says why ([correction]) — the vote in effect is the one nothing reverses (PM-VOTE-014).
 */
@Table("vote")
data class Vote(
    @Id val id: UUID,
    val entranceId: UUID,
    val assemblyId: UUID,
    val agendaItemId: UUID,
    val sessionNo: Int,
    val partyId: UUID,
    val unitId: UUID,
    val weight: BigDecimal,
    val choice: String,             // VoteChoice
    val channel: String,            // IN_PERSON | VIDEO | PROXY — how the owner was present
    val castAt: Instant,
    val reverses: UUID?,
    val correction: String?,
    val recordedBy: UUID,
    val loginIssuer: String,
    val loginSubject: String,
)

enum class VoteChoice { FOR, AGAINST, ABSTAIN }

interface VoteRepository : ListCrudRepository<Vote, UUID> {
    fun findByAgendaItemId(agendaItemId: UUID): List<Vote>
}

@Service
class VoteService(
    private val aggregates: JdbcAggregateTemplate,
    private val assemblies: AssemblyRepository,
    private val agenda: AgendaItemRepository,
    private val sittings: SittingRepository,
    private val attendance: AttendanceService,
    private val votes: VoteRepository,
    private val decisions: DecisionRepository,
    private val holdings: Holdings,
    private val clock: Clock,
) {
    /**
     * Records how [owner] voted on [itemId], by whoever runs the assembly ([by]): at the sitting that opened, on its day.
     * The owner is present — on the register in person, by video once identified, or brought by the representative a
     * counted proxy names — and their vote weighs, for each unit they own that day, their share of its ideal parts. A
     * second vote for the same owner is a correction, and says why: it reverses the vote in effect for each unit.
     */
    // Rule: PM-VOTE-001
    // Rule: PM-VOTE-014
    // Rule: PM-ORG-005
    // TODO(legal): PM-GA-012 — a unit with both an owner and a holder of a right of use is not voted on, as it is not weighed for quorum
    @Transactional
    fun record(entranceId: UUID, assemblyId: UUID, itemId: UUID, by: Actor, owner: UUID, choice: String, correction: String?): List<Vote> {
        val chose = VoteChoice.entries.firstOrNull { it.name == choice } ?: throw IllegalArgumentException("a vote is FOR, AGAINST or ABSTAIN, not \"$choice\"")
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        // every item is bound to its majority when it is put on the agenda (agenda_item.majority_rule_id NOT NULL): one of this agenda, or none
        val item = agenda.findById(itemId).filter { it.assemblyId == assemblyId && it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no item $itemId on the agenda of assembly $assemblyId") }
        check(assembly.status == AssemblyStatus.OPEN.name) { "votes are taken at a sitting that opened on its quorum; this assembly is ${assembly.status}" }
        check(!decisions.existsByAgendaItemId(itemId)) { "this item is closed: no vote on it is taken or corrected (PM-VOTE-014)" }
        val sitting = sittings.findByAssemblyIdOrderBySessionNo(assemblyId).single { it.quorumMet }
        val today = LocalDate.parse(toSofiaDate(clock.instant()))                                         // a Sofia calendar day (PM-SYS-004)
        check(today == sitting.weighedOn) { "the votes of sitting ${sitting.sessionNo} are taken on ${sitting.weighedOn}" }
        val line = attendance.register(entranceId, assemblyId, sitting.sessionNo).firstOrNull { owner in it.represented }
            ?: throw IllegalArgumentException("this owner is not present at the sitting, in person or by a counted proxy: they do not vote (PM-ORG-005)")
        val channel = if (line.attendance.attendeePartyId == owner) line.attendance.channel else "PROXY"

        val before = votes.findByAgendaItemId(itemId).filter { it.partyId == owner }
        val reversed = before.mapNotNull { it.reverses }.toSet()
        val inEffect = before.filter { it.id !in reversed }
        val why = correction?.trim()?.takeIf { it.isNotEmpty() }
        val now = clock.instant()
        fun row(unit: UUID, weight: BigDecimal, reverses: UUID?) = Vote(
            UUID.randomUUID(), entranceId, assemblyId, item.id, sitting.sessionNo, owner, unit, weight, chose.name, channel, now,
            reverses, why.takeIf { reverses != null }, by.party, by.loginIssuer, by.loginSubject,
        )
        if (inEffect.isNotEmpty()) {
            checkNotNull(why) { "this owner's vote on the item is recorded: a correction says why, and reverses it (PM-VOTE-014)" }
            return inEffect.map { aggregates.insert(row(it.unitId, it.weight, reverses = it.id)) }
        }
        require(why == null) { "there is no vote of this owner on the item to correct" }
        val owned = holdings.inForce(entranceId, sitting.weighedOn, TitleRole.OWN)
        val bothRoles = holdings.inForce(entranceId, sitting.weighedOn, TitleRole.USR).holdings.map { it.unitId }.toSet()
        val theirs = owned.holdings.filter { it.partyId == owner }
        check(theirs.none { it.unitId in bothRoles || it.unitId in owned.overHeldUnitIds }) {
            "a unit of this owner has both an owner and a holder of a right of use, or is owned for more than the whole of it: it is not weighed, so not voted on (TODO(legal): PM-GA-012, PM-ORG-005)"
        }
        // one row per unit: a co-owner weighs their share of it and no more, and never twice (vote_first_once). Co-owners'
        // rounded shares may add up to a hair over the unit (within Holdings' tolerance): the tally caps a unit at its
        // ideal parts, as quorum weighing does — a row records the owner's share exactly as the book gives it.
        return theirs.groupBy { it.unitId }.map { (unit, held) ->
            aggregates.insert(row(unit, held.fold(BigDecimal.ZERO) { sum, h -> sum + BigDecimal(h.idealParts) }, reverses = null))
        }
    }
}
