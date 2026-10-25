package zues.app.assembly

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.kernel.CivilDate
import zues.kernel.toSofiaDate
import zues.law.numberOn
import zues.law.statutoryDeadline
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The day an assembly's protocol was posted under чл. 16 ал. 7 (Rule: PM-GA-023): the owners received its decisions
 * that day, and the time to seek their annulment runs from it. Once per assembly; never edited.
 */
@Table("protocol_posting")
data class ProtocolPosting(
    @Id val id: UUID,
    val entranceId: UUID,
    val assemblyId: UUID,
    val postedOn: LocalDate,
    val postedBy: UUID,
    val loginIssuer: String,
    val loginSubject: String,
    val recordedAt: Instant,
)

interface ProtocolPostingRepository : ListCrudRepository<ProtocolPosting, UUID> {
    fun findByAssemblyId(assemblyId: UUID): ProtocolPosting?
}

/**
 * Where a decision stands on [today]: PROVISIONAL while absent owners may still vote — the 7 days after its sitting
 * (PM-VOTE-013, PM-GA-017) — or while an owner may still seek its annulment — the 30 days after the protocol was
 * posted (PM-GA-023); FINAL from the day after both closed. A protocol is posted no earlier than its sitting, so the
 * time to seek annulment always outlasts the absentee window. A protocol not yet posted starts no countdown: the decision stays PROVISIONAL. Each window's last day is the one deadline utility's (PM-SYS-005), under the law in
 * force on the day of the decision. The decision's day is its sitting's: an item is closed on the sitting's day only.
 * An item tagged for absentee ballots ([eligible]) is FINAL only once they are merged ([mergedOn], after day 7): on the
 * later of the day after the 30 days and the merge's day (the owner's, 2026-10-25); unmerged, it has no final day.
 */
data class Standing(val status: String, val absenteeCloses: CivilDate, val annulmentCloses: CivilDate?, val finalOn: CivilDate?)

// Rule: PM-VOTE-013
// Rule: PM-GA-017
// Rule: PM-GA-023
fun standing(decidedOn: CivilDate, protocolPostedOn: CivilDate?, today: CivilDate, eligible: Boolean = false, mergedOn: CivilDate? = null): Standing {
    require(protocolPostedOn == null || protocolPostedOn >= decidedOn) { "a protocol is posted no earlier than its sitting, $decidedOn" }
    val absentee = statutoryDeadline(decidedOn, numberOn("GA_ABSENTEE_DAYS", decidedOn).toInt(), decidedOn)
    require(mergedOn == null || (eligible && mergedOn > absentee)) { "absentee ballots are merged on an eligible item, after day 7, $absentee" }
    val annulment = protocolPostedOn?.let { statutoryDeadline(it, numberOn("GA_ANNULMENT_DAYS", decidedOn).toInt(), decidedOn) }
    val afterAnnulment = annulment?.let { LocalDate.parse(it).plusDays(1).toString() }
    val finalOn = if (!eligible) afterAnnulment else afterAnnulment?.let { after -> mergedOn?.let { maxOf(after, it) } }
    return Standing(if (finalOn != null && today >= finalOn) "FINAL" else "PROVISIONAL", absentee, annulment, finalOn)
}

/**
 * Entering the day the protocol was posted — by whoever runs the assembly, as they record its votes (RECORD_VOTE),
 * once, for an assembly whose sitting opened, on a day from that sitting's to today.
 */
@Service
class ProtocolPostingService(
    private val acts: AssemblyActs,
    private val assemblies: AssemblyRepository,
    private val sittings: SittingRepository,
    private val postings: ProtocolPostingRepository,
    private val aggregates: JdbcAggregateTemplate,
    private val clock: Clock,
) {
    // Rule: PM-GA-023
    // Rule: PM-SEC-001
    @Transactional
    fun record(who: Asking, entranceId: UUID, assemblyId: UUID, postedOn: LocalDate): ProtocolPosting {
        val by = acts.runBy(who, entranceId, assemblyId, Action.RECORD_VOTE)
        assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        val sitting = sittings.findByAssemblyIdOrderBySessionNo(assemblyId).singleOrNull { it.quorumMet }
            ?: throw IllegalStateException("no sitting of this assembly opened: it has no protocol to post")
        val today = LocalDate.parse(toSofiaDate(clock.instant()))
        check(postedOn in sitting.weighedOn..today) { "the protocol is posted from the sitting's day, ${sitting.weighedOn}, to today, $today" }
        check(postings.findByAssemblyId(assemblyId) == null) { "the protocol's posting is entered: it is entered once (PM-GA-023)" }
        return aggregates.insert(ProtocolPosting(UUID.randomUUID(), entranceId, assemblyId, postedOn, by.party, by.loginIssuer, by.loginSubject, clock.instant()))
    }
}
