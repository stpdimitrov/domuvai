package zues.app.assembly

import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.conversion.DbActionExecutionException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.kernel.toSofiaDate
import zues.law.LegalDate
import zues.law.MajorityNotInForce
import zues.law.MajorityRule
import zues.law.majorityRuleOn
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** What a convenor states when convening. */
data class Convene(
    val convenedBy: UUID,
    val convenedAs: String,
    val scheduledAt: Instant,
    val place: String,
    val mode: String,
    val urgent: Boolean = false,
    val urgencyReason: String? = null,
)

/** The majority `law` has in force for an item type on a legal date. */
fun interface Majorities {
    fun on(itemType: String, on: LegalDate): MajorityRule
}

@Component
class LawMajorities : Majorities {
    override fun on(itemType: String, on: LegalDate): MajorityRule = majorityRuleOn(itemType, on)
}

/** The capacity of an assembly convened by the owners on a petition — never accepted from a caller. */
const val OWNERS = "OWNERS"

private val AGENDA_OPEN = setOf(AssemblyStatus.DRAFT.name, AssemblyStatus.NOTICED.name)

/** An agenda item as bound, and whether adding it voided a posted notice (PM-GA-006). */
data class AgendaItemBound(val item: AgendaItem, val majority: MajorityRule, val noticeVoided: Boolean)

/**
 * The database's own words for a reference to a row that is not there, or null if the failure is something
 * else. The registry's tables are not this module's to read: the foreign keys say who is missing, and the
 * constraint's name is in the message whatever language the server speaks.
 */
internal fun missingReference(e: Throwable): String? =
    generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        ?.takeIf { it.sqlState == FOREIGN_KEY_VIOLATION }?.message.orEmpty().ifEmpty { null }

private const val FOREIGN_KEY_VIOLATION = "23503"   // SQLSTATE

/** The item's type is known to the law, but no majority is in force for it on the meeting's day: the item cannot be bound. */
class MajorityPending(message: String) : RuntimeException(message)

/** Convening a general assembly and building its agenda, while it is a draft. */
@Service
class AssemblyService(
    private val aggregates: JdbcAggregateTemplate,
    private val assemblies: AssemblyRepository,
    private val agenda: AgendaItemRepository,
    private val majorities: Majorities,
    private val clock: Clock,
) {
    // Rule: PM-GA-002
    // Rule: PM-GA-005
    @Transactional
    fun convene(entranceId: UUID, request: Convene): Assembly {
        val office = ConvenorOffice.entries.firstOrNull { it.name == request.convenedAs }     // any other capacity may not convene
            ?: throw IllegalArgumentException("an assembly is convened as MB, BM or CTL (PM-GA-002), not as \"${request.convenedAs}\"")
        return draft(entranceId, request.copy(convenedAs = office.name)) { it }
    }

    /**
     * The owners convene on their petition (PM-GA-003). Not reachable by naming a capacity: [PetitionService]
     * calls it once the petition is weighed, and [onPetition] puts the petition and what unlocked it on the draft.
     */
    // Rule: PM-GA-003
    @Transactional
    fun conveneByOwners(entranceId: UUID, request: Convene, onPetition: (Assembly) -> Assembly): Assembly =
        draft(entranceId, request.copy(convenedAs = OWNERS), onPetition)

    private fun draft(entranceId: UUID, request: Convene, complete: (Assembly) -> Assembly): Assembly {
        val mode = MeetingMode.entries.firstOrNull { it.name == request.mode } ?: throw IllegalArgumentException("unknown mode \"${request.mode}\"")
        require(request.place.isNotBlank()) { "the place of the assembly is required" }
        val reason = request.urgencyReason?.trim()?.ifEmpty { null }
        require(!request.urgent || reason != null) { "an urgent assembly needs its justification (PM-GA-005)" }
        require(request.urgent || reason == null) { "a justification of urgency was given for an assembly not marked urgent (PM-GA-005)" }
        requireNoticeStillPossible(request.scheduledAt, request.urgent)
        val assembly = Assembly(
            id = UUID.randomUUID(), entranceId = entranceId, convenedBy = request.convenedBy, convenedAs = request.convenedAs,
            scheduledAt = request.scheduledAt, place = request.place.trim(), mode = mode.name,
            status = AssemblyStatus.DRAFT.name, urgent = request.urgent, urgencyReason = reason, noticeContentChangedAt = clock.instant(),
        )
        try {
            return aggregates.insert(complete(assembly))
        } catch (e: DbActionExecutionException) {
            // the registry's own tables are not this module's to read: the foreign keys say who is missing
            if (missingReference(e)?.contains("convened_by") == true) throw IllegalArgumentException("the convenor ${request.convenedBy} is not a registered party")
            if (missingReference(e) != null) throw NoSuchElementException("no entrance $entranceId")
            throw e
        }
    }

    /**
     * Puts an item on the agenda, bound to the majority in force for its type on the day the assembly
     * meets — a Sofia calendar day (PM-SYS-004), never today's law (PM-SYS-002). The posted notice
     * stated the full agenda, so an item added after posting voids it: the assembly is a draft again
     * and needs a new posting act, with its own notice period.
     */
    // Rule: PM-VOTE-004
    // Rule: PM-GA-006
    @Transactional
    fun addAgendaItem(entranceId: UUID, assemblyId: UUID, text: String, itemType: String): AgendaItemBound {
        require(text.isNotBlank()) { "an agenda item needs its text" }
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        check(assembly.status in AGENDA_OPEN) { "the agenda of an assembly that is ${assembly.status} is closed" }
        val majority = try {
            majorities.on(itemType, toSofiaDate(assembly.scheduledAt))
        } catch (e: MajorityNotInForce) {
            if (e.known || e.pending != null) throw MajorityPending(e.message.orEmpty())
            throw IllegalArgumentException("unknown agenda item type $itemType")
        }
        val ordinal = (agenda.findByAssemblyIdOrderByOrdinal(assemblyId).maxOfOrNull { it.ordinal } ?: 0) + 1
        val item = aggregates.insert(
            AgendaItem(UUID.randomUUID(), entranceId, assemblyId, ordinal, text.trim(), majority.itemType, majority.id),
        )
        return AgendaItemBound(item, majority, noticeVoided = noticeContentChanged(assembly))
    }

    /**
     * Moves the meeting. The notice stated the date, the hour and the place, so a posted notice is void
     * and the assembly is a draft again; the new time must still leave room for a notice.
     */
    // Rule: PM-GA-004
    // Rule: PM-GA-006
    @Transactional
    fun reschedule(entranceId: UUID, assemblyId: UUID, scheduledAt: Instant, place: String): Assembly {
        require(place.isNotBlank()) { "the place of the assembly is required" }
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        check(assembly.status in AGENDA_OPEN) { "an assembly that is ${assembly.status} cannot be moved" }
        if (scheduledAt == assembly.scheduledAt && place.trim() == assembly.place) return assembly     // nothing moved: the notice stands
        requireNoticeStillPossible(scheduledAt, assembly.urgent)
        val moved = assembly.copy(scheduledAt = scheduledAt, place = place.trim())
        noticeContentChanged(moved)
        return assemblies.findById(assemblyId).orElseThrow()
    }

    /**
     * What a notice must state has changed. A posted notice no longer says what the assembly is, so the
     * assembly is a draft again; and no posting from before this moment can be its notice. True when a
     * posted notice was voided.
     */
    private fun noticeContentChanged(assembly: Assembly): Boolean {
        assemblies.save(
            assembly.copy(status = AssemblyStatus.DRAFT.name, noticePostedAt = null, noticePostingId = null, noticeContentChangedAt = clock.instant()),
        )
        return assembly.status == AssemblyStatus.NOTICED.name
    }

    /** Scheduling a meeting that a notice posted this instant would already be too late for is blocked. */
    private fun requireNoticeStillPossible(scheduledAt: Instant, urgent: Boolean) {
        val now = clock.instant()
        require(noticeInTime(now, scheduledAt, urgent)) { tooSoon(now, urgent) }
    }

    @Transactional(readOnly = true)
    fun read(entranceId: UUID, assemblyId: UUID): Pair<Assembly, List<AgendaItem>> {
        val assembly = assemblies.findById(assemblyId).filter { it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no assembly $assemblyId in entrance $entranceId") }
        return assembly to agenda.findByAssemblyIdOrderByOrdinal(assemblyId)
    }
}
