package zues.app.intake

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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
data class CommitRequest(val committedBy: UUID, val sheet: FeeSheetDryRunRequest)

/**
 * Ask for a committed import to be reverted. A reason is required — a reverted legal record says why. The answer is
 * REVERTING: the registry removes the rows afterwards, and the import then reads REVERTED, or REVERT_BLOCKED with
 * what blocks it.
 */
data class RevertRequest(val revertedBy: UUID, val reason: String)

/** Durable imports: record a fee sheet with its Gate-1 verdict, and read it back. */
@RestController
@RequestMapping("/api/intake")
class ImportController(private val imports: ImportService) {

    @PostMapping("/entrances/{entranceId}/imports")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        @PathVariable entranceId: UUID,
        @RequestBody request: FeeSheetDryRunRequest,
    ): ImportResult =
        imports.record(entranceId, request)

    @GetMapping("/imports/{id}")
    fun get(@PathVariable id: UUID): ImportView =
        imports.find(id).let(::view)

    @PostMapping("/imports/{id}/commit")
    fun commit(@PathVariable id: UUID, @RequestBody request: CommitRequest): CommitResult =
        imports.commit(id, request.committedBy, request.sheet)

    @PostMapping("/imports/{id}/revert")
    fun revert(@PathVariable id: UUID, @RequestBody request: RevertRequest): ImportView =
        imports.revert(id, request.revertedBy, request.reason).let(::view)

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
