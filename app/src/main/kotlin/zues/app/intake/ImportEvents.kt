package zues.app.intake

import java.time.Instant
import java.util.UUID

/**
 * One unit a commit adopts into the registry — the subset of a fee-sheet row the book needs to
 * create the unit: its designation and ideal parts. Occupancy, area and unit type are deliberately
 * absent: a fee sheet does not carry them, and the real pilot spreadsheet defines what does
 * (STAGE1-ADDENDUM §1). Ideal parts is an exact decimal percent string, never a float (ADR-006).
 */
data class AdoptedUnit(val designation: String, val idealParts: String)

/**
 * Raised when a REPRODUCED import is committed (STAGE1-ADDENDUM §1, step 6). Producer: intake; the
 * registry consumes it (registry.ImportAdoption). In-process delivery carries [units] so the
 * registry can adopt them; the externalized contract (docs/events/ImportCommitted.schema.json) is
 * the counts summary and omits them, projected when externalization is wired. [committedBy] is the
 * acting party — a commit writes the legal record, so it is never anonymous. [sourceDocumentId] is
 * the content-addressed source; until the evidence module exists it is the import id itself.
 */
data class ImportCommitted(
    val entranceId: UUID,
    val importId: UUID,
    val sourceDocumentId: UUID,
    val committedBy: UUID,
    val rowsCreated: Int,
    val rowsChanged: Int,
    val units: List<AdoptedUnit>,
)

/** Raised when a committed import is reverted; the registry drops every row that carried its id. */
data class ImportReverted(
    val entranceId: UUID,
    val importId: UUID,
    val revertedBy: UUID,
    val revertedAt: Instant,
    val reason: String,
)

/** A commit or revert asked for on an import whose status does not allow it — a 409, not a 400. */
class ImportStateException(message: String) : RuntimeException(message)
