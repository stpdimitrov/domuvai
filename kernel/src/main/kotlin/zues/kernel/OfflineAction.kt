package zues.kernel

/** The key a queued action carries, so the server applies it once however often it is sent. Rule: PM-SYS-014 */
@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        require(value.isNotBlank()) { "an action queued offline carries an idempotency key (PM-SYS-014)" }
    }
}

/** What the screen says of a queued action until the server answers. Rule: PM-SYS-014 */
const val QUEUED_LABEL_BG: String = "изпраща се"

/** Rule: PM-SYS-014 */
enum class OfflineStatus { QUEUED, CONFIRMED, REJECTED }

/**
 * An action taken on a device, possibly offline. It is QUEUED until the server answers; only the server's
 * confirmation makes it performed. Rule: PM-SYS-014
 */
data class OfflineAction(
    val key: IdempotencyKey,
    val operation: String,
    val status: OfflineStatus = OfflineStatus.QUEUED,
    val resultId: String? = null,
) {
    /** Performed only once the server has confirmed it — never while queued. Rule: PM-SYS-014 */
    val performed: Boolean get() = status == OfflineStatus.CONFIRMED

    /** The label while it waits; a confirmed or rejected action is labelled by its own screen. Rule: PM-SYS-014 */
    val pendingLabel: String? get() = if (status == OfflineStatus.QUEUED) QUEUED_LABEL_BG else null

    /** The server applied it (first time or a replay) and names the record. Rule: PM-SYS-014 */
    fun confirmed(resultId: String): OfflineAction {
        check(status == OfflineStatus.QUEUED) { "only a queued action is confirmed, this one is $status (PM-SYS-014)" }
        require(resultId.isNotBlank()) { "a confirmation names the record the server made (PM-SYS-014)" }
        return copy(status = OfflineStatus.CONFIRMED, resultId = resultId)
    }

    /** The server refused it. Rule: PM-SYS-014 */
    fun rejected(): OfflineAction {
        check(status == OfflineStatus.QUEUED) { "only a queued action is rejected, this one is $status (PM-SYS-014)" }
        return copy(status = OfflineStatus.REJECTED)
    }
}

/** A key the server already kept: the operation, the hash of the request it came with, the record it made. Rule: PM-SYS-014 */
data class KeptKey(val operation: String, val requestHash: String, val resultId: String)

/** Rule: PM-SYS-014 */
sealed interface KeyDecision {
    /** A new key: apply the action and keep the key with the write. */
    data object Apply : KeyDecision

    /** The same request again: write nothing, answer with the record the first one made. */
    data class Replay(val resultId: String) : KeyDecision

    /** The same key for another request or operation: refuse it. */
    data object Reused : KeyDecision
}

/**
 * The server's decision for a keyed write, given what it kept for that key. A replayed queue meets
 * [KeyDecision.Replay] for every action already applied, so it applies each action once. A key is unique only within
 * its scope: the caller finds [kept] by the entrance and the key together, never by the key alone. Rule: PM-SYS-014
 */
fun decideKey(kept: KeptKey?, operation: String, requestHash: String): KeyDecision = when {
    kept == null -> KeyDecision.Apply
    kept.operation == operation && kept.requestHash == requestHash -> KeyDecision.Replay(kept.resultId)
    else -> KeyDecision.Reused
}
