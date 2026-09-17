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
 */
data class FeeSheetDryRunRequest(
    val period: String,          // YYYY-MM
    val legalDate: String,       // the date the law is read at (PM-SYS-002)
    val businessMultiplier: Int? = null,
    val lines: List<TariffInput>,
    val csv: String,
)

/**
 * Intake's edge for Gate 1: does our engine reproduce the firm's spreadsheet to the cent? Owns no
 * rules — it enforces ORG and FEE on the way in (ADR-003).
 */
@RestController
@RequestMapping("/api/intake/entrances/{entranceId}/fee-sheet")
class IntakeController {

    @PostMapping("/dry-run")
    fun dryRun(@PathVariable entranceId: UUID, @RequestBody request: FeeSheetDryRunRequest): DryRunReport =
        IntakeDryRun.of(
            entranceId = entranceId.toString(),
            period = request.period,
            legalDate = request.legalDate,
            businessMultiplier = request.businessMultiplier,
            lines = request.lines,
            sheet = FeeSheet.parse(request.csv),
        )

    /** A malformed tariff — an unknown cost stream or allocation key — is the caller's error. */
    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: RuntimeException): Map<String, String> = mapOf("error" to (e.message ?: "invalid dry-run request"))
}
