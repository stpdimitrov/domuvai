package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One weighing of quorum as it was recorded: what was represented against what, and whether the sitting opened on it. */
data class SittingView(
    val id: UUID,
    val sessionNo: Int,
    val quorumMet: Boolean,
    val openedAt: Instant?,
    val representedPct: String,
    val thresholdPct: String,
    val thresholdConstant: String,
    val thresholdVerified: Boolean,
    val weighedOn: LocalDate,
    val weighedAt: Instant,
    val weighedBy: UUID,
    val basisHash: String,
    val lawVersion: String,
    val engineVersion: String,
)

/**
 * The meter: the share of all the ideal parts the register makes present, the threshold with its source, and whether
 * a sitting could open on it. [cannotWeigh] lists why it cannot be weighed at all; [derivedParts] warns that some of
 * the weight rests on ideal parts derived from area.
 */
data class QuorumView(
    val sessionNo: Int,
    val opensFrom: Instant,
    val weighedOn: LocalDate,
    val representedPct: String,
    val thresholdPct: String,
    val thresholdSource: String,
    val thresholdVerified: Boolean,
    val met: Boolean,
    val cannotWeigh: List<String>,
    val derivedParts: Boolean,
    /** Where one owner held more than the law's share at the notice date, who and how much: the threshold is the higher one (PM-GA-015). */
    val concentratedBy: UUID?,
    val concentratedPct: String?,
    val sittings: List<SittingView>,
)

/**
 * Quorum and the sittings of a general assembly (PM-GA-012). The meter is read by anybody; a sitting is weighed and
 * opened by whoever runs the assembly, signed in — anybody else is a 403 naming the rule (PM-SEC-001).
 */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}")
class SittingController(private val acts: AssemblyActs, private val sittings: SittingService, private val who: WhoAsks) {

    // Rule: PM-GA-012
    // Rule: PM-GA-013
    // Rule: PM-GA-014
    // Rule: PM-GA-015
    @GetMapping("/quorum")
    fun quorum(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID): QuorumView =
        sittings.read(entranceId, assemblyId).let { (q, weighed, n, opensFrom) ->
            QuorumView(n, opensFrom, q.on, q.representedPct.percent(), q.thresholdPct.percent(), q.thresholdSource, q.thresholdVerified, q.met, q.cannotWeigh, q.derivedParts, q.concentratedBy, q.concentratedPct?.percent(), weighed.map(::view))
        }

    // Rule: PM-GA-012
    // Rule: PM-GA-013
    // Rule: PM-GA-014
    // Rule: PM-SEC-001
    @PostMapping("/sittings")
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, signedIn: JwtAuthenticationToken?): SittingView =
        view(acts.openSitting(who.of(signedIn), entranceId, assemblyId))

    private fun view(s: Sitting) = SittingView(
        s.id, s.sessionNo, s.quorumMet, s.openedAt, s.represented.percent(), s.thresholdPct.percent(), s.thresholdConstant, s.thresholdVerified,
        s.weighedOn, s.weighedAt, s.weighedBy, s.basisHash, s.lawVersion, s.engineVersion,
    )

    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused): Map<String, String> = mapOf("error" to (e.message ?: "not allowed"), "rule" to e.ruleId)

    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))

    /** Not yet the hour, already weighed, not noticed — or the quorum cannot be weighed, and why. */
    @ExceptionHandler(IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: IllegalStateException): Map<String, String> = mapOf("error" to (e.message ?: "conflict"))
}
