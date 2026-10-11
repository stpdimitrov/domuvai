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

/** Who acts: the party, and the sign-in that was tied to it when it acted — both go on the record of the act. */
data class Actor(val party: UUID, val loginIssuer: String, val loginSubject: String)

/** The policy does not allow it, or nobody a record could name is asking → 403, naming the rule and nothing else. */
class ActRefused(val ruleId: String) : RuntimeException("not allowed ($ruleId)")

/**
 * Who acts in `assembly`, and whether they may (ADR-002). The actor is the party the sign-in is tied to — never a
 * field of a request — and what that party may do is the policy's to say, as at today in Sofia. An act is a
 * signed-in party's: a login tied to no party, a party with no sign-in behind it, and nobody at all — sign-in switched
 * off — act on nothing here, since what `assembly` records is not undone and must name who did it.
 */
@Service
class Acting(private val policy: Policy, private val clock: Clock) {

    /**
     * Who acts, when the policy allows [action] in the entrance today; otherwise [ActRefused] — naming the action's
     * rule when the policy refuses, and PM-SEC-001 when there is no signed-in party to decide about at all.
     */
    // Rule: PM-SEC-001
    fun actor(who: Asking, action: Action, entranceId: UUID): Actor {
        val party = who.party
        val login = who.login
        if (party == null || login == null) throw ActRefused(NOBODY_ACTS)
        val today = LocalDate.parse(toSofiaDate(clock.instant()))                  // a Sofia calendar day (PM-SYS-004)
        val decision = policy.decide(who, action, Resource.OfEntrance(entranceId), today)
        if (!decision.allowed) throw ActRefused(decision.ruleId)
        return Actor(party, login.issuer, login.subject)
    }

    private companion object {
        /** The rule that makes access a matter of roles: with nobody signed in as a party, there is no role to hold. */
        const val NOBODY_ACTS = "PM-SEC-001"
    }
}
