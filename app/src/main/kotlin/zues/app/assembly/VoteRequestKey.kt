package zues.app.assembly

import org.springframework.dao.DuplicateKeyException
import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component
import zues.kernel.IdempotencyKey
import zues.kernel.KeptKey
import zues.kernel.KeyDecision
import zues.kernel.decideKey
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The `Idempotency-Key` a vote was recorded with (DEVBRIEF §8): the operation, a hash of the request, when. The votes it made name it. Insert-only. Rule: PM-SYS-014 */
@Table("vote_request_key")
data class VoteRequestKeyRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val idempotencyKey: String,
    val operation: String,
    val requestHash: String,
    val keptAt: Instant,
)

interface VoteRequestKeyRepository : Repository<VoteRequestKeyRow, UUID> {
    fun findByEntranceIdAndIdempotencyKey(entranceId: UUID, idempotencyKey: String): VoteRequestKeyRow?
}

/** The same `Idempotency-Key` for another request, or taken by a racing one: a 409, never a second vote. Rule: PM-SYS-014 */
class IdempotencyKeyReused(message: String) : IllegalStateException(message)

/**
 * A keyed vote write (Rule: PM-SYS-014): the kernel's [decideKey] on what this entrance kept for the key, inside the
 * vote's own transaction, so the key is kept only if the votes are.
 */
@Component
class VoteRequestKeys(private val keys: VoteRequestKeyRepository, private val aggregates: JdbcAggregateTemplate, private val clock: Clock) {

    // Rule: PM-SYS-014
    /** Apply, Replay (naming the kept key's id, which the votes it made carry) or Reused — found by entrance and key together. Rule: PM-SYS-014 */
    fun decide(entranceId: UUID, key: IdempotencyKey, requestHash: String): KeyDecision =
        decideKey(keys.findByEntranceIdAndIdempotencyKey(entranceId, key.value)?.let { KeptKey(it.operation, it.requestHash, it.id.toString()) }, OPERATION, requestHash)

    // Rule: PM-SYS-014
    /** Keeps the key and returns the id its votes name; a key a racing request took first is refused, and this write rolled back. Rule: PM-SYS-014 */
    fun keep(entranceId: UUID, key: IdempotencyKey, requestHash: String): UUID {
        val row = VoteRequestKeyRow(UUID.randomUUID(), entranceId, key.value, OPERATION, requestHash, clock.instant().truncatedTo(ChronoUnit.MICROS))
        try {
            aggregates.insert(row)
        } catch (e: DuplicateKeyException) {
            throw IdempotencyKeyReused("Idempotency-Key ${key.value} was used by another request at the same time (PM-SYS-014)")
        }
        return row.id
    }

    companion object {
        private const val OPERATION = "RECORD_VOTE"

        // Rule: PM-SYS-014
        /** Each field length-prefixed and a missing one apart from any text, so two requests hash alike only when they are alike. */
        fun hash(vararg fields: Any?): String =
            fields.joinToString("") { it?.toString()?.let { s -> "${s.length}:$s" } ?: "-" }
                .let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { b -> "%02x".format(b) } }
    }
}
