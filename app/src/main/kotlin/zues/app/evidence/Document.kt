package zues.app.evidence

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The kinds of statutory document (Rule: PM-DOC-002): the six the rule names, and no other. A document of no kind is
 * not stored — there is deliberately no "other".
 */
enum class DocumentKind { PROTOCOL, NOTICE, POSTING_ACT, CONSTATIVE_PROTOCOL, FILING, CERTIFICATE }

/**
 * A breach recorded in a constative protocol (Rule: PM-DOC-003, PM-DOC-004): who, what, when, where. Required when
 * kind = CONSTATIVE_PROTOCOL.
 */
data class BreachRecord(
    val partyId: UUID,
    val description: String,
    val occurredAt: Instant,
    val location: String,
)

/**
 * Photographic evidence metadata (Rule: PM-DOC-009): when it was captured, and the hash of the display copy. The
 * original bytes are unmodified; the display copy is resized for the screen.
 */
data class PhotoMetadata(
    val capturedAt: Instant,
    val displayCopyHash: String?,
)

/**
 * Where the paper original is and who keeps it (Rule: PM-DOC-007): the stored file is then a copy of that paper, and
 * says so. Both are words as the entrance keeps them — "the archive in the board's room", "the chair of the board".
 */
data class PaperOriginal(val location: String, val custodian: String)

/**
 * A document to store, as another module hands it over. [entityRef] is what it belongs to, `<type>:<uuid>` — the
 * assembly `assembly:…` a protocol records, say. [supersedes] names the version a re-upload replaces; none for a first.
 * [retentionUntil] is PM-DOC-005's date, if set. [signatureValidationReport] is PM-DOC-008's evidence. [photoMetadata]
 * is PM-DOC-009's capture time and display copy. [breach] is PM-DOC-003/004's required fields for a constative protocol.
 */
class StoreDocument(
    val entranceId: UUID,
    val entityRef: String,
    val kind: String?,
    val mediaType: String,
    val content: ByteArray,
    val authorPartyId: UUID,
    val paperOriginal: PaperOriginal? = null,
    val supersedes: UUID? = null,
    val retentionUntil: LocalDate? = null,
    val signatureValidationReport: String? = null,
    val photoMetadata: PhotoMetadata? = null,
    val breach: BreachRecord? = null,
)

/** A stored version of a document: never changed. Its bytes are read by [contentHash] through [Documents.content]. */
data class StoredDocument(
    val id: UUID,
    val entranceId: UUID,
    val entityRef: String,
    val kind: DocumentKind,
    val contentHash: String,
    val mediaType: String,
    val authorPartyId: UUID,
    val storedAt: Instant,
    /** the first version's id, shared by every version of the document */
    val documentKey: UUID,
    val versionNo: Int,
    val supersedes: UUID?,
    val paperOriginal: PaperOriginal?,
    val retentionUntil: LocalDate?,
    val signatureValidationReport: String?,
    val photoMetadata: PhotoMetadata?,
    val breach: BreachRecord?,
)

/**
 * Published when a document is stored (docs/events/DocumentStored.schema.json). In-process it carries [entranceId], as
 * the other events do; the envelope carries it once externalized.
 */
data class DocumentStored(
    val entranceId: UUID,
    val documentId: UUID,
    val kind: String,
    val contentHash: String,
    val storedAt: Instant,
    val retentionUntil: LocalDate? = null,
)

/**
 * A deletion log entry (Rule: PM-DOC-005): immutable record of a document's deletion. Written by the background
 * deletion job; the document row and bytes are already gone.
 */
data class DeletionLog(
    val id: UUID,
    val documentId: UUID,
    val entranceId: UUID,
    val entityRef: String,
    val kind: String,
    val contentHash: String,
    val deletedAt: Instant,
    val deletedBy: UUID,
    val reason: String,
)

/**
 * What a document must carry to be stored, before anything is looked up: its kind is one of the statutory kinds, and
 * the fields that kind wants are there. Pure. Returns the kind.
 */
// Rule: PM-DOC-002
// Rule: PM-DOC-003
// Rule: PM-DOC-004
// Rule: PM-DOC-007
// Rule: PM-DOC-009
fun admit(request: StoreDocument): DocumentKind {
    val kind = DocumentKind.entries.firstOrNull { it.name == request.kind }
        ?: throw IllegalArgumentException(
            "a statutory document is of one of the kinds ${DocumentKind.entries.joinToString { it.name }} — not \"${request.kind ?: ""}\" (PM-DOC-002)",
        )
    require(ENTITY_REF.matches(request.entityRef)) { "a document names the entity it belongs to as <type>:<uuid> (PM-DOC-001)" }
    require(MEDIA_TYPE.matches(request.mediaType)) { "a document says what it is, as a media type such as application/pdf" }
    require(request.content.isNotEmpty()) { "a document has content" }
    request.paperOriginal?.let {
        require(it.location.isNotBlank() && it.custodian.isNotBlank()) { "a paper original is recorded with where it is and who keeps it (PM-DOC-007)" }
    }
    if (kind == DocumentKind.PROTOCOL) {
        require(request.paperOriginal != null) { "a protocol is kept on paper: say where the original is and who keeps it (PM-DOC-007)" }
    }
    // Rule: PM-DOC-003, PM-DOC-004
    if (kind == DocumentKind.CONSTATIVE_PROTOCOL) {
        require(request.breach != null) { "a constative protocol records the breach: who, what, when, where (PM-DOC-003, PM-DOC-004)" }
        require(request.breach.description.isNotBlank()) { "a constative protocol records what the breach was (PM-DOC-004)" }
        require(request.breach.location.isNotBlank()) { "a constative protocol records where the breach occurred (PM-DOC-004)" }
    }
    // Rule: PM-DOC-009
    request.photoMetadata?.let {
        require(it.displayCopyHash == null || it.displayCopyHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "a photo's display copy hash is a SHA-256 hash"
        }
    }
    return kind
}

private val ENTITY_REF = Regex("^[a-z_]+:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val MEDIA_TYPE = Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$")
