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
import zues.kernel.CivilDate
import zues.kernel.toSofiaDate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * An absent owner's ballot on an eligible item, for one unit they own (Rule: PM-GA-017): [weight] is their title share
 * of its ideal parts on the sitting's day. A handwritten-signed declaration (Rule: PM-GA-018), received on
 * [receivedOn], entered with its reference and the SHA-256 of its scan. Never edited; merged into the tally later
 * (PM-VOTE-013, S-G2-02m).
 */
@Table("absentee_ballot")
data class AbsenteeBallot(
    @Id val id: UUID,
    val entranceId: UUID,
    val assemblyId: UUID,
    val agendaItemId: UUID,
    val partyId: UUID,
    val unitId: UUID,
    val weight: BigDecimal,
    val choice: String,             // VoteChoice
    val signature: String,          // HANDWRITTEN
    val documentRef: String,
    val documentSha256: String,
    val receivedOn: LocalDate,
    val recordedBy: UUID,
    val loginIssuer: String,
    val loginSubject: String,
    val recordedAt: Instant,
)

interface AbsenteeBallotRepository : ListCrudRepository<AbsenteeBallot, UUID> {
    fun findByAgendaItemId(agendaItemId: UUID): List<AbsenteeBallot>
}

/** An absent owner's ballot, as entered from their declaration: [signature] HANDWRITTEN, [receivedOn] a day, yyyy-MM-dd. */
data class BallotRequest(
    val ownerPartyId: UUID,
    val choice: String,
    val signature: String,
    val documentRef: String,
    val documentSha256: String,
    val receivedOn: String,
)

/**
 * A ballot is received after the sitting and by day 7 after it — the last day the one deadline utility gives (PM-SYS-005)
 * — and entered by then: "the tally reopens until day 7", no later.
 */
// Rule: PM-GA-017
fun withinAbsenteeWindow(decidedOn: CivilDate, receivedOn: CivilDate, today: CivilDate) {
    val closes = standing(decidedOn, null, today).absenteeCloses
    require(receivedOn in decidedOn..closes) { "a ballot is received from the sitting, $decidedOn, to day 7 after it, $closes; not $receivedOn" }
    require(receivedOn <= today) { "a ballot received on $receivedOn is not entered before, on $today" }
    check(today <= closes) { "the absentee window closed on $closes: no ballot is entered after it" }
}

/**
 * A signed declaration, entered by whoever runs the assembly — never the owner's own act (PM-GA-018): only a handwritten
 * signature, with its scan's reference and hash; a QES waits for its provider (DEVBRIEF, an open decision).
 */
// Rule: PM-GA-018
fun signedDeclaration(signature: String, documentRef: String, documentSha256: String) {
    check(signature != "QES") { "a qualified electronic signature is not accepted yet: its provider is not chosen" }
    require(signature == "HANDWRITTEN") { "a ballot is a handwritten-signed declaration, not \"$signature\"" }
    require(documentRef.isNotBlank()) { "a ballot names the declaration it is entered from" }
    require(Regex("^[0-9a-f]{64}$").matches(documentSha256)) { "a ballot carries the SHA-256 of the declaration's scan, 64 hex digits" }
}

@Service
class AbsenteeBallotService(
    private val acts: AssemblyActs,
    private val agenda: AgendaItemRepository,
    private val sittings: SittingRepository,
    private val attendance: AttendanceService,
    private val decisions: DecisionRepository,
    private val ballots: AbsenteeBallotRepository,
    private val holdings: Holdings,
    private val aggregates: JdbcAggregateTemplate,
    private val clock: Clock,
) {
    /**
     * Enters [request]'s ballot on [itemId], by whoever runs the assembly (RECORD_VOTE, PM-SEC-001): the item is tagged
     * eligible and closed, the owner was not represented at its sitting, and has no ballot on it yet. One row per unit
     * they own on the sitting's day, weighed as a vote is.
     */
    // Rule: PM-GA-017
    // Rule: PM-GA-018
    // Rule: PM-SEC-001
    @Transactional
    fun enter(who: Asking, entranceId: UUID, assemblyId: UUID, itemId: UUID, request: BallotRequest): List<AbsenteeBallot> {
        val by = acts.runBy(who, entranceId, assemblyId, Action.RECORD_VOTE)
        val chose = VoteChoice.entries.firstOrNull { it.name == request.choice } ?: throw IllegalArgumentException("a ballot is FOR, AGAINST or ABSTAIN, not \"${request.choice}\"")
        signedDeclaration(request.signature, request.documentRef, request.documentSha256)
        val receivedOn = runCatching { LocalDate.parse(request.receivedOn) }.getOrElse { throw IllegalArgumentException("receivedOn is a day, yyyy-MM-dd") }
        val item = agenda.findById(itemId).filter { it.assemblyId == assemblyId && it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no item $itemId on the agenda of assembly $assemblyId") }
        check(item.absenteeEligible) { "this item is not tagged for absentee ballots (PM-GA-017)" }
        val decision = decisions.findByAgendaItemId(itemId) ?: throw IllegalStateException("absentee ballots are entered after the item is closed at its sitting")
        withinAbsenteeWindow(toSofiaDate(decision.decidedAt), receivedOn.toString(), toSofiaDate(clock.instant()))
        val sitting = sittings.findByAssemblyIdOrderBySessionNo(assemblyId).single { it.sessionNo == decision.sessionNo }
        val owner = request.ownerPartyId
        require(attendance.register(entranceId, assemblyId, sitting.sessionNo).none { owner in it.represented }) {
            "this owner was represented at the sitting: they voted there, not by an absentee ballot (PM-GA-017)"
        }
        check(ballots.findByAgendaItemId(itemId).none { it.partyId == owner }) { "this owner's ballot on the item is entered" }

        val owned = holdings.inForce(entranceId, sitting.weighedOn, TitleRole.OWN)
        val bothRoles = holdings.inForce(entranceId, sitting.weighedOn, TitleRole.USR).holdings.map { it.unitId }.toSet()
        val theirs = owned.holdings.filter { it.partyId == owner }
        require(theirs.isNotEmpty()) { "this party owns no unit of the entrance on the sitting's day" }
        check(theirs.none { it.unitId in bothRoles || it.unitId in owned.overHeldUnitIds }) {
            "a unit of this owner has both an owner and a holder of a right of use, or is owned for more than the whole of it: it is not weighed, so not voted on (TODO(legal): PM-GA-012, PM-ORG-005)"
        }
        val now = clock.instant()
        return theirs.groupBy { it.unitId }.map { (unit, held) ->
            aggregates.insert(
                AbsenteeBallot(
                    UUID.randomUUID(), entranceId, assemblyId, itemId, owner, unit, held.fold(BigDecimal.ZERO) { sum, h -> sum + BigDecimal(h.idealParts) },
                    chose.name, request.signature, request.documentRef.trim(), request.documentSha256, receivedOn, by.party, by.loginIssuer, by.loginSubject, now,
                ),
            )
        }
    }
}
