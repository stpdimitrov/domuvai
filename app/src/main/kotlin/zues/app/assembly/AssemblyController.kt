package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
import zues.law.MajorityRule
import java.time.Instant
import java.util.UUID

/** [absenteeEligible]: the item accepts absent owners' ballots after its sitting (PM-GA-017) — the drafter's call, false unless said. */
data class AddAgendaItemRequest(val text: String, val itemType: String, val absenteeEligible: Boolean? = null)

/** The majority an item was bound to, with the rule and the article it comes from (PM-VOTE-004). */
data class MajorityView(
    val id: String,
    val rule: String,
    val thresholdPct: String,
    val comparison: String,
    val denominator: String,
    val source: String,
    val verified: Boolean,
)

/** [noticeVoided]: the item was added after the notice was posted, so the assembly is a draft again (PM-GA-006). */
data class AgendaItemAdded(
    val id: UUID,
    val ordinal: Int,
    val text: String,
    val itemType: String,
    val majority: MajorityView,
    val noticeVoided: Boolean,
    val absenteeEligible: Boolean,
)

data class RescheduleRequest(val scheduledAt: Instant, val place: String)

/** The posting act as certified: when, the photograph's SHA-256, and the owner who signed beside the convenor — who is the one signed in. */
data class RecordPostingRequest(val postedAt: Instant, val coSignatoryPartyId: UUID, val photoHash: String)

data class NoticePostingView(
    val id: UUID,
    val postedAt: Instant,
    val convenorPartyId: UUID,
    val coSignatoryPartyId: UUID,
    val photoHash: String,
    val statedScheduledAt: Instant,
    val statedPlace: String,
    val statedAgenda: String,
)

data class AgendaItemView(val id: UUID, val ordinal: Int, val text: String, val itemType: String, val majorityRuleId: String, val absenteeEligible: Boolean)

data class AssemblyView(
    val id: UUID,
    val entranceId: UUID,
    val convenedBy: UUID,
    val convenedAs: String,
    val scheduledAt: Instant,
    val place: String,
    val mode: String,
    val status: String,
    val urgent: Boolean,
    val urgencyReason: String?,
    val noticePostedAt: Instant?,
    val agenda: List<AgendaItemView>,
)

/** One assembly in an entrance's list: which it is, where it stands, when it meets, in what capacity it was convened. */
data class AssemblySummaryView(val id: UUID, val status: String, val scheduledAt: Instant, val convenedAs: String)

/**
 * Convening a general assembly, its agenda and its notice (PM-GA-002, PM-GA-004 … PM-GA-007, PM-VOTE-004). Who acts is
 * the party the sign-in is tied to — never a field of the request — and the policy says whether they may (PM-SEC-001):
 * anybody else is a 403 naming the rule. An assembly is read by anyone holding a role in its entrance, and by nobody
 * else. A body that cannot be read is a 400 for anyone.
 */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/assemblies")
class AssemblyController(private val acts: AssemblyActs, private val assemblies: AssemblyService, private val reads: AssemblyReads, private val who: WhoAsks) {

    // Rule: PM-GA-002
    // Rule: PM-SEC-001
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun convene(@PathVariable entranceId: UUID, @RequestBody request: ConveneRequest, signedIn: JwtAuthenticationToken?): AssemblyView =
        view(acts.convene(who.of(signedIn), entranceId, request), emptyList())

    // Rule: PM-SEC-001
    // Rule: PM-GA-017
    @PostMapping("/{assemblyId}/agenda")
    @ResponseStatus(HttpStatus.CREATED)
    fun addAgendaItem(
        @PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @RequestBody request: AddAgendaItemRequest, signedIn: JwtAuthenticationToken?,
    ): AgendaItemAdded =
        acts.addAgendaItem(who.of(signedIn), entranceId, assemblyId, request.text, request.itemType, request.absenteeEligible ?: false).let { (item, majority, noticeVoided) ->
            AgendaItemAdded(item.id, item.ordinal, item.text, item.itemType, view(majority), noticeVoided, item.absenteeEligible)
        }

    // Rule: PM-SEC-001
    @PostMapping("/{assemblyId}/schedule")
    fun reschedule(
        @PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @RequestBody request: RescheduleRequest, signedIn: JwtAuthenticationToken?,
    ): AssemblyView =
        acts.reschedule(who.of(signedIn), entranceId, assemblyId, request.scheduledAt, request.place)
            .let { view(it, assemblies.read(entranceId, assemblyId).second) }

    // Rule: PM-GA-007
    // Rule: PM-SEC-001
    @PostMapping("/{assemblyId}/notice-posting")
    @ResponseStatus(HttpStatus.CREATED)
    fun recordPosting(
        @PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @RequestBody request: RecordPostingRequest, signedIn: JwtAuthenticationToken?,
    ): NoticePostingView =
        acts.recordPosting(who.of(signedIn), entranceId, assemblyId, request.postedAt, request.coSignatoryPartyId, request.photoHash).let {
            NoticePostingView(it.id, it.postedAt, it.convenorPartyId, it.coSignatoryPartyId, it.photoHash, it.statedScheduledAt, it.statedPlace, it.statedAgenda)
        }

    /** An entrance's assemblies, the one meeting latest first: enough to find one and open it — for anyone holding a role in it. */
    // Rule: PM-GA-002
    // Rule: PM-SEC-001
    @GetMapping
    fun list(@PathVariable entranceId: UUID, signedIn: JwtAuthenticationToken?): List<AssemblySummaryView> {
        reads.require(entranceId, who.of(signedIn))
        return assemblies.list(entranceId).map { AssemblySummaryView(it.id, it.status, it.scheduledAt, it.convenedAs) }
    }

    // Rule: PM-SEC-001
    @GetMapping("/{assemblyId}")
    fun read(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, signedIn: JwtAuthenticationToken?): AssemblyView {
        reads.require(entranceId, who.of(signedIn))
        return assemblies.read(entranceId, assemblyId).let { (assembly, agenda) -> view(assembly, agenda) }
    }

    private fun view(a: Assembly, agenda: List<AgendaItem>) = AssemblyView(
        a.id, a.entranceId, a.convenedBy, a.convenedAs, a.scheduledAt, a.place, a.mode, a.status, a.urgent, a.urgencyReason, a.noticePostedAt,
        agenda.map { AgendaItemView(it.id, it.ordinal, it.text, it.itemType, it.majorityRuleId, it.absenteeEligible) },
    )

    private fun view(m: MajorityRule) =
        MajorityView(m.id, m.rule, m.thresholdPct, m.comparison.name, m.denominator.name, m.source, m.verified)

    /** The policy refuses, the assembly is not theirs to run, or nobody a record could name is asking. */
    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused): Map<String, String> = mapOf("error" to (e.message ?: "not allowed"), "rule" to e.ruleId)

    /** A capacity that may not convene, an unknown mode or item type, a missing justification. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid request"))

    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** The assembly is not in a state that allows it: already noticed, no agenda, or the notice came too late. */
    @ExceptionHandler(IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: IllegalStateException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))

    /** The item's majority waits on counsel: the request is understood and cannot be carried out. */
    @ExceptionHandler(MajorityPending::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onPending(e: MajorityPending): Map<String, String> = mapOf("error" to (e.message ?: "majority not confirmed"))
}
