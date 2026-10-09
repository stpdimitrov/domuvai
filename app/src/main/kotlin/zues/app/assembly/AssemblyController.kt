package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.law.MajorityRule
import java.time.Instant
import java.util.UUID

data class AddAgendaItemRequest(val text: String, val itemType: String)

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
)

data class RescheduleRequest(val scheduledAt: Instant, val place: String)

/** The posting act as certified: when, the photograph's SHA-256, and the one signatory beside the convenor. */
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

data class AgendaItemView(val id: UUID, val ordinal: Int, val text: String, val itemType: String, val majorityRuleId: String)

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

/** Convening a general assembly, its agenda and its notice (PM-GA-002, PM-GA-004 … PM-GA-007, PM-VOTE-004). */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/assemblies")
class AssemblyController(private val assemblies: AssemblyService, private val notices: NoticeService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun convene(@PathVariable entranceId: UUID, @RequestBody request: Convene): AssemblyView =
        view(assemblies.convene(entranceId, request), emptyList())

    @PostMapping("/{assemblyId}/agenda")
    @ResponseStatus(HttpStatus.CREATED)
    fun addAgendaItem(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @RequestBody request: AddAgendaItemRequest): AgendaItemAdded =
        assemblies.addAgendaItem(entranceId, assemblyId, request.text, request.itemType).let { (item, majority, noticeVoided) ->
            AgendaItemAdded(item.id, item.ordinal, item.text, item.itemType, view(majority), noticeVoided)
        }

    @PostMapping("/{assemblyId}/schedule")
    fun reschedule(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @RequestBody request: RescheduleRequest): AssemblyView =
        assemblies.reschedule(entranceId, assemblyId, request.scheduledAt, request.place)
            .let { view(it, assemblies.read(entranceId, assemblyId).second) }

    @PostMapping("/{assemblyId}/notice-posting")
    @ResponseStatus(HttpStatus.CREATED)
    fun recordPosting(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @RequestBody request: RecordPostingRequest): NoticePostingView =
        notices.recordPosting(entranceId, assemblyId, request.postedAt, request.coSignatoryPartyId, request.photoHash).let {
            NoticePostingView(it.id, it.postedAt, it.convenorPartyId, it.coSignatoryPartyId, it.photoHash, it.statedScheduledAt, it.statedPlace, it.statedAgenda)
        }

    @GetMapping("/{assemblyId}")
    fun read(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID): AssemblyView =
        assemblies.read(entranceId, assemblyId).let { (assembly, agenda) -> view(assembly, agenda) }

    private fun view(a: Assembly, agenda: List<AgendaItem>) = AssemblyView(
        a.id, a.entranceId, a.convenedBy, a.convenedAs, a.scheduledAt, a.place, a.mode, a.status, a.urgent, a.urgencyReason, a.noticePostedAt,
        agenda.map { AgendaItemView(it.id, it.ordinal, it.text, it.itemType, it.majorityRuleId) },
    )

    private fun view(m: MajorityRule) =
        MajorityView(m.id, m.rule, m.thresholdPct, m.comparison.name, m.denominator.name, m.source, m.verified)

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
