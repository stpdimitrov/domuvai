package zues.app.registry

import org.springframework.stereotype.Service
import zues.app.policy.Action
import zues.app.policy.Asking
import java.time.LocalDate
import java.util.UUID

/** What the book declares of a unit's persons on a date: how many, never who. */
data class DeclaredHeadcount(val unitId: UUID, val designation: String, val occupants: Int, val childrenUnder6: Int)

/**
 * The registry's published read of what the book declares of each unit's persons, for a person reading it (Rule:
 * PM-BOOK-006, PM-BOOK-007): the decision is the book's own, [Action.READ_BOOK], never the caller's, and every answer
 * is on the book's record. [Units] gives the same counts to a charge run, which is no person's read.
 */
interface BookHeadcounts {
    /**
     * As of [on]. With no date there is nothing to read as of — a period with no run — and the reader is still answered:
     * refused as for any other date, or given nothing.
     */
    fun declared(entranceId: UUID, who: Asking, on: LocalDate?): BookAnswer<List<DeclaredHeadcount>>
}

@Service
class BookHeadcountsAdapter(private val units: Units, private val access: BookAccessService) : BookHeadcounts {
    // Rule: PM-BOOK-006
    // Rule: PM-BOOK-007
    // Rule: PM-SEC-004
    override fun declared(entranceId: UUID, who: Asking, on: LocalDate?): BookAnswer<List<DeclaredHeadcount>> =
        if (on == null) {
            access.unserved(entranceId, who, BookAccessKind.HEADCOUNT_READ, Action.READ_BOOK, PURPOSE, emptyList())
        } else {
            access.standing(entranceId, who, BookAccessKind.HEADCOUNT_READ, Action.READ_BOOK, on, PURPOSE) {
                units.forEntrance(entranceId, on).map { DeclaredHeadcount(it.unitId, it.designation, it.occupants, it.childrenUnder6) }
            }
        }

    companion object {
        const val PURPOSE = "the period's headcount check"
    }
}
