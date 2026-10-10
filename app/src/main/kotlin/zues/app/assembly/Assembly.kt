package zues.app.assembly

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A general assembly of one entrance. It starts in `DRAFT`; only a recorded posting act takes it
 * further. [convenedAs] is the office the convenor acts in (Rule: PM-GA-002); an urgent assembly
 * carries its justification (Rule: PM-GA-005).
 */
@Table("assembly")
data class Assembly(
    @Id val id: UUID,
    val entranceId: UUID,
    val convenedBy: UUID,
    val convenedAs: String,          // ConvenorOffice
    val scheduledAt: Instant,
    val place: String,
    val mode: String,                // MeetingMode
    val status: String,              // AssemblyStatus
    val urgent: Boolean,
    val urgencyReason: String?,
    val noticePostedAt: Instant? = null,
    val noticePostingId: UUID? = null,
    /** When the date, hour, place or agenda last changed; a notice posted before it stated something else (PM-GA-006). */
    val noticeContentChangedAt: Instant? = null,
    /** Convened by the owners on this petition (Rule: PM-GA-003) — with what unlocked it, kept as it was that day. */
    val petitionId: UUID? = null,
    val demandUnmetNote: String? = null,
    val petitionHeldPct: BigDecimal? = null,
    val petitionThresholdPct: BigDecimal? = null,
    val petitionWeighedOn: LocalDate? = null,
    val lawVersion: String? = null,
    val engineVersion: String? = null,
)

/** Who may convene: the management board, the manager, the control board or controller. Rule: PM-GA-002 */
enum class ConvenorOffice { MB, BM, CTL }

enum class MeetingMode { IN_PERSON, VIDEO, HYBRID }

/** `NOTICED` is reached only by a recorded posting act (Rule: PM-GA-007). */
enum class AssemblyStatus { DRAFT, NOTICED }

/** One item of the agenda, bound to the majority that decides it (Rule: PM-VOTE-004). */
@Table("agenda_item")
data class AgendaItem(
    @Id val id: UUID,
    val entranceId: UUID,
    val assemblyId: UUID,
    val ordinal: Int,
    val text: String,
    val itemType: String,
    val majorityRuleId: String,
)

interface AssemblyRepository : ListCrudRepository<Assembly, UUID> {
    /** The assembly, its row locked until the transaction ends — so two items never take one ordinal. */
    @Query("SELECT * FROM assembly.assembly WHERE id = :id AND entrance_id = :entranceId FOR UPDATE")
    fun lock(id: UUID, entranceId: UUID): Assembly?

    fun findByPetitionId(petitionId: UUID): Assembly?
}

interface AgendaItemRepository : ListCrudRepository<AgendaItem, UUID> {
    fun findByAssemblyIdOrderByOrdinal(assemblyId: UUID): List<AgendaItem>
}
