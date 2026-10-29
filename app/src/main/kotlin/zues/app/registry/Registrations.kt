package zues.app.registry

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Decision
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The list of entrances, and an entrance or a party registered, as an HTTP caller asks (the owner's decisions R1a, R2a,
 * R3a on #322): each person lists the entrances where they hold a role today in Sofia, the administrator every one; a
 * party is registered by the administrator or by the manager or the board of any entrance; an entrance by the
 * administrator only. Decided before anything is written: a caller refused learns nothing. With sign-in switched off in
 * so many words nobody is asking, and all is as before. The services behind stay unguarded for in-process callers.
 */
@Service
class Registrations(
    private val registry: RegistryService,
    private val ownership: OwnershipService,
    private val policy: Policy,
    private val clock: Clock,
    @Value("\${domuvai.auth.mode:}") mode: String = "",
) {
    private val open = mode.trim().equals("off", ignoreCase = true)

    // Rule: PM-SEC-001
    fun entrances(who: Asking): List<Entrance> {
        val all = registry.listEntrances()
        if (unasked(who)) return all
        val today = today()
        return all.filter { policy.decide(who, Action.LIST_ENTRANCE, Resource.OfEntrance(it.id), today).allowed }
    }

    // Rule: PM-ORG-001
    // Rule: PM-SEC-001
    fun entrance(command: RegisterEntrance, who: Asking): EntranceCreated {
        if (!unasked(who)) allowed(policy.decide(who, Action.REGISTER_ENTRANCE, Resource.Deployment, today()), ENTRANCE_REFUSED)
        return registry.registerEntrance(command)
    }

    // Rule: PM-BOOK-005
    // Rule: PM-SEC-001
    fun party(command: RegisterParty, who: Asking): UUID {
        if (!unasked(who)) allowed(policy.decideInAny(who, Action.REGISTER_PARTY, registry.listEntrances().map { it.id }, today()), PARTY_REFUSED)
        return ownership.registerParty(command)
    }

    private fun unasked(who: Asking) = open && who.login == null && who.party == null

    private fun today() = LocalDate.parse(toSofiaDate(clock.instant()))

    private fun allowed(decision: Decision, why: String) {
        if (!decision.allowed) throw BookReadForbidden(decision.ruleId, why)
    }

    private companion object {
        const val ENTRANCE_REFUSED = "an entrance is registered by the deployment's administrator"
        const val PARTY_REFUSED = "a party is registered by the deployment's administrator, or by the manager or the board of an entrance"
    }
}
