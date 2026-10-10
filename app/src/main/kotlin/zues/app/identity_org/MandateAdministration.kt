package zues.app.identity_org

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Login
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.kernel.toSofiaDate
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** A mandate to record, as the protocol gives it. Each field may be missing: what is missing is said only to the administrator. */
data class RecordMandate(
    val body: String? = null,
    val partyId: UUID? = null,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val protocolRef: String? = null,
)

/** The mandate recorded, and the mandates it succeeded — each ended on the day this one starts. */
data class MandateRecorded(val mandateId: UUID, val succeeded: List<UUID>)

/** The day a mandate was recorded as ended. */
data class MandateEnded(val mandateId: UUID, val on: LocalDate)

/** What the administrator's request came to: done, or the rule that refuses whoever asked. A refusal is an answer — its entry is kept. */
sealed interface MandateAnswer<out T> {
    data class Done<T>(val value: T) : MandateAnswer<T>
    data class Refused(val ruleId: String) : MandateAnswer<Nothing>
}

/** The mandate has an end already: the day is recorded once and not moved, so what was true of a past day stays true. */
class MandateAlreadyEnded(message: String) : RuntimeException(message)

/** The mandate would stand beside or behind one already recorded for the same office: a successor is recorded after what it succeeds. */
class MandateOutOfOrder(message: String) : RuntimeException(message)

/** A protocol reference is a line, as the table has it. */
const val PROTOCOL_REF_MAX = 500

/** This module's own table of mandates, written. Reading which count on a date is [MandateRoles]'. */
@Repository
class MandateStore(private val jdbc: JdbcClient) {

    /** Adds a mandate. The table's own word on it — an unregistered entrance or party, a mandate too long — comes back as the exception it raised. */
    fun add(id: UUID, entranceId: UUID, body: String, partyId: UUID, validFrom: LocalDate, validTo: LocalDate, protocolRef: String) {
        jdbc.sql("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to, protocol_ref) VALUES (?, ?, ?, ?, ?, ?, ?)")
            .params(id, entranceId, body, partyId, validFrom, validTo, protocolRef).update()
    }

    /** The entrance's mandates of these offices that have no end recorded and began before [before] — for [ranOutOnly], only those whose own dates have run out by then. */
    fun open(entranceId: UUID, bodies: Collection<String>, before: LocalDate, ranOutOnly: Boolean): List<UUID> =
        jdbc.sql(
            "SELECT id FROM identity_org.management_mandate WHERE entrance_id = :entrance AND body IN (:bodies) AND succeeded_at IS NULL " +
                "AND valid_from < :before AND (NOT :ranOutOnly OR valid_to <= :before) ORDER BY valid_from, id",
        ).param("entrance", entranceId).param("bodies", bodies).param("before", before).param("ranOutOnly", ranOutOnly).query(UUID::class.java).list()

    /** Whether the entrance has a mandate of these offices beginning on [from] or after. */
    fun beginsNoEarlier(entranceId: UUID, bodies: Collection<String>, from: LocalDate): Boolean =
        jdbc.sql("SELECT count(*) FROM identity_org.management_mandate WHERE entrance_id = :entrance AND body IN (:bodies) AND valid_from >= :from")
            .param("entrance", entranceId).param("bodies", bodies).param("from", from).query(Long::class.java).single() > 0

    /** A mandate of this entrance: its first day and the end recorded on it, if any — or null when the entrance has no such mandate. */
    fun find(entranceId: UUID, mandateId: UUID): Pair<LocalDate, LocalDate?>? =
        jdbc.sql("SELECT valid_from, succeeded_at FROM identity_org.management_mandate WHERE id = ? AND entrance_id = ?").params(mandateId, entranceId)
            .query { rs, _ -> rs.getObject("valid_from", LocalDate::class.java) to rs.getObject("succeeded_at", LocalDate::class.java) }.optional().orElse(null)

    /** Records the end, once: false when one is recorded already. */
    fun end(mandateId: UUID, on: LocalDate): Boolean =
        jdbc.sql("UPDATE identity_org.management_mandate SET succeeded_at = ? WHERE id = ? AND succeeded_at IS NULL").params(on, mandateId).update() == 1
}

/** One entry of the record of who was given which office (Rule: PM-SEC-004). */
data class MandateAct(
    val act: String,
    val entranceId: UUID,
    val by: Login,
    val ruleId: String,
    val mandateId: UUID? = null,
    val body: String? = null,
    val partyId: UUID? = null,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val onDay: LocalDate? = null,
    val succeededBy: UUID? = null,
    val protocolRef: String? = null,
)

/** The record of who was given which office: written once, never changed — the table sees to that. */
@Repository
class MandateActLog(private val jdbc: JdbcClient, private val clock: Clock) {

    // Rule: PM-SEC-004
    fun record(entry: MandateAct) {
        jdbc.sql(
            "INSERT INTO identity_org.mandate_act (id, act, entrance_id, mandate_id, body, party_id, valid_from, valid_to, on_day, succeeded_by, protocol_ref, by_issuer, by_subject, rule_id, at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).params(
            UUID.randomUUID(), entry.act, entry.entranceId, entry.mandateId, entry.body, entry.partyId, entry.validFrom, entry.validTo, entry.onDay,
            entry.succeededBy, entry.protocolRef, entry.by.issuer, entry.by.subject, entry.ruleId, Timestamp.from(clock.instant()),
        ).update()
    }
}

/**
 * Saying who holds an entrance's offices (ADR-002): a mandate is recorded, and its end, only by the deployment's
 * administrator — the policy decides, as at today in Sofia, before anything else is looked at, and a refused caller
 * learns nothing else. The mandate's own dates are the protocol's. Every record and end, the ends a new mandate
 * brings among them, and every refused attempt of a signed-in login is an entry that stays (Rule: PM-SEC-004),
 * written in the act's own transaction: no entry, no mandate.
 */
@Service
class MandateAdministration(
    private val mandates: MandateStore,
    private val policy: Policy,
    private val acts: MandateActLog,
    private val clock: Clock,
) {
    /**
     * Records a mandate, and ends the ones it succeeds on its first day (Rule: PM-GOV-004 — the incumbent continues
     * until a successor): a manager succeeds every open executive mandate before it; a board member succeeds an
     * earlier manager, and earlier board members whose own dates have run out — never a fellow member still in term.
     */
    // Rule: PM-SEC-001
    // Rule: PM-GOV-004
    @Transactional
    fun record(by: Asking, entranceId: UUID, request: RecordMandate): MandateAnswer<MandateRecorded> {
        val login = allowed(by, entranceId) ?: return refused(by, MandateAct("RECORD_REFUSED", entranceId, NOBODY, GOVERNED_BY, body = request.body?.let(::kept), partyId = request.partyId))
        val body = request.body?.takeIf { it in OFFICES } ?: throw IllegalArgumentException("the office is one of ${OFFICES.joinToString()}")
        val party = requireNotNull(request.partyId) { "the party the mandate is given to is required" }
        val from = requireNotNull(request.validFrom) { "the mandate's first day is required" }
        val to = requireNotNull(request.validTo) { "the day the mandate runs up to is required" }
        require(to.isAfter(from)) { "a mandate runs up to a day after its first" }
        val protocol = protocolOf(request.protocolRef) { "the protocol that gave the mandate is required, in at most $PROTOCOL_REF_MAX characters" }
        // A successor is recorded after what it succeeds, so that recording it ends the predecessor — never the other
        // way round, where nothing would. A sole manager stands beside no other executive mandate from the same day on;
        // a board member beside no manager's.
        val besideOrBehind = when (body) {
            "BM" -> mandates.beginsNoEarlier(entranceId, EXECUTIVE, from)
            "MB" -> mandates.beginsNoEarlier(entranceId, listOf("BM"), from)
            else -> false
        }
        if (besideOrBehind) throw MandateOutOfOrder("the entrance has an executive mandate from $from or later: mandates are recorded in the order they began")

        val id = UUID.randomUUID()
        try {
            mandates.add(id, entranceId, body, party, from, to, protocol)
        } catch (e: DataIntegrityViolationException) {
            throw explained(e, entranceId, party)
        }
        acts.record(MandateAct("RECORDED", entranceId, login, GOVERNED_BY, id, body, party, from, to, protocolRef = protocol))
        val succeeded = when (body) {
            "BM" -> mandates.open(entranceId, EXECUTIVE, from, ranOutOnly = false)
            "MB" -> mandates.open(entranceId, listOf("BM"), from, ranOutOnly = false) + mandates.open(entranceId, listOf("MB"), from, ranOutOnly = true)
            else -> emptyList()
        }.filter { it != id && mandates.end(it, from) }
        succeeded.forEach { acts.record(MandateAct("ENDED", entranceId, login, "PM-GOV-004", it, onDay = from, succeededBy = id)) }
        return MandateAnswer.Done(MandateRecorded(id, succeeded))
    }

    /** Records the day a mandate ended — once, a day not in the future, with the protocol or act that ended it. From that day it no longer counts; before it, it did. */
    // Rule: PM-SEC-001
    @Transactional
    fun end(by: Asking, entranceId: UUID, mandateId: UUID, on: LocalDate?, basis: String?): MandateAnswer<MandateEnded> {
        val login = allowed(by, entranceId) ?: return refused(by, MandateAct("END_REFUSED", entranceId, NOBODY, GOVERNED_BY, mandateId, onDay = on))
        val day = requireNotNull(on) { "the day the mandate ended is required" }
        // An end is something that happened: a day yet to come would park the mandate where no successor could end it.
        require(!day.isAfter(LocalDate.parse(toSofiaDate(clock.instant())))) { "the day a mandate ended is not in the future" }
        val protocol = protocolOf(basis) { "the protocol or act that ended the mandate is required, in at most $PROTOCOL_REF_MAX characters" }
        val (from, ended) = mandates.find(entranceId, mandateId) ?: throw NoSuchElementException("the entrance has no mandate $mandateId")
        if (ended != null) throw MandateAlreadyEnded("the mandate ended on $ended: the day is recorded once")
        require(!day.isBefore(from)) { "a mandate does not end before its first day" }
        if (!mandates.end(mandateId, day)) throw MandateAlreadyEnded("the mandate has an end recorded already")
        acts.record(MandateAct("ENDED", entranceId, login, GOVERNED_BY, mandateId, onDay = day, protocolRef = protocol))
        return MandateAnswer.Done(MandateEnded(mandateId, day))
    }

    /** The administrator's login when the policy allows them, or null. Only a login can be allowed: there is nobody else to put on the record. */
    private fun allowed(by: Asking, entranceId: UUID): Login? {
        val decision = policy.decide(by, Action.RECORD_MANDATE, Resource.OfEntrance(entranceId), LocalDate.parse(toSofiaDate(clock.instant())))
        return by.login?.takeIf { decision.allowed }
    }

    /** A refused attempt is entered as the login that made it; with nobody signed in there is nobody to enter, and nothing is written. */
    private fun <T> refused(by: Asking, attempt: MandateAct): MandateAnswer<T> {
        by.login?.let { acts.record(attempt.copy(by = it)) }
        return MandateAnswer.Refused(GOVERNED_BY)
    }

    /** A reference to a protocol as it is kept: a line of ordinary characters, required. */
    private fun protocolOf(given: String?, lacking: () -> String): String =
        given.orEmpty().filterNot { it.isISOControl() }.trim().also { require(it.isNotEmpty() && it.length <= PROTOCOL_REF_MAX, lacking) }

    /** What a refused caller sent, as it is kept: a line of ordinary characters, and short. */
    private fun kept(text: String) = text.filterNot { it.isISOControl() }.take(KEPT_MAX)

    /** What the table refused, said to the administrator: which reference is missing, or that the mandate is too long. Anything else is passed on. */
    private fun explained(e: DataIntegrityViolationException, entranceId: UUID, party: UUID): RuntimeException {
        val cause = e.rootCause as? SQLException ?: return e
        val text = cause.message.orEmpty()
        return when {
            cause.sqlState == FOREIGN_KEY_VIOLATION && "entrance_id" in text -> NoSuchElementException("no entrance $entranceId")
            cause.sqlState == FOREIGN_KEY_VIOLATION && "party_id" in text -> IllegalArgumentException("no party $party is registered")
            // The length is the table's check, and the law's number lives there and in the law module only.
            cause.sqlState == CHECK_VIOLATION && LENGTH_CHECK in text -> IllegalArgumentException("the mandate is longer than a mandate may be (PM-GOV-004)")
            else -> e
        }
    }

    private companion object {
        val OFFICES = listOf("BM", "MB", "CTL", "CSH")
        val EXECUTIVE = listOf("BM", "MB")
        val GOVERNED_BY = Action.RECORD_MANDATE.governedBy
        val NOBODY = Login("", "")
        const val KEPT_MAX = 64
        const val FOREIGN_KEY_VIOLATION = "23503"
        const val CHECK_VIOLATION = "23514"

        /** The unnamed checks of the table as first written — its dates' order and its length; the order is checked here before the table sees it. */
        const val LENGTH_CHECK = "management_mandate_check"
    }
}
