package zues.app.registry

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Who files a declaration for the book (Rule: PM-BOOK-003): the declarant is the person the sign-in says is asking,
 * filing their own for a unit they hold — or, naming another person, the manager or the board recording the
 * declaration that person handed in (Rule: PM-BOOK-005) — an owner or a user of the unit today, like any declarant.
 * Either way the declaration records who filed it. Decided by the
 * policy as at today in Sofia, before anything is said about the unit: a unit that is not in the entrance is decided on
 * the entrance alone, so a declarant learns nothing of it. With sign-in switched off in so many words nobody is asking,
 * and the declarant is the one the request names — as everything else is in that mode.
 */
@Service
class DeclarationFiling(
    private val declarations: DeclarationService,
    private val units: PropertyUnitRepository,
    private val titles: TitleRepository,
    private val policy: Policy,
    private val clock: Clock,
    @Value("\${domuvai.auth.mode:}") mode: String = "",
) {
    private val open = mode.trim().equals("off", ignoreCase = true)

    // Rule: PM-BOOK-003
    // Rule: PM-BOOK-005
    @Transactional
    fun file(entranceId: UUID, unitId: UUID, named: UUID?, kind: String, who: Asking): BookDeclaration {
        if (open && who.login == null && who.party == null) {
            return declarations.file(entranceId, unitId, requireNotNull(named) { "partyId names the declarant when nobody is signed in" }, kind)
        }
        val self = who.party
        val declarant = named ?: self
        val action = if (self != null && declarant == self) Action.FILE_DECLARATION else Action.RECORD_DECLARATION
        val unit = units.findById(unitId).orElse(null)?.takeIf { it.entranceId == entranceId }
        val resource = if (unit != null) Resource.OfUnit(entranceId, unitId) else Resource.OfEntrance(entranceId)
        val today = LocalDate.parse(toSofiaDate(clock.instant()))
        val decision = policy.decide(who, action, resource, today)
        if (!decision.allowed || self == null || declarant == null) throw BookReadForbidden(decision.ruleId)
        // Owners and users declare (PM-BOOK-003): a declaration recorded in someone's name is one they could have filed —
        // they hold the unit, as owner or user, today. Anybody else, or a party not there, is the same 404.
        if (action == Action.RECORD_DECLARATION && unit != null && !holds(declarant, entranceId, unitId, today)) {
            throw NoSuchElementException("no owner or user $declarant of unit $unitId")
        }
        return declarations.file(entranceId, unitId, declarant, kind, filedBy = self)
    }

    private fun holds(party: UUID, entranceId: UUID, unitId: UUID, on: LocalDate) = titles.findByEntranceId(entranceId).any {
        it.partyId == party && it.unitId == unitId && it.titleRole in DECLARANTS && it.validFrom <= on && (it.validTo == null || on < it.validTo)
    }

    private companion object {
        val DECLARANTS = setOf(TitleRole.OWN.name, TitleRole.USR.name)
    }
}
