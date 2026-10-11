package zues.app.identity_org

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.time.LocalDate
import java.util.UUID

/** A mandate as the table holds it: a body's office in an entrance, a party's unless it is the firm's, with its dates. */
data class Mandate(
    val id: UUID,
    val entranceId: UUID,
    val body: String,
    val partyId: UUID?,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val succeededAt: LocalDate?,
) {
    /** The role this office is — none for the firm's own mandate (`PMC`): firm staff hold nothing until their module exists. */
    val role: Role? get() = OFFICES[body]

    /** The executive offices — the manager, the board, and a firm the powers were delegated to: a later one of these is a successor. */
    val executive: Boolean get() = body in EXECUTIVE

    /**
     * Whether this mandate takes [earlier]'s place — the one reading of "a successor is elected" (Rule: PM-GOV-004),
     * asked by the side that records a mandate and by the side that reads who holds an office. A later manager succeeds
     * every earlier executive mandate of the entrance. A later board member succeeds an earlier manager, and an earlier
     * board member only when it begins on or after the day that member's term ends — `validTo`, the first day outside
     * it: a board is several seats, and a member elected while another's term runs succeeds nobody (the owner's
     * decision, 2026-10-11). A firm's mandate counts as a board member's does. A successor that has itself ended, or
     * names no party, is a successor still: the office passed on the day it began.
     */
    // Rule: PM-GOV-004
    fun succeeds(earlier: Mandate): Boolean =
        entranceId == earlier.entranceId && executive && earlier.executive && validFrom.isAfter(earlier.validFrom) &&
            (body == MANAGER || earlier.body == MANAGER || !validFrom.isBefore(earlier.validTo))

    /**
     * Whether the mandate counts on [date], given every mandate of its entrance. It runs from `validFrom` up to and not
     * including `validTo`, and never from the day `succeededAt` records. The manager and the board continue past
     * `validTo` until a successor (Rule: PM-GOV-004) — the day recorded, or failing that the first day of a mandate
     * that [succeeds] this one, so a successor nobody stamped still ends the predecessor's stay. With no successor
     * they continue: an expired member keeps the office until a successor or an end is recorded. The controller and
     * the cashier do not continue: the rule names the board and the manager only.
     */
    // Rule: PM-GOV-004
    fun inForceOn(date: LocalDate, ofTheEntrance: List<Mandate>): Boolean {
        if (date.isBefore(validFrom)) return false
        if (succeededAt != null && !date.isBefore(succeededAt)) return false
        if (date.isBefore(validTo)) return true
        return role in CONTINUING && ofTheEntrance.none { it.succeeds(this) && !it.validFrom.isAfter(date) }
    }

    private companion object {
        val OFFICES = mapOf("BM" to Role.BM, "MB" to Role.MB, "CTL" to Role.CTL, "CSH" to Role.CSH)
        val CONTINUING = setOf(Role.BM, Role.MB)
        val EXECUTIVE = setOf("BM", "MB", "PMC")
        const val MANAGER = "BM"
    }
}

/** A party holding a role in an entrance on a date, and the mandate that says so. */
data class RoleHolder(val partyId: UUID, val role: Role, val mandateId: UUID)

/**
 * Roles from mandates (ADR-002): the manager, the board, the controller and the cashier are whoever holds a mandate in
 * force on the date asked about — derived from the mandate, never copied beside it, so the two cannot disagree. Any
 * past date is an ordinary question (Rule: PM-SEC-011).
 */
@Service
class MandateRoles(private val mandates: MandateStore) : RoleSource {

    // Rule: PM-SEC-011
    @Transactional(readOnly = true)
    override fun held(who: Asking, entranceId: UUID, asAt: LocalDate): Set<Held> {
        val party = who.party ?: return emptySet()
        val all = mandates.ofEntrance(entranceId)
        return all.filter { it.partyId == party && it.inForceOn(asAt, all) }.mapNotNullTo(mutableSetOf()) { m -> m.role?.let { Held(it, m.entranceId) } }
    }

    /** Rule: PM-SEC-011 — who held which role in the entrance on that date. */
    @Transactional(readOnly = true)
    fun holdersOn(entranceId: UUID, asAt: LocalDate): Set<RoleHolder> {
        val all = mandates.ofEntrance(entranceId)
        return all.filter { it.inForceOn(asAt, all) }.mapNotNullTo(mutableSetOf()) { m -> m.role?.let { role -> m.partyId?.let { RoleHolder(it, role, m.id) } } }
    }
}
