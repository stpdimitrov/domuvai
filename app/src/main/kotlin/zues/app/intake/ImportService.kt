package zues.app.intake

import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** What recording an import returns: the stored id and the full dry-run report behind the verdict. */
data class ImportResult(val importId: UUID, val report: DryRunReport)

/**
 * A value the sheet carried that a commit must not adopt, because the law requires a declaration a
 * count is not: a person records it from the declaration. [rule] names why.
 */
data class ManualEntry(val designation: String, val field: IntakeField, val value: String, val rule: String)

/**
 * What committing an import returns: the id, how many unit rows the commit asks the registry to adopt, and what the
 * sheet carried that must be recorded by hand — surfaced, never dropped, never fabricated.
 */
data class CommitResult(
    val importId: UUID,
    val rowsCreated: Int,
    val rowsChanged: Int,
    val manualEntries: List<ManualEntry>,
    /** COMMITTING: the registry adopts the rows after this answer; the import then reads COMMITTED or COMMIT_BLOCKED */
    val status: String = "COMMITTING",
)

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
        val sheet = FeeSheet.parse(request.csv, request.mapping)
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
     * stays unmodelled until the pilot spreadsheet defines it. Only a REPRODUCED import commits —
     * or one whose commit was blocked, asked again. This transaction writes intake's own record and
     * the outbox event; the registry adopts the units in its own transaction, after this answer
     * (MODULE-TEMPLATE laws 1 and 3), so `registry.unit` is never written from here and the import
     * is COMMITTING until the registry reports back ([commitApplied], [commitBlocked]).
     */
    @Transactional
    fun commit(importId: UUID, committedBy: UUID, request: FeeSheetDryRunRequest): CommitResult {
        val record = imports.readById(importId) ?: throw NoSuchElementException("no import $importId")   // locked: two commits at once are one
        if (record.status != "REPRODUCED" && record.status != "COMMIT_BLOCKED") {
            throw ImportStateException("import $importId is ${record.status}; only a REPRODUCED import, or one whose commit was blocked, can be committed")
        }
        if (sha256(request.csv) != record.sourceSha) {
            throw ImportStateException("the submitted sheet does not match the reviewed import (source hash differs)")
        }
        // The hash pins the bytes; the mapping is how they are read. A different mapping that still
        // reproduces to the cent is still a valid commit — the reproduce check below is the guard.
        val sheet = FeeSheet.parse(request.csv, request.mapping)
        val report = IntakeDryRun.of(
            record.entranceId.toString(), request.period, request.legalDate, request.businessMultiplier, request.lines, sheet,
        )
        if (!report.reproduced) {
            // What stopped it is said: figures that differ, or a rule the tariff breaks (e.g. PM-FEE-011) while the figures add up.
            val why = report.violations.ifEmpty { listOf("${report.differing} unit(s) differ") }.joinToString("; ")
            throw ImportStateException("import $importId no longer reproduces the firm's figures ($why); refusing to commit")
        }
        aggregates.update(record.copy(status = "COMMITTING", commitBlockedBy = null))
        val units = sheet.rows.map {
            AdoptedUnit(
                designation = it.designation,
                idealParts = it.idealParts,
                builtArea = it.optional[IntakeField.BUILT_AREA],
                occupants = it.occupants,
                childrenUnder6 = it.optional[IntakeField.CHILDREN_UNDER_6]?.toInt() ?: 0,
                ownerName = it.optional[IntakeField.OWNER_NAME],
                businessUse = it.businessUse,   // Rule: PM-FEE-010 — adopted since the registry holds it as a fact of its own (#91)
            )
        }
        val manual = sheet.rows.flatMap { row ->
            NOT_ADOPTED.mapNotNull { (field, rule) -> row.optional[field]?.let { ManualEntry(row.designation, field, it, rule) } }
        }
        // rows_changed is 0: adoption is insert-only into a fresh entrance; merge is a later slice.
        events.publishEvent(
            ImportCommitted(record.entranceId, importId, importId, committedBy, units.size, 0, units, LocalDate.parse(request.legalDate)),
        )
        return CommitResult(importId, units.size, 0, manual)
    }

    /**
     * Ask for a committed import to be reverted (STAGE1-ADDENDUM §1). The registry drops every row
     * stamped with the import id after this request has answered, so the import is REVERTING until
     * the registry reports back ([revertApplied], [revertBlocked]). Only a COMMITTED import can be
     * reverted — or one whose revert was blocked, asked again — and a reason is required: a reverted
     * legal record says why.
     */
    @Transactional
    fun revert(importId: UUID, revertedBy: UUID, reason: String): ImportRow {
        require(reason.isNotBlank()) { "a revert reason is required" }
        val record = imports.readById(importId) ?: throw NoSuchElementException("no import $importId")   // locked: two reverts at once are one
        if (record.status != "COMMITTED" && record.status != "REVERT_BLOCKED") {
            throw ImportStateException("import $importId is ${record.status}; only a COMMITTED import, or one whose revert was blocked, can be reverted")
        }
        val reverting = record.copy(status = "REVERTING", revertBlockedBy = null)
        aggregates.update(reverting)
        events.publishEvent(ImportReverted(record.entranceId, importId, revertedBy, clock.instant(), reason))
        return reverting
    }

    /** The registry adopted the import's rows — a fact that stays true, so it also settles an import a stale answer left blocked. */
    @Transactional
    fun commitApplied(importId: UUID) = settle(importId, setOf("COMMITTING", "COMMIT_BLOCKED")) { it.copy(status = "COMMITTED", commitBlockedBy = null) }

    /** The registry could not, and adopted nothing: COMMITTING becomes COMMIT_BLOCKED, with why. Any other status is left as it is. */
    @Transactional
    fun commitBlocked(importId: UUID, blockedBy: String) = settle(importId, setOf("COMMITTING")) { it.copy(status = "COMMIT_BLOCKED", commitBlockedBy = blockedBy) }

    /**
     * The registry removed the import's rows — a fact that stays true, so it settles an import that waits for it and
     * also one an earlier answer left REVERT_BLOCKED (delivery is at least once, and answers may arrive out of turn).
     */
    @Transactional
    fun revertApplied(importId: UUID) = settle(importId, setOf("REVERTING", "REVERT_BLOCKED")) { it.copy(status = "REVERTED", revertBlockedBy = null) }

    /** The registry could not: REVERTING becomes REVERT_BLOCKED, with why. Any other status is left as it is. */
    @Transactional
    fun revertBlocked(importId: UUID, blockedBy: String) = settle(importId, setOf("REVERTING")) { it.copy(status = "REVERT_BLOCKED", revertBlockedBy = blockedBy) }

    private fun settle(importId: UUID, from: Set<String>, settled: (ImportRow) -> ImportRow) {
        val record = imports.readById(importId) ?: return
        if (record.status in from) aggregates.update(settled(record))
    }

    private companion object {
        /**
         * What a sheet may carry but a commit must not adopt. An absence exempts only on a filed
         * declaration (PM-FEE-007); an animal is entered from the owner's declaration with its
         * passport data (PM-BOOK-005). Business use was a third until the registry held it as a fact
         * of its own (S-G1-03d); a commit adopts it now.
         */
        val NOT_ADOPTED = listOf(
            IntakeField.ABSENT_DAYS to "PM-FEE-007",
            IntakeField.ANIMALS to "PM-BOOK-005",
        )
    }

    /** Content-address the source — what the firm actually gave us (STAGE1-ADDENDUM §1, step 1). */
    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
