package zues.app.registry

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.time.LocalDate
import java.util.UUID

/**
 * Roles from titles (ADR-002): a party is an owner or a user of the units they hold a title in on the date asked
 * about (Rule: PM-ORG-011) — derived from the title, never copied beside it. A login tied to no party holds nothing.
 */
@Service
class TitleRoles(private val titles: TitleRepository) : RoleSource {

    // Rule: PM-ORG-011
    @Transactional(readOnly = true)
    override fun held(who: Asking, entranceId: UUID, asAt: LocalDate): Set<Held> {
        val party = who.party ?: return emptySet()
        return titles.findByEntranceId(entranceId)
            .filter { it.partyId == party && it.entranceId == entranceId && it.validFrom <= asAt && (it.validTo == null || asAt < it.validTo) }
            .groupBy { it.titleRole }
            .mapNotNullTo(mutableSetOf()) { (role, held) -> ROLES[role]?.let { Held(it, entranceId, held.mapTo(mutableSetOf()) { title -> title.unitId }) } }
    }

    private companion object {
        val ROLES = mapOf(TitleRole.OWN.name to Role.OWN, TitleRole.USR.name to Role.USR)
    }
}
