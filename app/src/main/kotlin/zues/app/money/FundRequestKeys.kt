package zues.app.money

import org.springframework.dao.DuplicateKeyException
import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component
import java.util.UUID

/** The `Idempotency-Key` a fund write came with (DEVBRIEF §8): the operation, a hash of the request, the record it made. Insert-only. */
@Table("fund_request_key")
data class FundRequestKeyRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val idempotencyKey: String,
    val operation: String,                  // FundOperation
    val requestHash: String,
    val resultId: UUID,
)

enum class FundOperation { COMMIT, PAY, CANCEL, HANDOVER }

interface FundRequestKeyRepository : Repository<FundRequestKeyRow, UUID> {
    fun findByEntranceIdAndIdempotencyKey(entranceId: UUID, idempotencyKey: String): FundRequestKeyRow?
}

/**
 * Makes a retried fund write safe (DEVBRIEF §8): the same key with the same request names the record the first
 * request made, so the caller answers from it and writes nothing; the same key for a different request, or a
 * different operation, is an [IdempotencyKeyReused] — a 409. Called inside the write's own transaction, so the
 * key is kept only if the write is.
 */
@Component
class FundRequestKeys(private val keys: FundRequestKeyRepository, private val aggregates: JdbcAggregateTemplate) {

    /** The id of the record an earlier request with this key made, or null when the key is new. */
    fun prior(entranceId: UUID, key: String, operation: FundOperation, request: Map<String, Any?>): UUID? {
        require(key.isNotBlank()) { "an Idempotency-Key is required" }
        val kept = keys.findByEntranceIdAndIdempotencyKey(entranceId, key) ?: return null
        if (kept.operation != operation.name || kept.requestHash != hash(request)) {
            throw IdempotencyKeyReused("Idempotency-Key $key was used for a different request")
        }
        return kept.resultId
    }

    /** A key taken by a request racing this one is refused like any reused key, and this write is rolled back. */
    fun keep(entranceId: UUID, key: String, operation: FundOperation, request: Map<String, Any?>, resultId: UUID) {
        try {
            aggregates.insert(FundRequestKeyRow(UUID.randomUUID(), entranceId, key, operation.name, hash(request), resultId))
        } catch (e: DuplicateKeyException) {
            throw IdempotencyKeyReused("Idempotency-Key $key was used by another request at the same time")
        }
    }

    /** Canonical JSON — named fields, keys sorted, text quoted, null apart from "null" — so two requests hash alike only when they are alike. */
    private fun hash(request: Map<String, Any?>): String = BasisJson.hash(BasisJson.canonical(request.toSortedMap()))
}
