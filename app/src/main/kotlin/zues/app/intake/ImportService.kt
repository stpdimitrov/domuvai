package zues.app.intake

import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/** What recording an import returns: the stored id and the full dry-run report behind the verdict. */
data class ImportResult(val importId: UUID, val report: DryRunReport)

/** What committing an import returns: the id and how many unit rows the commit adopts. */
data class CommitResult(val importId: UUID, val rowsCreated: Int, val rowsChanged: Int)

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
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
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

    /**
     * Commit a reviewed import (STAGE1-ADDENDUM §1, step 6). The sheet is re-presented and its
     * content hash checked against the reviewed import, so a commit adopts exactly the file a human
     * released — nothing is stored between review and commit that could drift, and the row schema
     * stays unmodelled until the pilot spreadsheet defines it. Only a REPRODUCED import commits.
     * This transaction writes intake's own record and the outbox event; the registry adopts the
     * units in its own transaction (MODULE-TEMPLATE laws 1 and 3), so `registry.unit` is never
     * written from here.
     */
    @Transactional
    fun commit(importId: UUID, committedBy: UUID, request: FeeSheetDryRunRequest): CommitResult {
        val record = imports.findById(importId).orElseThrow { NoSuchElementException("no import $importId") }
        if (record.status != "REPRODUCED") {
            throw ImportStateException("import $importId is ${record.status}; only a REPRODUCED import can be committed")
        }
        if (sha256(request.csv) != record.sourceSha) {
            throw ImportStateException("the submitted sheet does not match the reviewed import (source hash differs)")
        }
        val sheet = FeeSheet.parse(request.csv)
        val report = IntakeDryRun.of(
            record.entranceId.toString(), request.period, request.legalDate, request.businessMultiplier, request.lines, sheet,
        )
        if (!report.reproduced) {
            throw ImportStateException("import $importId no longer reproduces the firm's figures; refusing to commit")
        }
        aggregates.update(record.copy(status = "COMMITTED"))
        val units = sheet.rows.map { AdoptedUnit(it.designation, it.idealParts) }
        // rows_changed is 0: adoption is insert-only into a fresh entrance; merge is a later slice.
        events.publishEvent(
            ImportCommitted(record.entranceId, importId, importId, committedBy, units.size, 0, units),
        )
        return CommitResult(importId, units.size, 0)
    }

    /**
     * Revert a committed import (STAGE1-ADDENDUM §1): the registry drops every unit stamped with
     * the import id. Only a COMMITTED import can be reverted, and a reason is required — a reverted
     * legal record says why.
     */
    @Transactional
    fun revert(importId: UUID, revertedBy: UUID, reason: String): ImportRow {
        require(reason.isNotBlank()) { "a revert reason is required" }
        val record = imports.findById(importId).orElseThrow { NoSuchElementException("no import $importId") }
        if (record.status != "COMMITTED") {
            throw ImportStateException("import $importId is ${record.status}; only a COMMITTED import can be reverted")
        }
        val reverted = record.copy(status = "REVERTED")
        aggregates.update(reverted)
        events.publishEvent(ImportReverted(record.entranceId, importId, revertedBy, clock.instant(), reason))
        return reverted
    }

    /** Content-address the source — what the firm actually gave us (STAGE1-ADDENDUM §1, step 1). */
    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
