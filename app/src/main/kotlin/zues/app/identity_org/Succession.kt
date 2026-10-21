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
 * after the day that member's term ended; a controller's by a controller elected on or after the day that one's term
 * ended (PM-GOV-007). Each with the role it holds, which may not be [office]: a board may follow
 * a manager. Empty while the predecessor still holds [office] that day, or nobody on record has followed them. A party
 * is never their own successor, though the place may pass through their own later mandate.
 *
 * It answers who *succeeded*; whether a successor may carry on an act of the predecessor — and whether they must hold
 * the very same office — is the asking module's rule, not this one's.
 */
@Service
class Succession(private val mandates: MandateStore) {

    // Rule: PM-GOV-004
    // Rule: PM-GOV-007
    @Transactional(readOnly = true)
    fun successorsOf(predecessor: UUID, entranceId: UUID, office: Role, on: LocalDate): List<Successor> {
        val all = mandates.ofEntrance(entranceId)
        val held = all.filter { it.partyId == predecessor && it.role == office && !it.validFrom.isAfter(on) }
        // While the predecessor holds the office that day, nobody has taken their place — whatever else is on record.
        if (held.any { it.inForceOn(on, all) }) return emptyList()
        var reached = held.toSet()
        val followers = mutableSetOf<Mandate>()
        while (true) {
            // The walk goes through every mandate that follows — the predecessor's own later ones, a firm's, one that
            // names nobody — since the place passes through them; who is answered is filtered below.
            val next = all.filter { later -> later !in followers && reached.any { later.succeeds(it) } }
            if (next.isEmpty()) break
            followers += next
            reached = next.toSet()
        }
        return followers.filter { it.inForceOn(on, all) && it.partyId != null && it.partyId != predecessor && it.role != null }
            .sortedWith(compareBy({ it.validFrom }, { it.id }))
            .map { Successor(it.partyId!!, it.role!!, it.id, it.validFrom) }
    }

    /** Whether [candidate] has, as at [on], taken [predecessor]'s place in [office] — the same question, asked of one party. */
    // Rule: PM-GOV-004
    @Transactional(readOnly = true)
    fun hasSucceeded(candidate: UUID, predecessor: UUID, entranceId: UUID, office: Role, on: LocalDate): Boolean =
        successorsOf(predecessor, entranceId, office, on).any { it.partyId == candidate }
}
