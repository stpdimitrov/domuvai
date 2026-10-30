package zues.app.assembly

import zues.app.identity_org.PartyNames
import zues.app.policy.Asking
import java.util.UUID

/** What each served read enters in the book's access log (Rule: PM-BOOK-007). */
object NamedRead {
    const val REGISTER = "the assembly's register, named"
    const val PROXIES = "the assembly's proxies, named"
    const val VOTES = "the item's votes, named"
}

/**
 * The names beside the ids a read already serves (#249 N1, #300 Q3): one ask of the book's port for the whole read —
 * never one a row — and none where the read serves no id. The decision is the book's own, READ_BOOK, made by the port
 * (Rule: PM-BOOK-006); `null` is names withheld, and nothing is entered (Rule: PM-BOOK-007).
 */
// Rule: PM-BOOK-006
// Rule: PM-BOOK-007
internal fun PartyNames.beside(entranceId: UUID, who: Asking, ids: Collection<UUID>, purpose: String): Map<UUID, String>? =
    if (ids.isEmpty()) null else named(entranceId, who, ids.toSet(), purpose)

/** The names of the ids one row carries (Rule: PM-BOOK-011 — the name only), or none where names are withheld. */
// Rule: PM-BOOK-011
// Rule: PM-SEC-002
internal fun Map<UUID, String>?.of(vararg ids: UUID): Map<UUID, String>? = this?.filterKeys { it in ids }
