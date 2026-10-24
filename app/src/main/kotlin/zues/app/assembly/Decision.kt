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
import zues.law.CATALOGUE_VERSION
import zues.law.Comparison
import zues.law.Denominator
import zues.law.ENGINE_VERSION
import zues.law.MajorityRule
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * What an item of the agenda decided, as it was closed (Rule: PM-VOTE-016): the majority applied, the tally, the
 * represented share it was counted against, the sitting, the outcome and the status — and [basis], the result line and
 * every vote counted, so it can be recomputed years later. Never edited.
 */
@Table("decision")
data class Decision(
    @Id val id: UUID,
    val entranceId: UUID,
    val assemblyId: UUID,
    val agendaItemId: UUID,
    val sessionNo: Int,
    val majorityRuleId: String,
    val denominator: String,        // TOTAL | REPRESENTED
    val comparison: String,         // MORE_THAN | AT_LEAST
    val thresholdPct: BigDecimal,
    val represented: BigDecimal,
    val tallyFor: BigDecimal,
    val tallyAgainst: BigDecimal,
    val tallyAbstain: BigDecimal,
    val outcome: String,            // PASSED | NOT_PASSED
    val status: String,             // PROVISIONAL | FINAL | ANNULLED
    val basis: String,
    val basisHash: String,
    val lawVersion: String,
    val engineVersion: String,
    val decidedAt: Instant,
    val closedBy: UUID,
    val loginIssuer: String,
    val loginSubject: String,
)

interface DecisionRepository : ListCrudRepository<Decision, UUID> {
    fun existsByAgendaItemId(agendaItemId: UUID): Boolean
}

/** One vote in effect, as the tally counts it. */
data class Cast(val voteId: UUID, val partyId: UUID, val unitId: UUID, val weight: BigDecimal, val choice: VoteChoice)

/** The tally of an item against its majority, and the result line that says so. */
data class Tally(val forParts: BigDecimal, val againstParts: BigDecimal, val abstainParts: BigDecimal, val passed: Boolean, val line: String)

/** All the ideal parts of an entrance, in percent (ADR-006: they sum to 100% per entrance) — the TOTAL denominator. */
private val ALL_IDEAL_PARTS = BigDecimal(100)   // not-legal: the whole of the ideal parts, not a threshold

/**
 * Tallies [casts] on ideal parts against [rule] (Rule: PM-VOTE-011): a unit counts for a choice no more than its own
 * ideal parts ([unitParts]), however its co-owners' rounded shares add up — as quorum weighing caps it. Against
 * REPRESENTED, FOR is weighed against [represented]; against TOTAL, against all the ideal parts and never the
 * represented subset (PM-VOTE-012). Passing is decided on the exact numbers; the line reports them to 2 decimals.
 * The cap is per choice: a cast is a title share of the unit, so co-owners together overshoot it only by the rounding
 * Holdings tolerates — a unit voted one way counts its parts exactly; one split between choices may count that hair.
 */
// Rule: PM-VOTE-002
// Rule: PM-VOTE-011
// Rule: PM-VOTE-012
fun tally(casts: List<Cast>, unitParts: Map<UUID, BigDecimal>, represented: BigDecimal, rule: MajorityRule): Tally {
    require(represented.signum() > 0) { "nothing was represented at the sitting" }
    fun parts(choice: VoteChoice) = casts.filter { it.choice == choice }.groupBy { it.unitId }.entries.fold(BigDecimal.ZERO) { sum, (unit, ofUnit) ->
        val whole = unitParts[unit] ?: throw IllegalStateException("unit $unit is not in the book on the sitting's day")
        sum + ofUnit.fold(BigDecimal.ZERO) { cast, it -> cast + it.weight }.min(whole)
    }
    val (forParts, against, abstain) = VoteChoice.entries.map(::parts)
    val base = if (rule.denominator == Denominator.TOTAL) ALL_IDEAL_PARTS else represented
    val threshold = BigDecimal(rule.thresholdPct)
    val share = (forParts * ALL_IDEAL_PARTS).compareTo(threshold * base)   // FOR / base against threshold / 100, without dividing
    val passed = if (rule.comparison == Comparison.MORE_THAN) share > 0 else share >= 0
    fun pct(of: BigDecimal, over: BigDecimal) = (of * ALL_IDEAL_PARTS).divide(over, 2, RoundingMode.HALF_UP).toPlainString()
    val ofWhat = if (rule.denominator == Denominator.TOTAL) "of total" else "of represented"
    val line = "FOR ${pct(forParts, base)}% $ofWhat (${pct(forParts, ALL_IDEAL_PARTS)}% of total, ${pct(represented, ALL_IDEAL_PARTS)}% represented); " +
        "threshold ${if (rule.comparison == Comparison.MORE_THAN) "more than" else "at least"} ${rule.thresholdPct}% $ofWhat (${rule.id}) — " +
        if (passed) "PASSED" else "NOT PASSED"
    return Tally(forParts, against, abstain, passed, line)
}

@Service
class DecisionService(
    private val aggregates: JdbcAggregateTemplate,
    private val assemblies: AssemblyRepository,
    private val agenda: AgendaItemRepository,
    private val sittings: SittingRepository,
    private val votes: VoteRepository,
    private val decisions: DecisionRepository,
    private val holdings: Holdings,
    private val majorities: Majorities,
    private val clock: Clock,
) {
    /**
     * Closes [itemId], by whoever runs the assembly ([by]), at the sitting that opened, on its day: the votes in effect
     * are tallied against the majority the item was bound to, and the decision is stored, PROVISIONAL — PM-VOTE-013 and
     * PM-GA-023 make it final only later. After this no vote on the item is taken or corrected (PM-VOTE-014).
     */
    // Rule: PM-VOTE-016
    // Rule: PM-VOTE-014
    @Transactional
    fun close(entranceId: UUID, assemblyId: UUID, itemId: UUID, by: Actor): Decision {
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        val item = agenda.findById(itemId).filter { it.assemblyId == assemblyId && it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no item $itemId on the agenda of assembly $assemblyId") }
        check(assembly.status == AssemblyStatus.OPEN.name) { "an item is closed at a sitting that opened on its quorum; this assembly is ${assembly.status}" }
        check(!decisions.existsByAgendaItemId(itemId)) { "this item is closed: its decision is stored (PM-VOTE-014)" }
        val sitting = sittings.findByAssemblyIdOrderBySessionNo(assemblyId).single { it.quorumMet }
        check(LocalDate.parse(toSofiaDate(clock.instant())) == sitting.weighedOn) { "the items of sitting ${sitting.sessionNo} are closed on ${sitting.weighedOn}" }

        val all = votes.findByAgendaItemId(itemId)
        val reversed = all.mapNotNull { it.reverses }.toSet()
        val inEffect = all.filter { it.id !in reversed }
        // "represented" is the opened sitting's own weighing: its basis names every owner and unit it counted
        val weighed = sitting.basis.lines().drop(1).map { it.split(" ").let { (party, unit) -> party to unit } }.toSet()
        check(inEffect.all { (it.partyId.toString() to it.unitId.toString()) in weighed }) {
            "a vote comes from an owner who was not weighed when the sitting opened: the represented share does not hold it (owner question #283)"
        }
        val rule = majorities.on(item.itemType, item.majorityRuleId.substringAfter('@'))
        check(rule.id == item.majorityRuleId) { "the item was bound to ${item.majorityRuleId}, the law now names ${rule.id}" }
        val unitParts = holdings.inForce(entranceId, sitting.weighedOn, TitleRole.OWN).holdings.associate { it.unitId to BigDecimal(it.unitIdealParts) }
        val casts = inEffect.map { Cast(it.id, it.partyId, it.unitId, it.weight, VoteChoice.valueOf(it.choice)) }
        val result = tally(casts, unitParts, sitting.represented, rule)
        val basis = (listOf("item ${item.id} at sitting ${sitting.sessionNo}: ${result.line}") +
            casts.map { "${it.partyId} ${it.unitId} ${it.weight.stripTrailingZeros().toPlainString()} ${it.choice} by ${it.voteId}" }.sorted()).joinToString("\n")
        return aggregates.insert(
            Decision(
                UUID.randomUUID(), entranceId, assemblyId, item.id, sitting.sessionNo, rule.id, rule.denominator.name, rule.comparison.name,
                BigDecimal(rule.thresholdPct), sitting.represented, result.forParts, result.againstParts, result.abstainParts,
                if (result.passed) "PASSED" else "NOT_PASSED", "PROVISIONAL", basis,
                MessageDigest.getInstance("SHA-256").digest(basis.toByteArray()).joinToString("") { "%02x".format(it) },
                CATALOGUE_VERSION, ENGINE_VERSION, clock.instant(), by.party, by.loginIssuer, by.loginSubject,
            ),
        )
    }
}
