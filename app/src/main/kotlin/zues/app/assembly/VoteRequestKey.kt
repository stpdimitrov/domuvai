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

/** The `Idempotency-Key` a vote, an absentee ballot or a proxy was recorded with (DEVBRIEF §8): the operation, a hash of the request, when. The rows it made name it. Insert-only. Rule: PM-SYS-014 */
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

/** The same `Idempotency-Key` for another request, or taken by a racing one: a 409, never a second write. Rule: PM-SYS-014 */
class IdempotencyKeyReused(message: String) : IllegalStateException(message)

/**
 * A keyed write (Rule: PM-SYS-014): the kernel's [decideKey] on what this entrance kept for the key, inside the write's
 * own transaction, so the key is kept only if its rows are. One key space per entrance, so a key reused for another
 * [operation] is refused, not applied twice.
 */
@Component
class VoteRequestKeys(private val keys: VoteRequestKeyRepository, private val aggregates: JdbcAggregateTemplate, private val clock: Clock) {

    // Rule: PM-SYS-014
    /** Apply, Replay (naming the kept key's id, which the rows it made carry) or Reused — found by entrance and key together. Rule: PM-SYS-014 */
    fun decide(entranceId: UUID, key: IdempotencyKey, operation: String, requestHash: String): KeyDecision =
        decideKey(keys.findByEntranceIdAndIdempotencyKey(entranceId, key.value)?.let { KeptKey(it.operation, it.requestHash, it.id.toString()) }, operation, requestHash)

    // Rule: PM-SYS-014
    /** Keeps the key and returns the id its rows name; a key a racing request took first is refused, and this write rolled back. Rule: PM-SYS-014 */
    fun keep(entranceId: UUID, key: IdempotencyKey, operation: String, requestHash: String): UUID {
        val row = VoteRequestKeyRow(UUID.randomUUID(), entranceId, key.value, operation, requestHash, clock.instant().truncatedTo(ChronoUnit.MICROS))
        try {
            aggregates.insert(row)
        } catch (e: DuplicateKeyException) {
            throw IdempotencyKeyReused("Idempotency-Key ${key.value} was used by another request at the same time (PM-SYS-014)")
        }
        return row.id
    }

    companion object {
        const val RECORD_VOTE = "RECORD_VOTE"
        const val ENTER_ABSENTEE_BALLOT = "ENTER_ABSENTEE_BALLOT"
        const val REGISTER_PROXY = "REGISTER_PROXY"

        // Rule: PM-SYS-014
        /** Each field length-prefixed and a missing one apart from any text, so two requests hash alike only when they are alike. */
        fun hash(vararg fields: Any?): String =
            fields.joinToString("") { it?.toString()?.let { s -> "${s.length}:$s" } ?: "-" }
                .let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { b -> "%02x".format(b) } }
    }
}
