package zues.app.assembly

import org.springframework.stereotype.Service
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** The policy does not allow it, or nobody a record could name is asking → 403, naming the rule and nothing else. */
class ActRefused(val ruleId: String) : RuntimeException("not allowed ($ruleId)")

/**
 * Who acts in `assembly`, and whether they may (ADR-002). The actor is the party the sign-in is tied to — never a
 * field of a request — and what that party may do is the policy's to say, as at today in Sofia. An act is a party's:
 * a login tied to no party, and nobody at all — sign-in switched off — act on nothing here, since what `assembly`
 * records is not undone and must name who did it.
 */
@Service
class Acting(private val policy: Policy, private val clock: Clock) {

    /** The party acting, when the policy allows [action] in the entrance today; otherwise [ActRefused]. */
    // Rule: PM-SEC-001
    fun party(who: Asking, action: Action, entranceId: UUID): UUID {
        val today = LocalDate.parse(toSofiaDate(clock.instant()))                  // a Sofia calendar day (PM-SYS-004)
        val decision = policy.decide(who, action, Resource.OfEntrance(entranceId), today)
        val party = who.party
        if (!decision.allowed || party == null) throw ActRefused(decision.ruleId)
        return party
    }
}
