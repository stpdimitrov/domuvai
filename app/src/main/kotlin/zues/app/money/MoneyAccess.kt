package zues.app.money

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** A read of money's personal data the policy refuses — a 403, naming the rule (ADR-002). */
class MoneyReadForbidden(val ruleId: String) : RuntimeException("refused by $ruleId")

/** A write to an entrance's accounts the policy refuses — a 403, naming the rule (ADR-002). */
class MoneyWriteForbidden(val ruleId: String) : RuntimeException("refused by $ruleId")

/**
 * Who may read money's personal data (Rule: PM-SEC-002, ADR-002): asked of the policy as at today in Sofia, for the
 * person the sign-in says is asking — never a parameter. A unit's entrance is money's own record of it, stamped on every
 * posting when the unit was charged, never the caller's: a unit with nothing posted has no entrance to decide on and is
 * refused, which tells the caller nothing about it. With sign-in switched off in so many words (`DOMUVAI_AUTH=off`)
 * nobody is asking and nothing can be decided, so the reads are served — as everything else is in that mode.
 */
@Service
class MoneyAccess(
    private val policy: Policy,
    private val postings: PostingRepository,
    private val clock: Clock,
    @Value("\${domuvai.auth.mode:}") mode: String = "",
) {
    private val open = mode.trim().equals("off", ignoreCase = true)

    /**
     * Rule: PM-SEC-002 — one unit's charges: its statement, its arrears. The entrance is that of the unit's first posting:
     * every writer stamps a unit's postings with the one entrance the registry holds it in (#209 item 4).
     */
    fun unitCharges(unitId: UUID, who: Asking) {
        if (unasked(who)) return
        val entranceId = postings.findFirstByUnitId(unitId)?.entranceId ?: throw MoneyReadForbidden(Action.READ_UNIT_CHARGES.governedBy)
        require(Action.READ_UNIT_CHARGES, Resource.OfUnit(entranceId, unitId), who)
    }

    /**
     * Rule: PM-SEC-002 — a payment's allocation: its unit's charges, decided on the payment's own record. A payment that
     * is not there is decided on the entrance alone, so an owner is refused as for any other unit's payment — and
     * learns nothing of whether it exists.
     */
    fun payment(entranceId: UUID, payment: PaymentRow?, who: Asking) {
        if (unasked(who)) return
        val resource = if (payment != null && payment.entranceId == entranceId) Resource.OfUnit(entranceId, payment.unitId) else Resource.OfEntrance(entranceId)
        require(Action.READ_UNIT_CHARGES, resource, who)
    }

    /** Rule: PM-SEC-002 — an entrance's arrears or its journal: other persons' debts; and its issued periods, read beside them. */
    fun entranceArrears(entranceId: UUID, who: Asking) {
        if (unasked(who)) return
        require(Action.READ_ENTRANCE_ARREARS, Resource.OfEntrance(entranceId), who)
    }

    /**
     * Rule: PM-SEC-002 — an entrance's fund, its accounts, its handover statements and its operating account: reports of
     * the condominium, read by the manager and the board (PM-SEC-001: by their role, in their entrance), the controller
     * (PM-GOV-008) and the entrance's owners (the owner's decision of 2026-10-21).
     */
    fun fund(entranceId: UUID, who: Asking) {
        if (unasked(who)) return
        require(Action.READ_FUND, Resource.OfEntrance(entranceId), who)
    }

    /**
     * Rule: PM-SEC-002 — whether the person reading the fund also sees who its records name: the manager, the board and the
     * controller do; an owner reads the same records without them (#253). Nobody asking is served as every read is in that mode.
     */
    fun seesFundParties(entranceId: UUID, who: Asking): Boolean =
        unasked(who) || policy.decide(who, Action.READ_FUND_PARTIES, Resource.OfEntrance(entranceId), LocalDate.parse(toSofiaDate(clock.instant()))).allowed

    /**
     * Rule: PM-SEC-001 — the entrance's accounts are kept by the manager and the board (the owner's decision of
     * 2026-10-21, #250): a charge run issued or previewed, a payment recorded, the fund's account registered, a
     * disbursement signed off, paid or cancelled, a handover statement issued. Asked before anything is read or written.
     */
    fun keep(entranceId: UUID, who: Asking) {
        if (unasked(who)) return
        val decision = policy.decide(who, Action.KEEP_ACCOUNTS, Resource.OfEntrance(entranceId), LocalDate.parse(toSofiaDate(clock.instant())))
        if (!decision.allowed) throw MoneyWriteForbidden(decision.ruleId)
    }

    /**
     * Rule: PM-FUND-007 — [keep], and who is acting: the sign-in's party, never a body's word (#260). Null only with sign-in
     * switched off, where nobody is asking and the record falls to the fund account's holder.
     */
    fun keeper(entranceId: UUID, who: Asking): UUID? {
        keep(entranceId, who)
        if (unasked(who)) return null
        return who.party ?: throw MoneyWriteForbidden(Action.KEEP_ACCOUNTS.governedBy)
    }

    /**
     * Rule: PM-SEC-001 — a run previewed from a stated basis, for the entrance its body names. With somebody asking, a
     * body that names no entrance is kept by nobody: there is nothing to decide on, so it is refused.
     */
    fun keep(entranceNamed: String, who: Asking) {
        if (unasked(who)) return
        keep(runCatching { UUID.fromString(entranceNamed) }.getOrNull() ?: throw MoneyWriteForbidden(Action.KEEP_ACCOUNTS.governedBy), who)
    }

    private fun unasked(who: Asking) = open && who.login == null && who.party == null

    private fun require(action: Action, resource: Resource, who: Asking) {
        val decision = policy.decide(who, action, resource, LocalDate.parse(toSofiaDate(clock.instant())))
        if (!decision.allowed) throw MoneyReadForbidden(decision.ruleId)
    }
}

/** A caller the policy refuses, in any of money's controllers → 403, naming the rule. Nobody signed in is refused the same way. */
@RestControllerAdvice(basePackageClasses = [MoneyAccess::class])
class MoneyRefusals {
    @ExceptionHandler(MoneyReadForbidden::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onForbidden(e: MoneyReadForbidden): Map<String, String> =
        mapOf("error" to (REFUSED[e.ruleId] ?: REFUSED.getValue("PM-SEC-002")), "rule" to e.ruleId)

    @ExceptionHandler(MoneyWriteForbidden::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onWriteForbidden(e: MoneyWriteForbidden): Map<String, String> =
        mapOf("error" to "an entrance's accounts are kept by its manager and its board", "rule" to e.ruleId)

    /** A keeper of the accounts who does not hold the fund's account → 403: only its holder signs (Rule: PM-FUND-007). */
    @ExceptionHandler(NotTheHolder::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onNotTheHolder(e: NotTheHolder): Map<String, String> = mapOf("error" to (e.message ?: "not the fund account's holder"), "rule" to "PM-FUND-007")

    private companion object {
        val REFUSED = mapOf(
            "PM-SEC-002" to "a unit's charges are for its owner, the manager, the board and the controller; an entrance's arrears for the manager, the board and the controller; its fund for those and its owners",
            "PM-BOOK-006" to "what the book declares of a unit's persons is for the manager, the board and the controller",
        )
    }
}
