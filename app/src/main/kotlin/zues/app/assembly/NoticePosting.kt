package zues.app.assembly

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.conversion.DbActionExecutionException
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.registry.Holdings
import zues.app.registry.TitleRole
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The act certifying that the notice was posted at the entrance (Rule: PM-GA-007): when, signed by the
 * convenor and one further person, with the hash of the photograph that shows it (Rule: PM-GA-004). It
 * keeps what the notice stated — date and hour, place, the full agenda (Rule: PM-GA-006). Append-only:
 * a voided notice keeps its act, and the new notice gets a new one.
 */
@Table("notice_posting")
data class NoticePosting(
    @Id val id: UUID,
    val entranceId: UUID,
    val assemblyId: UUID,
    val postedAt: Instant,
    val convenorPartyId: UUID,
    val coSignatoryPartyId: UUID,
    val photoHash: String,
    val statedScheduledAt: Instant,
    val statedPlace: String,
    val statedAgenda: String,
    val recordedAt: Instant,
    /** The sign-in that recorded the act, as tied to the convenor at that moment (PM-SEC-001). */
    val loginIssuer: String? = null,
    val loginSubject: String? = null,
)

interface NoticePostingRepository : ListCrudRepository<NoticePosting, UUID>

/** The notice was posted too close to the meeting: the act does not make the assembly noticed. */
class NoticeTooLate(message: String) : IllegalStateException(message)

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/**
 * Recording the posting act — the only way an assembly leaves `DRAFT`. An email, a push or a message in
 * the application is not delivery of the notice (PM-SYS-013); nothing here listens to one.
 */
@Service
class NoticeService(
    private val aggregates: JdbcAggregateTemplate,
    private val assemblies: AssemblyRepository,
    private val agenda: AgendaItemRepository,
    private val holdings: Holdings,
    private val clock: Clock,
) {
    /**
     * [by] signs the act as the convenor: whoever runs the assembly that day — the one who convened it or, the owner's
     * decision of 2026-10-11, their successor in the same office. Who that may be is [AssemblyActs]' to say.
     */
    // Rule: PM-GA-007
    // Rule: PM-GA-004
    // Rule: PM-GA-006
    @Transactional
    fun recordPosting(entranceId: UUID, assemblyId: UUID, by: Actor, postedAt: Instant, coSignatoryPartyId: UUID, photoHash: String): NoticePosting {
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        check(assembly.status == AssemblyStatus.DRAFT.name) { "the notice of this assembly is already posted; it is ${assembly.status}" }
        val now = clock.instant()
        require(!postedAt.isAfter(now)) { "a posting act records a posting that has happened, not one to come" }
        require(assembly.noticeContentChangedAt?.let { !postedAt.isBefore(it) } ?: true) {
            "the date, hour, place or agenda changed after $postedAt: a notice posted then did not state this assembly (PM-GA-006)"
        }
        require(coSignatoryPartyId != by.party) { "the posting act is signed by the convenor and one other person (PM-GA-007)" }
        // TODO(legal): PM-GA-007 — the catalogue says "one owner"; whether a holder of use or an occupant may co-sign is unconfirmed,
        // and so is whether "the convenor" of a board's assembly is the member who convened or the board
        val ownersThatDay = holdings.inForce(entranceId, LocalDate.parse(toSofiaDate(postedAt)), TitleRole.OWN).holdings.map { it.partyId }
        require(coSignatoryPartyId in ownersThatDay) { "the posting act is co-signed by an owner in the entrance on the day of posting (PM-GA-007)" }
        require(SHA256_HEX.matches(photoHash)) { "the photograph of the posted notice is given by its SHA-256, in lower-case hex (PM-GA-004)" }
        val items = agenda.findByAssemblyIdOrderByOrdinal(assemblyId)
        check(items.isNotEmpty()) { "a notice states the full agenda, and this assembly has none (PM-GA-006)" }
        if (!noticeInTime(postedAt, assembly.scheduledAt, assembly.urgent)) throw NoticeTooLate(tooSoon(postedAt, assembly.urgent))

        val act = NoticePosting(
            id = UUID.randomUUID(), entranceId = entranceId, assemblyId = assemblyId, postedAt = postedAt,
            convenorPartyId = by.party, coSignatoryPartyId = coSignatoryPartyId, photoHash = photoHash,
            statedScheduledAt = assembly.scheduledAt, statedPlace = assembly.place,
            statedAgenda = items.joinToString("\n") { "${it.ordinal}. ${it.text}" }, recordedAt = now,
            loginIssuer = by.loginIssuer, loginSubject = by.loginSubject,
        )
        try {
            aggregates.insert(act)
        } catch (e: DbActionExecutionException) {
            if (missingReference(e) != null) throw IllegalArgumentException("the signatory $coSignatoryPartyId is not a registered party")
            throw e
        }
        assemblies.save(assembly.copy(status = AssemblyStatus.NOTICED.name, noticePostedAt = postedAt, noticePostingId = act.id))
        return act
    }
}
