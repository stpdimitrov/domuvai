package zues.app.assembly

import org.springframework.dao.DataIntegrityViolationException
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

/** The item's type is known to the law, but its majority is not confirmed: the item cannot be bound. */
class MajorityPending(message: String) : RuntimeException(message)

/** Convening a general assembly and building its agenda, while it is a draft. */
@Service
class AssemblyService(
    private val aggregates: JdbcAggregateTemplate,
    private val assemblies: AssemblyRepository,
    private val agenda: AgendaItemRepository,
    private val majorities: Majorities,
) {
    // Rule: PM-GA-002
    // Rule: PM-GA-005
    @Transactional
    fun convene(entranceId: UUID, request: Convene): Assembly {
        val office = enumValueOf<ConvenorOffice>(request.convenedAs)     // any other capacity may not convene
        val mode = enumValueOf<MeetingMode>(request.mode)
        require(request.place.isNotBlank()) { "the place of the assembly is required" }
        val reason = request.urgencyReason?.trim()?.ifEmpty { null }
        require(!request.urgent || reason != null) { "an urgent assembly needs its justification (PM-GA-005)" }
        require(request.urgent || reason == null) { "a justification of urgency was given for an assembly not marked urgent (PM-GA-005)" }
        val assembly = Assembly(
            id = UUID.randomUUID(), entranceId = entranceId, convenedBy = request.convenedBy, convenedAs = office.name,
            scheduledAt = request.scheduledAt, place = request.place.trim(), mode = mode.name,
            status = AssemblyStatus.DRAFT.name, urgent = request.urgent, urgencyReason = reason,
        )
        try {
            return aggregates.insert(assembly)
        } catch (e: DbActionExecutionException) {
            // the registry's own tables are not this module's to read: the foreign keys say who is missing
            val refused = e.cause as? DataIntegrityViolationException ?: throw e
            if (refused.message.orEmpty().contains("foreign key")) throw NoSuchElementException("the entrance or the convenor is not registered")
            throw e
        }
    }

    /**
     * Puts an item on a draft assembly's agenda, bound to the majority in force for its type on the
     * day the assembly meets — a Sofia calendar day (PM-SYS-004), never today's law (PM-SYS-002).
     */
    // Rule: PM-VOTE-004
    @Transactional
    fun addAgendaItem(entranceId: UUID, assemblyId: UUID, text: String, itemType: String): Pair<AgendaItem, MajorityRule> {
        require(text.isNotBlank()) { "an agenda item needs its text" }
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        val majority = try {
            majorities.on(itemType, toSofiaDate(assembly.scheduledAt))
        } catch (e: MajorityNotInForce) {
            if (e.pending != null) throw MajorityPending(e.message.orEmpty())
            throw IllegalArgumentException("unknown agenda item type $itemType")
        }
        val ordinal = (agenda.findByAssemblyIdOrderByOrdinal(assemblyId).maxOfOrNull { it.ordinal } ?: 0) + 1
        val item = aggregates.insert(
            AgendaItem(UUID.randomUUID(), entranceId, assemblyId, ordinal, text.trim(), majority.itemType, majority.id),
        )
        return item to majority
    }

    @Transactional(readOnly = true)
    fun read(entranceId: UUID, assemblyId: UUID): Pair<Assembly, List<AgendaItem>> {
        val assembly = assemblies.findById(assemblyId).filter { it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no assembly $assemblyId in entrance $entranceId") }
        return assembly to agenda.findByAssemblyIdOrderByOrdinal(assemblyId)
    }
}
