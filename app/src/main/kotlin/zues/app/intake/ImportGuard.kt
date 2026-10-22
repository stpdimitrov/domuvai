package zues.app.intake

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

/** The policy refused this caller the import path of the entrance. */
class ImportForbidden(val ruleId: String) : RuntimeException("refused by $ruleId")

/**
 * Who may bring a fee sheet into an entrance's book (Rule: PM-BOOK-005): the import path enters units, owners and
 * households, so it is the book's entry, and asks the policy what the book's own entries ask — `ENTER_BOOK`, the
 * manager and the board — of the entrance the import belongs to, as at today in Sofia, before anything is read or
 * written. With sign-in switched off (`DOMUVAI_AUTH=off`, said in so many words) nobody is asking and the path stays
 * open, as the book's own entries do: a developer's machine, and CI's seed.
 */
@Service
class ImportGuard(
    private val policy: Policy,
    private val clock: Clock,
    @Value("\${domuvai.auth.mode:}") mode: String = "",
) {
    private val open = mode.trim().equals("off", ignoreCase = true)

    /**
     * Who the record names as having done it: the sign-in's party when the caller may — or, with sign-in switched off
     * and nobody asking, [stated], the only time a body's word is taken. A caller the policy refuses is [ImportForbidden].
     */
    // Rule: PM-BOOK-005
    // Rule: PM-SEC-001
    fun actor(entranceId: UUID, who: Asking, stated: UUID?): UUID {
        if (open && who.login == null && who.party == null) return requireNotNull(stated) { "with sign-in switched off, say who does it" }
        allowed(entranceId, who)
        return who.party ?: throw ImportForbidden(Action.ENTER_BOOK.governedBy)                    // an act is a party's
    }

    /** That the caller may: nothing is returned, a refusal is thrown. */
    // Rule: PM-BOOK-005
    fun allowed(entranceId: UUID, who: Asking) {
        if (open && who.login == null && who.party == null) return
        val decision = policy.decide(who, Action.ENTER_BOOK, Resource.OfEntrance(entranceId), LocalDate.parse(toSofiaDate(clock.instant())))
        if (!decision.allowed) throw ImportForbidden(decision.ruleId)
        if (who.party == null) throw ImportForbidden(Action.ENTER_BOOK.governedBy)                  // the book is entered by somebody in it
    }
}
