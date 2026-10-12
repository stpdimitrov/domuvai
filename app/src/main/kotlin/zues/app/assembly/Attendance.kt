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
 * One line of the register: the person present, whether a remote one has been identified, and whom their presence
 * brings — themself if they own in the entrance that day, and every principal whose proxy for this assembly names
 * them. A remote participant not yet identified is on the register and brings nobody (Rule: PM-GA-016).
 */
data class Present(val attendance: Attendance, val identityVerified: Boolean, val brings: Set<UUID>) {
    val counted: Boolean get() = attendance.channel == AttendanceChannel.IN_PERSON.name || identityVerified

    /** Whom this line makes present for quorum: nobody until it counts. */
    val represented: Set<UUID> get() = if (counted) brings else emptySet()
}

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
     * Enters a person in the register of the sitting: an owner in the entrance today, or the representative a proxy
     * registered for this assembly names — nobody else's presence is anybody's for quorum. Recorded by whoever runs
     * the assembly ([by]); the register opens on the day the assembly meets.
     */
    // Rule: PM-GA-012
    // Rule: PM-GA-016
    @Transactional
    fun record(entranceId: UUID, assemblyId: UUID, by: Actor, attendeePartyId: UUID, channel: String): Present {
        val through = AttendanceChannel.entries.firstOrNull { it.name == channel }
            ?: throw IllegalArgumentException("a person is present IN_PERSON or by VIDEO, not \"$channel\"")
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        check(assembly.status == AssemblyStatus.NOTICED.name) { "the register is kept for an assembly whose notice is posted; this one is ${assembly.status}" }
        val today = today()
        check(today >= LocalDate.parse(toSofiaDate(assembly.scheduledAt))) { "the register opens on the day the assembly meets" }
        check(attendance.findByAssemblyIdAndSessionNoOrderByRecordedAt(assemblyId, FIRST_SITTING).none { it.attendeePartyId == attendeePartyId }) {
            "this person is already on the register of the sitting"
        }
        val brings = brings(entranceId, assemblyId, attendeePartyId, today)
        require(brings.isNotEmpty()) { "the register takes an owner in the entrance, or the representative a registered proxy names (PM-GA-012)" }
        val row = aggregates.insert(
            Attendance(UUID.randomUUID(), entranceId, assemblyId, FIRST_SITTING, attendeePartyId, through.name, by.party, by.loginIssuer, by.loginSubject, clock.instant()),
        )
        return Present(row, identityVerified = false, brings = brings)
    }

    /** Records that a remote participant was reliably identified, and how: from then on their presence counts. */
    // Rule: PM-GA-016
    @Transactional
    fun verifyIdentity(entranceId: UUID, assemblyId: UUID, attendanceId: UUID, by: Actor, how: String): Present {
        require(how.isNotBlank()) { "say how the remote participant was identified (PM-GA-016)" }
        assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        val row = attendance.findById(attendanceId).filter { it.assemblyId == assemblyId && it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no such line on the register of assembly $assemblyId") }
        check(row.channel == AttendanceChannel.VIDEO.name) { "a person present in person is identified by being there" }
        check(!verifications.existsById(attendanceId)) { "this participant's identity is already verified" }
        aggregates.insert(IdentityVerification(attendanceId, assemblyId, entranceId, how.trim(), by.party, by.loginIssuer, by.loginSubject, clock.instant()))
        return Present(row, identityVerified = true, brings = brings(entranceId, assemblyId, row.attendeePartyId, today()))
    }

    /** The register of a sitting as it stands today: every line, counted or not, with whom it brings. */
    @Transactional(readOnly = true)
    fun register(entranceId: UUID, assemblyId: UUID, sessionNo: Int = FIRST_SITTING): List<Present> {
        assemblies.findById(assemblyId).filter { it.entranceId == entranceId }.orElseThrow { NoSuchElementException("no assembly $assemblyId in entrance $entranceId") }
        val verified = verifications.findByAssemblyId(assemblyId).map { it.attendanceId }.toSet()
        val today = today()
        return attendance.findByAssemblyIdAndSessionNoOrderByRecordedAt(assemblyId, sessionNo)
            .map { Present(it, it.id in verified, brings(entranceId, assemblyId, it.attendeePartyId, today)) }
    }

    /**
     * Whom a person's presence brings on [on]: themself if they own in the entrance that day (PM-ORG-011), and the
     * principals of the proxies registered for this assembly that name them — only proxies somebody signed in for.
     */
    private fun brings(entranceId: UUID, assemblyId: UUID, attendee: UUID, on: LocalDate): Set<UUID> {
        val owners = holdings.inForce(entranceId, on, TitleRole.OWN).holdings.map { it.partyId }.toSet()
        val principals = proxies.findByAssemblyId(assemblyId).filter { it.agentPartyId == attendee && it.loginSubject != null }.map { it.principalPartyId }
        return buildSet { if (attendee in owners) add(attendee); addAll(principals) }
    }

    private fun today(): LocalDate = LocalDate.parse(toSofiaDate(clock.instant()))   // a Sofia calendar day (PM-SYS-004)
}
