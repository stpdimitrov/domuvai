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
 * Where the paper original is and who keeps it (Rule: PM-DOC-007): the stored file is then a copy of that paper, and
 * says so. Both are words as the entrance keeps them — "the archive in the board's room", "the chair of the board".
 */
data class PaperOriginal(val location: String, val custodian: String)

/**
 * A document to store, as another module hands it over. [entityRef] is what it belongs to, `<type>:<uuid>` — the
 * assembly `assembly:…` a protocol records, say. [supersedes] names the version a re-upload replaces; none for a first.
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
)

/**
 * Published when a document is stored (docs/events/DocumentStored.schema.json). In-process it carries [entranceId], as
 * the other events do; the envelope carries it once externalized. Retention is PM-DOC-005's, not yet set.
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
 * What a document must carry to be stored, before anything is looked up: its kind is one of the statutory kinds, and
 * the fields that kind wants are there. Pure. Returns the kind.
 */
// Rule: PM-DOC-002
// Rule: PM-DOC-007
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
    return kind
}

private val ENTITY_REF = Regex("^[a-z_]+:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val MEDIA_TYPE = Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$")
