package zues.app.evidence

import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/**
 * The evidence module's published API: other modules store a statutory document here and read it back. Nothing is
 * ever changed: a re-upload is a new version beside the old one (Rule: PM-DOC-001). Who may store or read is the
 * policy's to say once documents are reached over HTTP; inside the application, the calling module has decided.
 */
@Service
class Documents internal constructor(
    private val store: DocumentStore,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {

    // Rule: PM-DOC-001
    // Rule: PM-DOC-002
    // Rule: PM-DOC-007
    @Transactional
    fun store(request: StoreDocument): StoredDocument {
        val kind = admit(request)
        val id = UUID.randomUUID()
        val (key, versionNo) = request.supersedes?.let { previousId ->
            // the version replaced is locked, so two re-uploads of it are taken one after the other
            val previous = store.lock(request.entranceId, previousId)
                ?: throw NoSuchElementException("no document $previousId in entrance ${request.entranceId}")
            require(previous.entityRef == request.entityRef && previous.kind == kind) {
                "a new version is of the same document: ${previous.kind} of ${previous.entityRef} (PM-DOC-001)"
            }
            check(!store.isSuperseded(previousId)) { "document $previousId has a newer version already: a re-upload replaces the latest (PM-DOC-001)" }
            previous.documentKey to previous.versionNo + 1
        } ?: (id to 1)
        val hash = sha256(request.content)
        val document = StoredDocument(
            id, request.entranceId, request.entityRef, kind, hash, request.mediaType, request.authorPartyId, clock.instant().truncatedTo(ChronoUnit.MICROS),          // as the column keeps it
            key, versionNo, request.supersedes, request.paperOriginal,
            request.retentionUntil, request.signatureValidationReport, request.photoMetadata, request.breach,
        )
        try {
            store.keepBytes(hash, request.content)
            // Rule: PM-DOC-009 — for photos with a display copy, store that too (caller must have computed it)
            request.photoMetadata?.displayCopyHash?.let { displayHash ->
                // The display copy bytes must be provided separately; this slice stores the schema only
                // A later slice will add a displayCopyContent parameter when photo capture is implemented
            }
            store.add(document)
        } catch (e: DataIntegrityViolationException) {
            if (e.mostSpecificCause.message.orEmpty().contains("foreign key")) throw IllegalArgumentException("the entrance or the author is not registered", e)
            throw e
        }
        events.publishEvent(DocumentStored(document.entranceId, document.id, kind.name, hash, document.storedAt, document.retentionUntil))
        return document
    }

    @Transactional(readOnly = true)
    fun find(entranceId: UUID, documentId: UUID): StoredDocument? = store.find(entranceId, documentId)

    /** Every version of the document [documentId] is a version of, first to latest. Empty when the entrance has no such document. */
    @Transactional(readOnly = true)
    fun versions(entranceId: UUID, documentId: UUID): List<StoredDocument> =
        store.find(entranceId, documentId)?.let { store.versions(entranceId, it.documentKey) } ?: emptyList()

    /** The bytes as stored — never a re-encoded copy. Null when the entrance has no such document. */
    @Transactional(readOnly = true)
    fun content(entranceId: UUID, documentId: UUID): ByteArray? =
        store.find(entranceId, documentId)?.let { store.bytes(it.contentHash) }

    // Rule: PM-DOC-005
    /**
     * Mark a document for deletion at [retentionUntil]. The actual deletion is done by a background job (not in this
     * slice) that writes [DeletionLog] and removes the row. Setting retention_until = null clears the mark.
     */
    @Transactional
    fun markForDeletion(entranceId: UUID, documentId: UUID, retentionUntil: java.time.LocalDate?) {
        store.setRetention(entranceId, documentId, retentionUntil)
    }

    // Rule: PM-DOC-006
    /**
     * Documents for a case file export: every document of [matterEntityRef] in [entranceId], ordered by stored_at.
     * Returns metadata for bundle generation (PDF/ZIP generation is a later slice). [matter] names the case type
     * (e.g., "arrears", "expulsion") for logging.
     */
    @Transactional(readOnly = true)
    fun exportCaseFile(entranceId: UUID, matter: String, matterEntityRef: String): List<StoredDocument> =
        store.documentsForEntity(entranceId, matterEntityRef)
}

/** This module's own tables, written once and read. What a document must satisfy is [admit]'s and [Documents]'. */
@Repository
internal class DocumentStore(private val jdbc: JdbcClient) {

    /** The bytes, once per content: a file stored twice is one row. */
    fun keepBytes(hash: String, bytes: ByteArray) {
        jdbc.sql("INSERT INTO evidence.blob (content_hash, bytes) VALUES (?, ?) ON CONFLICT (content_hash) DO NOTHING").params(hash, bytes).update()
    }

    fun add(d: StoredDocument) {
        jdbc.sql(
            "INSERT INTO evidence.document (id, entrance_id, entity_ref, kind, content_hash, media_type, author_party_id, stored_at, " +
                "document_key, version_no, supersedes, paper_location, paper_custodian, retention_until, signature_validation_report, " +
                "captured_at, display_copy_hash, breach_party_id, breach_description, breach_occurred_at, breach_location) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)",
        ).params(
            d.id, d.entranceId, d.entityRef, d.kind.name, d.contentHash, d.mediaType, d.authorPartyId, Timestamp.from(d.storedAt),
            d.documentKey, d.versionNo, d.supersedes, d.paperOriginal?.location, d.paperOriginal?.custodian,
            d.retentionUntil?.let { java.sql.Date.valueOf(it) }, d.signatureValidationReport,
            d.photoMetadata?.capturedAt?.let { Timestamp.from(it) }, d.photoMetadata?.displayCopyHash,
            d.breach?.partyId, d.breach?.description, d.breach?.occurredAt?.let { Timestamp.from(it) }, d.breach?.location,
        ).update()
    }

    fun find(entranceId: UUID, id: UUID): StoredDocument? =
        jdbc.sql("SELECT $COLUMNS FROM evidence.document WHERE id = ? AND entrance_id = ?").params(id, entranceId).query { rs, _ -> row(rs) }.optional().orElse(null)

    fun lock(entranceId: UUID, id: UUID): StoredDocument? =
        jdbc.sql("SELECT $COLUMNS FROM evidence.document WHERE id = ? AND entrance_id = ? FOR UPDATE").params(id, entranceId).query { rs, _ -> row(rs) }.optional().orElse(null)

    fun isSuperseded(id: UUID): Boolean =
        jdbc.sql("SELECT count(*) FROM evidence.document WHERE supersedes = ?").param(id).query(Long::class.java).single() > 0

    fun versions(entranceId: UUID, documentKey: UUID): List<StoredDocument> =
        jdbc.sql("SELECT $COLUMNS FROM evidence.document WHERE document_key = ? AND entrance_id = ? ORDER BY version_no").params(documentKey, entranceId).query { rs, _ -> row(rs) }.list()

    fun bytes(hash: String): ByteArray? =
        jdbc.sql("SELECT bytes FROM evidence.blob WHERE content_hash = ?").param(hash).query { rs, _ -> rs.getBytes("bytes") }.optional().orElse(null)

    fun setRetention(entranceId: UUID, id: UUID, retentionUntil: java.time.LocalDate?) {
        jdbc.sql("UPDATE evidence.document SET retention_until = ? WHERE id = ? AND entrance_id = ?")
            .params(retentionUntil?.let { java.sql.Date.valueOf(it) }, id, entranceId)
            .update()
    }

    fun documentsForEntity(entranceId: UUID, entityRef: String): List<StoredDocument> =
        jdbc.sql("SELECT $COLUMNS FROM evidence.document WHERE entrance_id = ? AND entity_ref = ? ORDER BY stored_at")
            .params(entranceId, entityRef)
            .query { rs, _ -> row(rs) }
            .list()

    private fun row(rs: ResultSet) = StoredDocument(
        rs.getObject("id", UUID::class.java), rs.getObject("entrance_id", UUID::class.java), rs.getString("entity_ref"),
        DocumentKind.valueOf(rs.getString("kind")), rs.getString("content_hash"), rs.getString("media_type"),
        rs.getObject("author_party_id", UUID::class.java), rs.getTimestamp("stored_at").toInstant(),
        rs.getObject("document_key", UUID::class.java), rs.getInt("version_no"), rs.getObject("supersedes", UUID::class.java),
        rs.getString("paper_location")?.let { PaperOriginal(it, rs.getString("paper_custodian")) },
        rs.getDate("retention_until")?.toLocalDate(),
        rs.getString("signature_validation_report"),
        rs.getTimestamp("captured_at")?.toInstant()?.let { capturedAt ->
            PhotoMetadata(capturedAt, rs.getString("display_copy_hash"))
        },
        rs.getObject("breach_party_id", UUID::class.java)?.let { partyId ->
            BreachRecord(
                partyId,
                rs.getString("breach_description"),
                rs.getTimestamp("breach_occurred_at").toInstant(),
                rs.getString("breach_location"),
            )
        },
    )

    private companion object {
        const val COLUMNS = "id, entrance_id, entity_ref, kind, content_hash, media_type, author_party_id, stored_at, " +
            "document_key, version_no, supersedes, paper_location, paper_custodian, retention_until, signature_validation_report::text, " +
            "captured_at, display_copy_hash, breach_party_id, breach_description, breach_occurred_at, breach_location"
    }
}

private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
