package zues.app.identity_org

import zues.app.policy.Asking
import java.util.UUID

/**
 * The names beside parties' ids, for a read that already serves the ids (#249 N1): a name is the book's (чл. 7 keeps
 * the owners' names), so it goes only to whoever may read the book — the manager, the board and the controller
 * (Rule: PM-BOOK-006) — and each serving is entered in the book's access log under [purpose] (Rule: PM-BOOK-007).
 * The answer is the name only, never an identity number or an address (Rule: PM-BOOK-011); an id the registry does
 * not know is absent from it. `null` is names withheld: the reader keeps the ids, and nothing is entered.
 *
 * Declared here, answered by the registry, which keeps the names: this module cannot depend on it (ADR-003).
 */
fun interface PartyNames {
    fun named(entranceId: UUID, who: Asking, ids: Collection<UUID>, purpose: String): Map<UUID, String>?
}
