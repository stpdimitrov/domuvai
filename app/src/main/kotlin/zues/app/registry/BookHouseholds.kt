package zues.app.registry

import org.springframework.stereotype.Service
import zues.app.policy.Asking
import java.time.LocalDate
import java.util.UUID

/** What the book says of somebody called a member of a principal's household, as far as the one asking may be told. */
enum class HouseholdAnswer {
    /** they live in one of the principal's own units, and are no child under six there */
    MEMBER,
    /** they do not live there */
    NOT_LIVING_THERE,
    /** they live there as a child under six */
    CHILD_UNDER_6,
    /** not a member, and the one asking is not told why */
    NOT_BORNE_OUT,
}

/**
 * The registry's published test of an appointment against the book (Rule: PM-BOOK-006, PM-BOOK-007): the system asking,
 * for whoever is registering a proxy, so no decision gates it. What the one asking is told is the book's to say, and
 * every test is on the book's record, a refused appointment's too.
 */
interface BookHouseholds {
    /** Whether [agent] lives on [on] with [principal], in a unit of [units], the units [principal] holds a title in. */
    fun member(entranceId: UUID, who: Asking, principal: UUID, agent: UUID, units: Set<UUID>, on: LocalDate): HouseholdAnswer
}

@Service
class BookHouseholdsAdapter(private val holdings: Holdings, private val access: BookAccessService) : BookHouseholds {
    // Rule: PM-BOOK-006
    // Rule: PM-BOOK-007
    // Rule: PM-SEC-004
    // Rule: PM-GA-009
    override fun member(entranceId: UUID, who: Asking, principal: UUID, agent: UUID, units: Set<UUID>, on: LocalDate): HouseholdAnswer {
        val told = access.tested(entranceId, who, principal, on, PURPOSE)
        val residents = holdings.residents(entranceId, on)
        val home = residents.filter { it.partyId == principal }.map { it.unitId }.toSet().intersect(units)
        val rows = residents.filter { it.partyId == agent && it.unitId in home }
        // TODO(legal): PM-GA-009 — "adult": the book records no date of birth, only who is a child under six, so nobody
        // older than that is checked. Asked of the principal's own home alone: the flag is told to nobody else
        return when {
            rows.isNotEmpty() && rows.none { it.childUnder6 } -> HouseholdAnswer.MEMBER
            !told -> HouseholdAnswer.NOT_BORNE_OUT
            rows.isEmpty() -> HouseholdAnswer.NOT_LIVING_THERE
            else -> HouseholdAnswer.CHILD_UNDER_6
        }
    }

    companion object {
        const val PURPOSE = "a proxy's household check"
    }
}
