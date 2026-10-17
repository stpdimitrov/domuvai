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
 * Who lives in a unit, as an HTTP caller enters it in the book (Rule: PM-BOOK-005, PM-BOOK-002): its household, its
 * animals, its absences, and the day a resident or an animal left — entered by the manager or the board, from the
 * declarant's declaration; an owner or a user changes it by declaring (PM-BOOK-003). Decided by the policy as at today
 * in Sofia, on the entrance, before anything is said about the unit: a caller refused learns nothing of it. With sign-in
 * switched off in so many words nobody is asking, and the entry is made as before. The services behind are untouched:
 * an import's commit adopts a household in-process and is not a caller.
 */
@Service
class BookEntries(
    private val registry: RegistryService,
    private val policy: Policy,
    private val clock: Clock,
    @Value("\${domuvai.auth.mode:}") mode: String = "",
) {
    private val open = mode.trim().equals("off", ignoreCase = true)

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

    private fun <T> entered(entranceId: UUID, who: Asking, entry: () -> T): T {
        if (!(open && who.login == null && who.party == null)) {
            val decision = policy.decide(who, Action.ENTER_BOOK, Resource.OfEntrance(entranceId), LocalDate.parse(toSofiaDate(clock.instant())))
            if (!decision.allowed) throw BookReadForbidden(decision.ruleId)
        }
        return entry()
    }
}
