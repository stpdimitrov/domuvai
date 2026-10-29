package zues.app.registry

import org.springframework.stereotype.Service
import zues.app.identity_org.PartyNames
import zues.app.policy.Asking
import java.util.UUID

/**
 * The names [PartyNames] asks for, from the parties the book records: to the book's readers only and on its record
 * (Rule: PM-BOOK-006, PM-BOOK-007) — the decision is the book's own, [zues.app.policy.Action.READ_BOOK], never one
 * of this adapter's. The name only (Rule: PM-BOOK-011).
 */
@Service
class PartyNamesAdapter(private val access: BookAccessService) : PartyNames {
    // Rule: PM-BOOK-006
    override fun named(entranceId: UUID, who: Asking, ids: Collection<UUID>, purpose: String): Map<UUID, String>? =
        access.names(entranceId, who, ids, purpose)
}
