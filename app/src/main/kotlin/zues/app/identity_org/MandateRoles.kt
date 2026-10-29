package zues.app.identity_org

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Role
import zues.app.policy.RoleSource
import zues.law.constantOn
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
     *
     * A controller succeeds an earlier controller as a board member does a fellow member — on or after the day that
     * one's term ends — since a control board is several seats too (PM-GOV-007; the owner's decision of 2026-10-21 that
     * a later controller succeeds the earlier, read by the board's seat rule). No office crosses between the executive
     * and the controller, and the cashier succeeds nobody. This names who took a controller's place, and changes nothing
     * about continuing: a controller does not continue past their dates ([inForceOn]).
     */
    // Rule: PM-GOV-004
    // Rule: PM-GOV-007
    fun succeeds(earlier: Mandate): Boolean =
        entranceId == earlier.entranceId && validFrom.isAfter(earlier.validFrom) && when {
            executive && earlier.executive -> body == MANAGER || earlier.body == MANAGER || !validFrom.isBefore(earlier.validTo)
            body == CONTROLLER && earlier.body == CONTROLLER -> !validFrom.isBefore(earlier.validTo)
            else -> false
        }

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

    /**
     * The first day this mandate is no longer in force, as far as the record says — the day [inForceOn] turns false:
     * the day an end is recorded, or, for the manager and the board, the first day of the earliest mandate that
     * [succeeds] it, and never before its own term ends; for every other office, the day its term ends. Null only for
     * a manager or a board member nothing on record ends: that one is open.
     */
    fun endsOnRecord(ofTheEntrance: List<Mandate>): LocalDate? {
        val byItsOffice = if (role in CONTINUING) ofTheEntrance.filter { it.succeeds(this) }.minOfOrNull { it.validFrom }?.let { maxOf(it, validTo) } else validTo
        return listOfNotNull(succeededAt, byItsOffice).minOrNull()
    }

    /**
     * How the mandate stands on [date] (Rule: PM-GOV-004) — or null when it is not in force that day. A derived fact,
     * from the mandate's own dates and the one reading of "successor"; nothing is stored, so nothing can disagree with
     * the mandate. **A standing is a fact to be read: no task is raised, no election is scheduled, and nothing shows it
     * yet** — the acceptance's election task and PM-GOV-005's scheduling are not built here.
     *
     * The manager and the board: **in term**; then **an election is due**, once the date is within [reminderDays] of the
     * day the office is left — the day the term ends, or an earlier day an end is recorded for — unless a mandate that
     * succeeds this one is already on record; then **acting past term**, from the day the term ends for as long as
     * nobody has taken the place (the incumbent continues). An end recorded with nobody to succeed — a resignation —
     * silences nothing: the election is due by that day. The controller and the cashier are in term or not in force:
     * the rule names the board and the manager only.
     */
    // Rule: PM-GOV-004
    fun standingOn(date: LocalDate, ofTheEntrance: List<Mandate>, reminderDays: (leaves: LocalDate) -> Long?): Standing? {
        if (!inForceOn(date, ofTheEntrance)) return null
        val until = endsOnRecord(ofTheEntrance)
        if (role !in CONTINUING) return Standing(StandingKind.IN_TERM, until = until)
        if (!date.isBefore(validTo)) return Standing(StandingKind.EXPIRED_ACTING, electBy = validTo, actingSince = validTo, until = until)
        if (ofTheEntrance.any { it.succeeds(this) }) return Standing(StandingKind.IN_TERM, until = until)   // somebody is elected to follow
        val leaves = listOfNotNull(validTo, succeededAt).min()
        val reminder = reminderDays(leaves) ?: return Standing(StandingKind.IN_TERM, until = until)        // the law holds no period for that day
        return if (date.isBefore(leaves.minusDays(reminder))) Standing(StandingKind.IN_TERM, until = until)
        else Standing(StandingKind.ELECTION_DUE, electBy = leaves, until = until)
    }

    private companion object {
        val OFFICES = mapOf("BM" to Role.BM, "MB" to Role.MB, "CTL" to Role.CTL, "CSH" to Role.CSH)
        val CONTINUING = setOf(Role.BM, Role.MB)
        val EXECUTIVE = setOf("BM", "MB", "PMC")
        const val MANAGER = "BM"
        const val CONTROLLER = "CTL"
    }
}

/** How a mandate in force stands (Rule: PM-GOV-004): within its dates, an election due, or acting past them until a successor. */
enum class StandingKind { IN_TERM, ELECTION_DUE, EXPIRED_ACTING }

/**
 * A mandate's standing on a date. [electBy] is the day the office is left — the day the term ends, or an earlier
 * recorded end: PM-GOV-005 wants the election no later; [actingSince] the day from which the office is held past its
 * term; [until] the first day the mandate is no longer in force, where the record says ([Mandate.endsOnRecord]).
 */
data class Standing(val kind: StandingKind, val electBy: LocalDate? = null, val actingSince: LocalDate? = null, val until: LocalDate? = null)

/**
 * A mandate in force in an entrance on a date, with how it stands. The office-holder is a party's id — or nobody, where
 * the mandate names none — and [partyName] the registry's name for it, only where the reader may read the book
 * ([PartyNames]); absent otherwise.
 */
data class StandingMandate(
    val mandateId: UUID,
    val partyId: UUID?,
    val role: Role,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val standing: Standing,
    val partyName: String? = null,
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
 * How every mandate in force in an entrance stands on a date (Rule: PM-GOV-004): who is in term, for whom an election
 * is due and by when, who is acting past term and since when. In-process only: no endpoint serves it, no screen shows
 * it and no task is raised from it yet. The same mandates, read the same way, as [MandateRoles]: a mandate has a
 * standing exactly when it is in force — one that names no party included, since its office still needs an election.
 */
@Service
class MandateStanding(private val mandates: MandateStore) {

    init {
        // A period the law module does not know at all is a mistake here, not "no reminder": say so when the application starts.
        constantOn(REMINDER, FAR_AHEAD)
    }

    @Transactional(readOnly = true)
    fun of(entranceId: UUID, on: LocalDate): List<StandingMandate> {
        val all = mandates.ofEntrance(entranceId)
        return all.mapNotNull { m ->
            val role = m.role ?: return@mapNotNull null
            m.standingOn(on, all, ::reminderDays)?.let { StandingMandate(m.id, m.partyId, role, m.validFrom, m.validTo, it) }
        }
    }

    /**
     * How long before an office is left the election is called due: the law module's value in force on that day
     * (Rule: PM-SYS-002) — or null on a day the law holds none, when no reminder is given and none is invented.
     */
    // TODO(legal): PM-GOV-004 — MANDATE_ELECTION_REMINDER_DAYS is the catalogue's reminder, not a statutory period; unconfirmed.
    private fun reminderDays(leaves: LocalDate): Long? =
        try {
            numberOn(REMINDER, leaves.toString()).toLong()
        } catch (e: NoSuchElementException) {
            null
        }

    private companion object {
        const val REMINDER = "MANDATE_ELECTION_REMINDER_DAYS"
        const val FAR_AHEAD = "9999-12-31"
    }
}
