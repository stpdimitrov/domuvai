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

data class AgendaItemAdded(val id: UUID, val ordinal: Int, val text: String, val itemType: String, val majority: MajorityView)

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
    val agenda: List<AgendaItemView>,
)

/** Convening a general assembly and its agenda (PM-GA-002, PM-GA-005, PM-VOTE-004). */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/assemblies")
class AssemblyController(private val assemblies: AssemblyService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun convene(@PathVariable entranceId: UUID, @RequestBody request: Convene): AssemblyView =
        view(assemblies.convene(entranceId, request), emptyList())

    @PostMapping("/{assemblyId}/agenda")
    @ResponseStatus(HttpStatus.CREATED)
    fun addAgendaItem(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @RequestBody request: AddAgendaItemRequest): AgendaItemAdded =
        assemblies.addAgendaItem(entranceId, assemblyId, request.text, request.itemType).let { (item, majority) ->
            AgendaItemAdded(item.id, item.ordinal, item.text, item.itemType, view(majority))
        }

    @GetMapping("/{assemblyId}")
    fun read(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID): AssemblyView =
        assemblies.read(entranceId, assemblyId).let { (assembly, agenda) -> view(assembly, agenda) }

    private fun view(a: Assembly, agenda: List<AgendaItem>) = AssemblyView(
        a.id, a.entranceId, a.convenedBy, a.convenedAs, a.scheduledAt, a.place, a.mode, a.status, a.urgent, a.urgencyReason,
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

    /** The assembly is no longer a draft. */
    @ExceptionHandler(IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: IllegalStateException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))

    /** The item's majority waits on counsel: the request is understood and cannot be carried out. */
    @ExceptionHandler(MajorityPending::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onPending(e: MajorityPending): Map<String, String> = mapOf("error" to (e.message ?: "majority not confirmed"))
}
