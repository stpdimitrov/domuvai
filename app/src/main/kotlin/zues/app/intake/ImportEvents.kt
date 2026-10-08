package zues.app.intake

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One unit a commit adopts into the registry: its designation and ideal parts (ADR-006: an exact
 * decimal percent string, never a float), plus what the sheet mapped about its area, household and
 * owner (S-41b, ADR-012), and whether the sheet marks it as used for business (#91). Unit type is still
 * absent — no sheet field carries it. What a count cannot make lawful — an absence, an animal — never
 * travels here; the commit returns it for a person to record from a declaration.
 */
data class AdoptedUnit(
    val designation: String,
    val idealParts: String,
    /** exact decimal m², as the sheet wrote it (PM-BOOK-002); null when the sheet has none */
    val builtArea: String?,
    /** the persons the firm charged — the dry-run counts them so (PM-FEE-008) */
    val occupants: Int,
    /** residents on top of [occupants], never charged (PM-FEE-005) */
    val childrenUnder6: Int,
    /** the owner's name as written — never an identity number (PM-BOOK-011) */
    val ownerName: String?,
    /** the sheet marks the unit as used for business or professional activity (PM-FEE-010); a sheet says nothing of a separate entrance */
    val businessUse: Boolean = false,
)

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
    /** the import's legal date: adopted household members and owners' titles are valid from it (PM-ORG-011) */
    val effectiveFrom: LocalDate,
)

/** Raised when a committed import is reverted; the registry drops every row that carried its id. */
data class ImportReverted(
    val entranceId: UUID,
    val importId: UUID,
    val revertedBy: UUID,
    val revertedAt: Instant,
    val reason: String,
)

/**
 * The registry's answer to [ImportCommitted]: the import's rows are adopted. Defined here and published by the
 * registry, so the dependency still runs registry → intake only. In-process; not externalized.
 */
data class ImportCommitApplied(val entranceId: UUID, val importId: UUID)

/** The registry's other answer: it could not adopt the import's rows, and adopted none. [blockedBy] says why. */
data class ImportCommitBlocked(val entranceId: UUID, val importId: UUID, val blockedBy: String)

/**
 * The registry's answer to [ImportReverted]: every row that carried the import's id is gone. Defined here and
 * published by the registry, so the dependency still runs registry → intake only. In-process; not externalized.
 */
data class ImportRevertApplied(val entranceId: UUID, val importId: UUID)

/**
 * The registry's other answer: it could not remove the import's rows, and nothing was removed. [blockedBy] names
 * what still points at them, as the database refused it — a record added after the commit is not the import's to drop.
 */
data class ImportRevertBlocked(val entranceId: UUID, val importId: UUID, val blockedBy: String)

/** A commit or revert asked for on an import whose status does not allow it — a 409, not a 400. */
class ImportStateException(message: String) : RuntimeException(message)
