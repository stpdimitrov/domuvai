package zues.app.intake

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The firm's fee sheet plus the tariff it was built on. The dry-run recomputes and compares; it
 * stores nothing (persistence — the import record, commit and revert — is a later slice).
 *
 * [mapping] is the **confirmed** column → field mapping (ADR-012): supply it and the sheet is read
 * through it, so a layout the profiler cannot recognise parses once a human has confirmed it; omit it
 * and the profiler proposes one from the header, which is the standard-layout path. Confirm a proposal
 * with `POST …/fee-sheet/profile` first.
 */
data class FeeSheetDryRunRequest(
    val period: String,          // YYYY-MM
    val legalDate: String,       // the date the law is read at (PM-SYS-002)
    val businessMultiplier: Int? = null,
    val lines: List<TariffInput>,
    val csv: String,
    val mapping: Map<String, IntakeField>? = null,
)

/** Just the sheet: the profiler reads its header row and proposes a column → field mapping to confirm. */
data class ProfileRequest(val csv: String)

/**
 * Intake's edge for Gate 1: does our engine reproduce the firm's spreadsheet to the cent? Owns no
 * rules — it enforces ORG and FEE on the way in (ADR-003).
 */
@RestController
@RequestMapping("/api/intake/entrances/{entranceId}/fee-sheet")
class IntakeController {

    /**
     * Propose a column → field mapping for a sheet's header (ADR-012, STAGE1-ADDENDUM §1): the caller
     * reviews it, corrects an unmapped or mis-guessed column, and returns the confirmed mapping on the
     * dry-run/record/commit. The entrance scopes the workflow; the proposal is a pure function of the
     * header, so nothing is stored and nothing is adopted here.
     */
    @PostMapping("/profile")
    fun profile(@PathVariable entranceId: UUID, @RequestBody request: ProfileRequest): ProposedMapping =
        MappingProfiler.profile(headerColumns(request.csv))

    @PostMapping("/dry-run")
    fun dryRun(@PathVariable entranceId: UUID, @RequestBody request: FeeSheetDryRunRequest): DryRunReport =
        IntakeDryRun.of(
            entranceId = entranceId.toString(),
            period = request.period,
            legalDate = request.legalDate,
            businessMultiplier = request.businessMultiplier,
            lines = request.lines,
            sheet = FeeSheet.parse(request.csv, request.mapping),
        )

    /** The header row's columns, trimmed — an empty sheet yields no columns, so every required field is missing. */
    private fun headerColumns(csv: String): List<String> =
        csv.trim().lines().firstOrNull()?.split(",")?.map { it.trim() } ?: emptyList()

    /** A malformed tariff — an unknown cost stream or allocation key — is the caller's error. */
    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: RuntimeException): Map<String, String> = mapOf("error" to (e.message ?: "invalid dry-run request"))
}
