package zues.app.intake

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
import java.util.UUID

/** The stored import's verdict and provenance — the full per-unit report lives in the create response. */
data class ImportView(
    val id: UUID,
    val status: String,
    val sourceSha: String,
    val rowsParsed: Int,
    val differing: Int,
    val violations: Int,
    /** while the status is REVERT_BLOCKED: what still points at the import's rows, so the registry removed none */
    val revertBlockedBy: String? = null,
    /** while the status is COMMIT_BLOCKED: why the registry adopted none of the import's rows */
    val commitBlockedBy: String? = null,
)

/**
 * Commit a reviewed import: adopt its units into the registry. The sheet is re-presented so the
 * commit binds to exactly the file that was reviewed — its content hash must match the record.
 */
data class CommitRequest(val committedBy: UUID? = null, val sheet: FeeSheetDryRunRequest)

/**
 * Ask for a committed import to be reverted. A reason is required — a reverted legal record says why. The answer is
 * REVERTING: the registry removes the rows afterwards, and the import then reads REVERTED, or REVERT_BLOCKED with
 * what blocks it.
 */
data class RevertRequest(val revertedBy: UUID? = null, val reason: String)

/**
 * Durable imports: record a fee sheet with its Gate-1 verdict, read it back, commit it into the book or revert it —
 * each by the manager or the board of the import's own entrance (PM-BOOK-005), as the sign-in says who is asking; the
 * record names that party, never one a request names. Anybody else is a 403.
 */
@RestController
@RequestMapping("/api/intake")
class ImportController(private val imports: ImportService, private val guard: ImportGuard, private val who: WhoAsks) {

    @PostMapping("/entrances/{entranceId}/imports")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        @PathVariable entranceId: UUID,
        @RequestBody request: FeeSheetDryRunRequest,
        signedIn: JwtAuthenticationToken?,
    ): ImportResult {
        guard.allowed(entranceId, who.of(signedIn))
        return imports.record(entranceId, request)
    }

    @GetMapping("/imports/{id}")
    fun get(@PathVariable id: UUID, signedIn: JwtAuthenticationToken?): ImportView {
        val record = imports.find(id)
        guard.allowed(record.entranceId, who.of(signedIn))                       // the import's own entrance, never one a request names
        return view(record)
    }

    @PostMapping("/imports/{id}/commit")
    fun commit(@PathVariable id: UUID, @RequestBody request: CommitRequest, signedIn: JwtAuthenticationToken?): CommitResult {
        val actor = guard.actor(imports.find(id).entranceId, who.of(signedIn), request.committedBy)
        return imports.commit(id, actor, request.sheet)
    }

    @PostMapping("/imports/{id}/revert")
    fun revert(@PathVariable id: UUID, @RequestBody request: RevertRequest, signedIn: JwtAuthenticationToken?): ImportView {
        val actor = guard.actor(imports.find(id).entranceId, who.of(signedIn), request.revertedBy)
        return imports.revert(id, actor, request.reason).let(::view)
    }

    /** Not the manager or the board of the import's entrance → 403, naming the rule; nothing is recorded, adopted or removed. */
    @ExceptionHandler(ImportForbidden::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onForbidden(e: ImportForbidden): Map<String, String> = mapOf("error" to "an entrance's book is entered by its manager or its board", "rule" to e.ruleId)

    private fun view(row: ImportRow) =
        ImportView(row.id, row.status, row.sourceSha, row.rowsParsed, row.differing, row.violations, row.revertBlockedBy, row.commitBlockedBy)

    /** A malformed tariff — an unknown cost stream, allocation key or named cost — is the caller's error. */
    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: RuntimeException): Map<String, String> = mapOf("error" to (e.message ?: "invalid import"))

    /** A commit or revert against an import in the wrong state — a conflict, not a bad request. */
    @ExceptionHandler(ImportStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: ImportStateException): Map<String, String> = mapOf("error" to (e.message ?: "invalid state"))

    /** No import with that id. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))
}
