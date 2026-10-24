package zues.app.assembly

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
 * Who reads an entrance's assemblies (ADR-002): asked of the policy as at today in Sofia, for the person the sign-in
 * says is asking — never a parameter — and before anything is read, so a refusal tells nothing of what is there. With
 * sign-in switched off in so many words (`DOMUVAI_AUTH=off`) nobody is asking and nothing can be decided, so the
 * reads are served — as everything else is in that mode.
 */
@Service
class AssemblyReads(
    private val policy: Policy,
    private val clock: Clock,
    @Value("\${domuvai.auth.mode:}") mode: String = "",
) {
    private val open = mode.trim().equals("off", ignoreCase = true)

    /**
     * Rule: PM-SEC-001 — anyone holding a role in the entrance reads its assemblies (the owner's decision of 2026-10-22,
     * R1 on #247); an owner, as condominium-level matters (PM-SEC-002); the controller, as all of its documentation
     * (PM-GOV-008). Anybody else is [ActRefused], naming the rule. A narrower read names its own [action] — how each
     * owner voted is [Action.READ_VOTES]: the manager, the board and the controller only (PM-SEC-002).
     */
    fun require(entranceId: UUID, who: Asking, action: Action = Action.READ_ASSEMBLY) {
        if (unasked(who)) return
        val decision = decide(action, entranceId, who)
        if (!decision.allowed) throw ActRefused(decision.ruleId)
    }

    /** Rule: PM-SEC-002 — whether the owner behind a higher quorum is named to this reader: the manager, the board and the controller only. */
    fun seesConcentration(entranceId: UUID, who: Asking): Boolean = unasked(who) || decide(Action.READ_CONCENTRATION, entranceId, who).allowed

    private fun unasked(who: Asking) = open && who.login == null && who.party == null

    private fun decide(action: Action, entranceId: UUID, who: Asking) =
        policy.decide(who, action, Resource.OfEntrance(entranceId), LocalDate.parse(toSofiaDate(clock.instant())))
}
