package zues.app.identity_org

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
 * An entrance's mandates and how each stands, for whoever the policy allows (Rule: PM-GOV-005 — the manager and the
 * board, whose election comes due; PM-GOV-008 — the controller; and the administrator, who records them). Who may is
 * decided as at today in Sofia, before anything is read; what is read is the date asked for. Nothing is stored, sent
 * or scheduled from it: it is the countdown's source, not a task.
 */
@Service
class MandateReading(private val standing: MandateStanding, private val names: PartyNames, private val policy: Policy, private val clock: Clock) {

    // Rule: PM-GOV-004
    // Rule: PM-GOV-005
    fun read(by: Asking, entranceId: UUID, on: LocalDate?): MandateAnswer<List<StandingMandate>> {
        val today = LocalDate.parse(toSofiaDate(clock.instant()))
        val decision = policy.decide(by, Action.READ_MANDATES, Resource.OfEntrance(entranceId), today)
        if (!decision.allowed) return MandateAnswer.Refused(decision.ruleId)
        return MandateAnswer.Done(named(entranceId, by, standing.of(entranceId, on ?: today)))
    }

    /**
     * The office-holders' names beside their ids, to a reader of the book only — the administrator, who reads the
     * mandates and no book, keeps the ids (ADR-002). One ask for the whole read, and none where no office names anybody.
     */
    // Rule: PM-BOOK-006
    // Rule: PM-BOOK-007
    private fun named(entranceId: UUID, by: Asking, mandates: List<StandingMandate>): List<StandingMandate> {
        val ids = mandates.mapNotNullTo(mutableSetOf()) { it.partyId }
        if (ids.isEmpty()) return mandates
        val served = names.named(entranceId, by, ids, PURPOSE) ?: return mandates
        return mandates.map { m -> m.copy(partyName = m.partyId?.let(served::get)) }
    }

    companion object {
        const val PURPOSE = "the entrance's mandates, named"
    }
}
