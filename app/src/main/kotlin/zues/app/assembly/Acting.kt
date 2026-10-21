package zues.app.assembly

import org.springframework.stereotype.Service
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.identity_org.Succession
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.app.policy.Role
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Who acts: the party, and the sign-in that was tied to it when it acted — both go on the record of the act. */
data class Actor(val party: UUID, val loginIssuer: String, val loginSubject: String)

/** Who acts, and which of the actions asked about the policy allows them today — one of them at least. */
data class Allowed(val actor: Actor, val actions: Set<Action>)

/** The policy does not allow it, or nobody a record could name is asking → 403, naming the rule and nothing else. */
class ActRefused(val ruleId: String) : RuntimeException("not allowed ($ruleId)")

/**
 * Who acts in `assembly`, and whether they may (ADR-002). The actor is the party the sign-in is tied to — never a
 * field of a request — and what that party may do is the policy's to say, as at today in Sofia. An act is a
 * signed-in party's: a login tied to no party, a party with no sign-in behind it, and nobody at all — sign-in switched
 * off — act on nothing here, since what `assembly` records is not undone and must name who did it.
 */
@Service
class Acting(private val policy: Policy, private val succession: Succession, private val clock: Clock) {

    /**
     * Who acts, when the policy allows [action] in the entrance today; otherwise [ActRefused] — naming the action's
     * rule when the policy refuses, and PM-SEC-001 when there is no signed-in party to decide about at all.
     */
    // Rule: PM-SEC-001
    fun actor(who: Asking, action: Action, entranceId: UUID): Actor = allowedAny(who, entranceId, action).actor

    /**
     * Who acts, with those of [actions] the policy allows them in the entrance today — refused when it allows none,
     * under the first one's rule. For an act two kinds of people may do: which of them this is decides what follows.
     */
    // Rule: PM-SEC-001
    fun allowedAny(who: Asking, entranceId: UUID, vararg actions: Action): Allowed {
        val actor = signedIn(who)
        val decisions = actions.associateWith { policy.decide(who, it, Resource.OfEntrance(entranceId), today()) }
        val allowed = decisions.filterValues { it.allowed }.keys
        if (allowed.isEmpty()) throw ActRefused(decisions.getValue(actions.first()).ruleId)
        return Allowed(actor, allowed)
    }

    /**
     * Who convenes in an office (Rule: PM-GA-002): the policy allows convening, and the [office] stated is one this
     * party holds in the entrance today — a mandate in force, not a word in a request.
     */
    // Rule: PM-GA-002
    fun officeHolder(who: Asking, entranceId: UUID, office: Role): Actor {
        val actor = actor(who, Action.CONVENE_ASSEMBLY, entranceId)
        // which roles are offices that convene is the matrix's to say, here as everywhere
        val convenes = Policy.MATRIX.any { it.action == Action.CONVENE_ASSEMBLY && it.role == office }
        if (!convenes || policy.rolesOf(who, entranceId, today()).none { it.role == office }) throw ActRefused(Action.CONVENE_ASSEMBLY.governedBy)
        return actor
    }

    /**
     * Whether a party holds a role in the entrance today — asked of the policy, whose answer for an office comes from
     * the mandates and their one reading of who succeeded whom. Nothing about succession is worked out here.
     */
    fun holdsToday(party: UUID, entranceId: UUID, role: Role): Boolean = heldOn(party, entranceId, role, today())

    /**
     * Whether [candidate] has, today, taken [predecessor]'s place in [office] — `identity_org`'s one reading of
     * succession ([Succession], following `Mandate.succeeds`), asked, never worked out here.
     */
    // Rule: PM-GOV-004
    fun hasSucceeded(candidate: UUID, predecessor: UUID, entranceId: UUID, office: Role): Boolean =
        succession.hasSucceeded(candidate, predecessor, entranceId, office, today())

    /** Whether a party held a role in the entrance on a day — the same question of the policy, about a day that is not today. */
    fun heldOn(party: UUID, entranceId: UUID, role: Role, on: LocalDate): Boolean =
        policy.rolesOf(Asking(null, party), entranceId, on).any { it.role == role }

    private fun signedIn(who: Asking): Actor {
        val party = who.party
        val login = who.login
        if (party == null || login == null) throw ActRefused(NOBODY_ACTS)
        return Actor(party, login.issuer, login.subject)
    }

    private fun today(): LocalDate = LocalDate.parse(toSofiaDate(clock.instant()))   // a Sofia calendar day (PM-SYS-004)

    private companion object {
        /** The rule that makes access a matter of roles: with nobody signed in as a party, there is no role to hold. */
        const val NOBODY_ACTS = "PM-SEC-001"
    }
}
