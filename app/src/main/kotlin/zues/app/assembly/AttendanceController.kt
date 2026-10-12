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
import java.time.Instant
import java.util.UUID

data class RecordAttendanceRequest(val attendeePartyId: UUID, val channel: String)

data class VerifyIdentityRequest(val how: String)

/** A line of the register. [counted] is false for a remote participant not yet identified; [represents] is whom the line makes present. */
data class AttendanceView(
    val id: UUID,
    val sessionNo: Int,
    val attendeePartyId: UUID,
    val channel: String,
    val identityVerified: Boolean,
    val identityVerifiedAt: Instant?,
    val counted: Boolean,
    val represents: List<UUID>,
    val recordedBy: UUID,
    val recordedAt: Instant,
)

/**
 * The register of who is present at an assembly (PM-GA-012, PM-GA-016). It is kept by whoever runs the assembly, signed
 * in — never by a field of the request — and anybody else is a 403 naming the rule (PM-SEC-001).
 */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/attendance")
class AttendanceController(private val acts: AssemblyActs, private val attendance: AttendanceService, private val who: WhoAsks) {

    // Rule: PM-GA-012
    // Rule: PM-SEC-001
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        @PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @RequestBody request: RecordAttendanceRequest, signedIn: JwtAuthenticationToken?,
    ): AttendanceView = view(acts.recordAttendance(who.of(signedIn), entranceId, assemblyId, request.attendeePartyId, request.channel))

    // Rule: PM-GA-016
    // Rule: PM-SEC-001
    @PostMapping("/{attendanceId}/identity-verification")
    @ResponseStatus(HttpStatus.CREATED)
    fun verifyIdentity(
        @PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @PathVariable attendanceId: UUID, @RequestBody request: VerifyIdentityRequest,
        signedIn: JwtAuthenticationToken?,
    ): AttendanceView = view(acts.verifyIdentity(who.of(signedIn), entranceId, assemblyId, attendanceId, request.how))

    @GetMapping
    fun register(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID): List<AttendanceView> =
        attendance.register(entranceId, assemblyId).map(::view)

    private fun view(p: Present) = p.attendance.let {
        AttendanceView(it.id, it.sessionNo, it.attendeePartyId, it.channel, p.identityVerified, p.identityVerifiedAt, p.counted, p.represented.sortedBy { id -> id.toString() }, it.recordedBy, it.recordedAt)
    }

    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused): Map<String, String> = mapOf("error" to (e.message ?: "not allowed"), "rule" to e.ruleId)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid request"))

    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** Not yet noticed, not yet the day, already on the register, or already verified. */
    @ExceptionHandler(IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: IllegalStateException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))
}
