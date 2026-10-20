package zues.app.identity_org

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Role
import java.time.LocalDate
import java.util.UUID

/** A party who took another's place, and the mandate it holds on the day asked about. */
data class Successor(val partyId: UUID, val role: Role, val mandateId: UUID, val since: LocalDate)

/**
 * Who succeeded a party in an office of an entrance, as at a date — `identity_org`'s published answer, so that another
 * module asks it instead of reading succession its own way (Rule: PM-GOV-004). It is [Mandate.succeeds], the one
 * reading the recording side and the reading side already share, followed through the record: a successor's own
 * successor has taken the place too, so the office is followed to whoever holds it on the day.
 *
 * The answer is every party holding, on [on], a mandate that follows one of [predecessor]'s mandates in [office] — a
 * manager's place is taken by a later manager or board; a board member's by a later manager, or a member elected on or
 * after the day that member's term ended. Each with the role it holds, which may not be [office]: a board may follow
 * a manager. Empty while the predecessor still holds the office, or nobody on record has followed them.
 */
@Service
class Succession(private val mandates: MandateStore) {

    // Rule: PM-GOV-004
    @Transactional(readOnly = true)
    fun successorsOf(predecessor: UUID, entranceId: UUID, office: Role, on: LocalDate): List<Successor> {
        val all = mandates.ofEntrance(entranceId)
        var reached = all.filter { it.partyId == predecessor && it.role == office && !it.validFrom.isAfter(on) }.toSet()
        val followers = mutableSetOf<Mandate>()
        while (true) {
            val next = all.filter { later -> later !in followers && later.partyId != predecessor && reached.any { later.succeeds(it) } }
            if (next.isEmpty()) break
            followers += next
            reached = next.toSet()
        }
        return followers.filter { it.inForceOn(on, all) && it.partyId != null && it.role != null }
            .sortedWith(compareBy({ it.validFrom }, { it.id }))
            .map { Successor(it.partyId!!, it.role!!, it.id, it.validFrom) }
    }

    /** Whether [candidate] has, as at [on], taken [predecessor]'s place in [office] — the same question, asked of one party. */
    // Rule: PM-GOV-004
    fun hasSucceeded(candidate: UUID, predecessor: UUID, entranceId: UUID, office: Role, on: LocalDate): Boolean =
        successorsOf(predecessor, entranceId, office, on).any { it.partyId == candidate }
}
