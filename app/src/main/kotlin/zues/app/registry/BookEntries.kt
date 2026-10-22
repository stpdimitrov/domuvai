package zues.app.registry

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The book's entries, as an HTTP caller makes them (Rule: PM-BOOK-005, PM-BOOK-002): an entrance's units, a unit's
 * owners and users (its titles), its household, its animals, its absences, and the day a resident or an animal left —
 * entered by the manager or the board, from the declarant's declaration; an owner or a user changes them by declaring
 * (PM-BOOK-003), never by recording their own title. Decided by the policy as at today in Sofia, on the entrance, before
 * anything is said about the unit, the party or the request: a caller refused learns nothing. With sign-in switched off
 * in so many words nobody is asking, and the entry is made as before. The services behind are untouched: an import's
 * commit adopts units, titles and households in-process — its own HTTP operations ask nobody yet (#218).
 */
@Service
class BookEntries(
    private val registry: RegistryService,
    private val ownership: OwnershipService,
    private val policy: Policy,
    private val clock: Clock,
    @Value("\${domuvai.auth.mode:}") mode: String = "",
) {
    private val open = mode.trim().equals("off", ignoreCase = true)

    // Rule: PM-BOOK-005
    fun units(entranceId: UUID, units: List<RegisterUnit>, who: Asking): List<UUID> =
        entered(entranceId, who) { registry.registerUnits(entranceId, units) }

    // Rule: PM-BOOK-005
    // Rule: PM-BOOK-003
    fun title(entranceId: UUID, unitId: UUID, title: AssignTitle, who: Asking): UUID =
        entered(entranceId, who) { ownership.assignTitle(entranceId, unitId, title) }

    // Rule: PM-BOOK-005
    fun household(entranceId: UUID, unitId: UUID, members: List<RegisterMember>, who: Asking): List<UUID> =
        entered(entranceId, who) { registry.registerHousehold(entranceId, unitId, members) }

    // Rule: PM-BOOK-005
    fun animals(entranceId: UUID, unitId: UUID, animals: List<RegisterAnimal>, who: Asking): List<UUID> =
        entered(entranceId, who) { registry.registerAnimals(entranceId, unitId, animals) }

    // Rule: PM-BOOK-005
    fun absences(entranceId: UUID, unitId: UUID, absences: List<RegisterAbsence>, who: Asking): List<UUID> =
        entered(entranceId, who) { registry.registerAbsence(entranceId, unitId, absences) }

    /** The day is read only once the caller may enter it: a refused caller is refused, whatever they sent. */
    // Rule: PM-BOOK-005
    fun endHouseholdStay(entranceId: UUID, unitId: UUID, memberId: UUID, on: String, who: Asking): StayEnded =
        entered(entranceId, who) { LocalDate.parse(on).let { day -> registry.endHouseholdStay(entranceId, unitId, memberId, day).let { StayEnded(it.id, it.validFrom, day) } } }

    // Rule: PM-BOOK-005
    fun endAnimalStay(entranceId: UUID, unitId: UUID, animalId: UUID, on: String, who: Asking): StayEnded =
        entered(entranceId, who) { LocalDate.parse(on).let { day -> registry.endAnimalStay(entranceId, unitId, animalId, day).let { StayEnded(it.id, it.validFrom, day) } } }

    /**
     * An entrance's units, read by anyone holding a role in it — an owner, a holder of use, the manager, the board, the
     * controller, the cashier (the owner's decision of 2026-10-21, #251) — and decided before the list is read.
     */
    // Rule: PM-SEC-001
    fun listUnits(entranceId: UUID, who: Asking): List<PropertyUnit> = decided(Action.READ_UNITS, entranceId, who) { registry.listUnits(entranceId) }

    private fun <T> entered(entranceId: UUID, who: Asking, entry: () -> T): T = decided(Action.ENTER_BOOK, entranceId, who, entry)

    private fun <T> decided(action: Action, entranceId: UUID, who: Asking, then: () -> T): T {
        if (!(open && who.login == null && who.party == null)) {
            val decision = policy.decide(who, action, Resource.OfEntrance(entranceId), LocalDate.parse(toSofiaDate(clock.instant())))
            if (!decision.allowed) throw BookReadForbidden(decision.ruleId)
        }
        return then()
    }
}
