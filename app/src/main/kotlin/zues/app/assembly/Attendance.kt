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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A person present at a sitting of an assembly — in person or by video (Rule: PM-GA-012). Whom they represent is read from the proxies. */
@Table("attendance")
data class Attendance(
    @Id val id: UUID,
    val entranceId: UUID,
    val assemblyId: UUID,
    val sessionNo: Int,
    val attendeePartyId: UUID,
    val channel: String,            // AttendanceChannel
    val recordedBy: UUID,
    val loginIssuer: String,
    val loginSubject: String,
    val recordedAt: Instant,
)

enum class AttendanceChannel { IN_PERSON, VIDEO }

/** That a remote participant was reliably identified: who verified, how, when (Rule: PM-GA-016). One per attendance. */
@Table("identity_verification")
data class IdentityVerification(
    @Id val attendanceId: UUID,
    val assemblyId: UUID,
    val entranceId: UUID,
    val how: String,
    val verifiedBy: UUID,
    val loginIssuer: String,
    val loginSubject: String,
    val verifiedAt: Instant,
)

interface AttendanceRepository : ListCrudRepository<Attendance, UUID> {
    fun findByAssemblyIdAndSessionNoOrderByRecordedAt(assemblyId: UUID, sessionNo: Int): List<Attendance>
}

interface IdentityVerificationRepository : ListCrudRepository<IdentityVerification, UUID> {
    fun findByAssemblyId(assemblyId: UUID): List<IdentityVerification>
}

/**
 * One line of the register: the person present, when a remote one was identified, and whom their presence brings —
 * themself if they own in the entrance on the day of the sitting, and every owner whose proxy for the whole agenda,
 * registered before the line was entered, names them. A remote participant not yet identified is on the register and
 * brings nobody (Rule: PM-GA-016).
 */
data class Present(val attendance: Attendance, val identityVerifiedAt: Instant?, val brings: Set<UUID>) {
    val identityVerified: Boolean get() = identityVerifiedAt != null
    val counted: Boolean get() = attendance.channel == AttendanceChannel.IN_PERSON.name || identityVerified

    /** Whom this line makes present for quorum: nobody until it counts. */
    val represented: Set<UUID> get() = if (counted) brings else emptySet()
}

/** The register is kept from the notice on, and through the sitting: somebody arriving after it opened is entered too. */
private val REGISTER_KEPT = setOf(AssemblyStatus.NOTICED.name, AssemblyStatus.OPEN.name)

/** The first sitting. Which sitting an assembly is at, and how many there may be, is the quorum ladder's to say. */
const val FIRST_SITTING = 1

@Service
class AttendanceService(
    private val aggregates: JdbcAggregateTemplate,
    private val assemblies: AssemblyRepository,
    private val attendance: AttendanceRepository,
    private val verifications: IdentityVerificationRepository,
    private val proxies: ProxyRepository,
    private val holdings: Holdings,
    private val clock: Clock,
) {
    /**
     * Enters a person in the register of the sitting: an owner in the entrance that day, or the representative a
     * proxy registered for this assembly names — nobody else's presence is anybody's for quorum. Recorded by whoever
     * runs the assembly ([by]), on the day the assembly meets and on no other; nobody is present twice — an owner who
     * gave a proxy is represented, and is not entered beside their representative (Rule: PM-ORG-005).
     */
    // Rule: PM-GA-012
    // Rule: PM-GA-016
    // Rule: PM-ORG-005
    @Transactional
    fun record(entranceId: UUID, assemblyId: UUID, by: Actor, attendeePartyId: UUID, channel: String): Present {
        val through = AttendanceChannel.entries.firstOrNull { it.name == channel }
            ?: throw IllegalArgumentException("a person is present IN_PERSON or by VIDEO, not \"$channel\"")
        val assembly = sittingToday(entranceId, assemblyId)
        require(assembly.mode == MeetingMode.HYBRID.name || assembly.mode == through.name) {
            "this assembly meets ${assembly.mode}: nobody is present at it ${through.name} (PM-GA-016)"
        }
        check(attendance.findByAssemblyIdAndSessionNoOrderByRecordedAt(assemblyId, FIRST_SITTING).none { it.attendeePartyId == attendeePartyId }) {
            "this person is already on the register of the sitting"
        }
        check(countedProxies(assemblyId).none { it.principalPartyId == attendeePartyId }) {
            "this owner gave a proxy for this assembly and is represented: they are not entered beside their representative (PM-ORG-005)"
        }
        val now = clock.instant()
        val brings = brings(assembly, attendeePartyId, enteredAt = now)
        require(brings.isNotEmpty()) { "the register takes an owner in the entrance, or the representative a registered proxy names (PM-GA-012)" }
        val row = aggregates.insert(
            Attendance(UUID.randomUUID(), entranceId, assemblyId, FIRST_SITTING, attendeePartyId, through.name, by.party, by.loginIssuer, by.loginSubject, now),
        )
        return Present(row, identityVerifiedAt = null, brings = brings)
    }

    /**
     * Records that a remote participant was reliably identified, and how: from then on their presence counts. By
     * whoever runs the assembly, on the day of the sitting — and never for themself: nobody attests their own identity.
     */
    // Rule: PM-GA-016
    // TODO(legal): PM-GA-016 — what "reliable identification" requires is not in the catalogue; the keeper's statement of how is recorded
    @Transactional
    fun verifyIdentity(entranceId: UUID, assemblyId: UUID, attendanceId: UUID, by: Actor, how: String): Present {
        require(how.isNotBlank()) { "say how the remote participant was identified (PM-GA-016)" }
        val assembly = sittingToday(entranceId, assemblyId)
        val row = attendance.findById(attendanceId).filter { it.assemblyId == assemblyId && it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no such line on the register of assembly $assemblyId") }
        check(row.channel == AttendanceChannel.VIDEO.name) { "a person present in person is identified by being there" }
        require(row.attendeePartyId != by.party) { "nobody verifies their own identity: another who runs the assembly does, or they attend in person (PM-GA-016)" }
        check(!verifications.existsById(attendanceId)) { "this participant's identity is already verified" }
        val verified = aggregates.insert(IdentityVerification(attendanceId, assemblyId, entranceId, how.trim(), by.party, by.loginIssuer, by.loginSubject, clock.instant()))
        return Present(row, verified.verifiedAt, brings(assembly, row.attendeePartyId, enteredAt = row.recordedAt))
    }

    /** The register of a sitting: every line, counted or not, with whom it brings — as of the day of the sitting, whenever it is read. */
    @Transactional(readOnly = true)
    fun register(entranceId: UUID, assemblyId: UUID, sessionNo: Int = FIRST_SITTING): List<Present> {
        val assembly = assemblies.findById(assemblyId).filter { it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no assembly $assemblyId in entrance $entranceId") }
        val verifiedAt = verifications.findByAssemblyId(assemblyId).associate { it.attendanceId to it.verifiedAt }
        return attendance.findByAssemblyIdAndSessionNoOrderByRecordedAt(assemblyId, sessionNo)
            .map { Present(it, verifiedAt[it.id], brings(assembly, it.attendeePartyId, enteredAt = it.recordedAt)) }
    }

    /** Whether anybody has been entered: from then on what the notice stated — the date, the place, the agenda — stays as it is. */
    @Transactional(readOnly = true)
    fun begun(assemblyId: UUID): Boolean = attendance.findByAssemblyIdAndSessionNoOrderByRecordedAt(assemblyId, FIRST_SITTING).isNotEmpty()

    /** Whether this party is on the register of the assembly: a person present does not then hand their presence to another. */
    @Transactional(readOnly = true)
    fun isPresent(assemblyId: UUID, partyId: UUID): Boolean =
        attendance.findByAssemblyIdAndSessionNoOrderByRecordedAt(assemblyId, FIRST_SITTING).any { it.attendeePartyId == partyId }

    /** The assembly, locked, when its notice is posted and today in Sofia is the day it meets — the register's one day. */
    private fun sittingToday(entranceId: UUID, assemblyId: UUID): Assembly {
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        check(assembly.status in REGISTER_KEPT) { "the register is kept for an assembly whose notice is posted; this one is ${assembly.status}" }
        check(LocalDate.parse(toSofiaDate(clock.instant())) == sittingDay(assembly)) { "the register is kept on the day the assembly meets, ${sittingDay(assembly)}" }
        return assembly
    }

    private fun sittingDay(assembly: Assembly): LocalDate = LocalDate.parse(toSofiaDate(assembly.scheduledAt))   // a Sofia calendar day (PM-SYS-004)

    /** The proxies that make somebody present: registered by somebody signed in, and for the whole agenda. */
    // TODO(legal): PM-GA-012 — whether a proxy limited to listed items makes its principal present for quorum is not in the
    // catalogue; the strictest reading is taken: it does not.
    private fun countedProxies(assemblyId: UUID) =
        proxies.findByAssemblyId(assemblyId).filter { it.loginSubject != null && it.scope == ProxyScope.WHOLE_AGENDA.name }

    /**
     * Whom a person's presence brings: themself, and the principals of the counted proxies that name them and were
     * registered before the line was entered — each only if they own in the entrance on the day of the sitting
     * (PM-ORG-011). Owners only: a holder of a right of use is not weighed, so is not brought either.
     */
    private fun brings(assembly: Assembly, attendee: UUID, enteredAt: Instant): Set<UUID> {
        val owners = holdings.inForce(assembly.entranceId, sittingDay(assembly), TitleRole.OWN).holdings.map { it.partyId }.toSet()
        val principals = countedProxies(assembly.id).filter { it.agentPartyId == attendee && !it.registeredAt.isAfter(enteredAt) }.map { it.principalPartyId }
        return (principals + attendee).filterTo(mutableSetOf()) { it in owners }
    }
}
