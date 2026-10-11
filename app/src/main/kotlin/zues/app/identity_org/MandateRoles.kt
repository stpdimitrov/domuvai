package zues.app.identity_org

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Role
import zues.app.policy.RoleSource
import zues.law.numberOn
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

    /** The day a successor takes this mandate's place, if one is known on the record: the day recorded, or the first day of the earliest mandate that [succeeds] it. */
    fun successorDay(ofTheEntrance: List<Mandate>): LocalDate? =
        succeededAt ?: ofTheEntrance.filter { it.succeeds(this) }.minOfOrNull { it.validFrom }

    /**
     * How the mandate stands on [date] (Rule: PM-GOV-004, PM-GOV-005) — or null when it is not in force that day. A
     * derived fact, from the mandate's own dates and the one reading of "successor"; nothing is stored, so nothing can
     * disagree with the mandate.
     *
     * The manager and the board: **in term**; then **an election is due**, once the date is within [reminderDays] of the
     * day the term ends and nobody is on record to succeed — to be held by that day, the first day outside the term
     * (PM-GOV-005); then **acting past term**, from that day for as long as nobody has taken the place (PM-GOV-004: the
     * incumbent continues). The controller and the cashier are in term or not in force: the rule names the board and
     * the manager only.
     */
    // Rule: PM-GOV-004
    // Rule: PM-GOV-005
    fun standingOn(date: LocalDate, ofTheEntrance: List<Mandate>, reminderDays: (termEnds: LocalDate) -> Long): Standing? {
        if (!inForceOn(date, ofTheEntrance)) return null
        if (role !in CONTINUING) return Standing(StandingKind.IN_TERM)
        val successor = successorDay(ofTheEntrance)
        if (!date.isBefore(validTo)) return Standing(StandingKind.EXPIRED_ACTING, electBy = validTo, actingSince = validTo, until = successor)
        if (successor != null) return Standing(StandingKind.IN_TERM, until = successor)       // the election has been held
        val dueFrom = validTo.minusDays(reminderDays(validTo))
        return if (date.isBefore(dueFrom)) Standing(StandingKind.IN_TERM) else Standing(StandingKind.ELECTION_DUE, electBy = validTo)
    }

    private companion object {
        val OFFICES = mapOf("BM" to Role.BM, "MB" to Role.MB, "CTL" to Role.CTL, "CSH" to Role.CSH)
        val CONTINUING = setOf(Role.BM, Role.MB)
        val EXECUTIVE = setOf("BM", "MB", "PMC")
        const val MANAGER = "BM"
    }
}

/** How a mandate in force stands (Rule: PM-GOV-004): within its dates, an election due, or acting past them until a successor. */
enum class StandingKind { IN_TERM, ELECTION_DUE, EXPIRED_ACTING }

/**
 * A mandate's standing on a date. [electBy] is the day the term ends — the election is held no later (Rule: PM-GOV-005);
 * [actingSince] the day from which the office is held past its term; [until] the day a successor on record takes over.
 */
data class Standing(val kind: StandingKind, val electBy: LocalDate? = null, val actingSince: LocalDate? = null, val until: LocalDate? = null)

/** A mandate in force in an entrance on a date, with how it stands. The office-holder is a party's id: the name is the registry's. */
data class StandingMandate(
    val mandateId: UUID,
    val partyId: UUID?,
    val role: Role,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val standing: Standing,
)

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

/**
 * How every mandate in force in an entrance stands on a date (Rule: PM-GOV-004, PM-GOV-005): who is in term, for whom
 * an election is due and by when, who is acting past term and since when. The read an endpoint, the console's
 * countdown and a compliance sweep ask — in-process for now. The same mandates, read the same way, as [MandateRoles]:
 * a mandate has a standing exactly when it gives its holder the role.
 */
@Service
class MandateStanding(private val mandates: MandateStore) {

    @Transactional(readOnly = true)
    fun of(entranceId: UUID, on: LocalDate): List<StandingMandate> {
        val all = mandates.ofEntrance(entranceId)
        return all.mapNotNull { m ->
            val role = m.role ?: return@mapNotNull null
            if (m.partyId == null) return@mapNotNull null   // nobody's mandate gives nobody a role, and has nobody to tell
            m.standingOn(on, all, ::reminderDays)?.let { StandingMandate(m.id, m.partyId, role, m.validFrom, m.validTo, it) }
        }
    }

    /**
     * How long before a term ends the election is called due: the law module's value in force on the day the term ends
     * (Rule: PM-SYS-002). On a day the law holds none, no reminder is given — the mandate is in term until it is not.
     */
    // TODO(legal): PM-GOV-004 — MANDATE_ELECTION_REMINDER_DAYS is the catalogue's reminder, not a statutory period; unconfirmed.
    private fun reminderDays(termEnds: LocalDate): Long =
        try {
            numberOn("MANDATE_ELECTION_REMINDER_DAYS", termEnds.toString()).toLong()
        } catch (e: NoSuchElementException) {
            NO_REMINDER
        }

    private companion object {
        const val NO_REMINDER = 0L   // not-legal: the absence of a period, not a period
    }
}
