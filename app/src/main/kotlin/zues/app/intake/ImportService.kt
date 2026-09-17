package zues.app.intake

import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

/** What recording an import returns: the stored id and the full dry-run report behind the verdict. */
data class ImportResult(val importId: UUID, val report: DryRunReport)

/**
 * Records a fee-sheet import durably. The dry-run runs exactly as in the stateless path (S-31),
 * then its verdict and the source's content hash are stored, so an import is auditable and can be
 * referred to later. The commit that adopts the sheet into `registry`/`money` — one transaction
 * whose rows carry the import id (STAGE1-ADDENDUM §1) — is a later slice, once the seam is settled.
 */
@Service
class ImportService(
    private val aggregates: JdbcAggregateTemplate,
    private val imports: ImportRepository,
) {
    @Transactional
    fun record(entranceId: UUID, request: FeeSheetDryRunRequest): ImportResult {
        val sheet = FeeSheet.parse(request.csv)
        // A malformed tariff throws here (400) before anything is stored; a sheet that will not
        // reproduce does not throw — it is a recorded NEEDS_REVIEW import, which is the point.
        val report = IntakeDryRun.of(
            entranceId.toString(), request.period, request.legalDate, request.businessMultiplier, request.lines, sheet,
        )
        val id = aggregates.insert(
            ImportRow(
                id = UUID.randomUUID(),
                entranceId = entranceId,
                status = if (report.reproduced) "REPRODUCED" else "NEEDS_REVIEW",
                sourceSha = sha256(request.csv),
                rowsParsed = report.rowsParsed,
                differing = report.differing,
                violations = report.violations.size,
            ),
        ).id
        return ImportResult(id, report)
    }

    @Transactional(readOnly = true)
    fun find(id: UUID): ImportRow = imports.findById(id).orElseThrow { NoSuchElementException("no import $id") }

    /** Content-address the source — what the firm actually gave us (STAGE1-ADDENDUM §1, step 1). */
    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
