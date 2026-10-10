package zues.app.identity_org

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.kernel.toSofiaDate
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** What the administrator's request came to: done, or the rule that refuses whoever asked. A refusal is an answer — its entry is kept. */
sealed interface LoginAnswer<out T> {
    data class Done<T>(val value: T) : LoginAnswer<T>
    data class Refused(val ruleId: String) : LoginAnswer<Nothing>
}

/** A login tied: which login, and the party it now is. */
data class LoginTied(val issuer: String, val subject: String, val partyId: UUID)

/** A login untied — and the party it was, or that it was tied to none. */
data class LoginUntied(val untied: Boolean, val partyId: UUID?)

/** A subject is an identifier, not a text: nothing longer is a login, and nothing longer is kept of a refused attempt. */
const val SUBJECT_MAX = 255

/** The record of who was said to be whom (Rule: PM-SEC-004): written once, never changed — the table sees to that. */
@Repository
class LoginActLog(private val jdbc: JdbcClient, private val clock: Clock) {

    // Rule: PM-SEC-004
    fun record(act: String, issuer: String, subject: String, partyId: UUID?, by: Asking, ruleId: String) {
        jdbc.sql(
            "INSERT INTO identity_org.login_act (id, act, login_issuer, login_subject, party_id, by_issuer, by_subject, rule_id, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).params(UUID.randomUUID(), act, issuer, subject, partyId, by.login?.issuer, by.login?.subject, ruleId, Timestamp.from(clock.instant())).update()
    }
}

/**
 * Saying who a signed-in person is (ADR-011), guarded (ADR-002): a login is tied to a party, and untied, only by the
 * deployment's administrator — the policy decides, as at today in Sofia, before anything else is looked at, and a
 * refused caller learns nothing else. Every tie, untie and refused attempt of a signed-in login is an entry that stays
 * (Rule: PM-SEC-004), written in the act's own transaction: no entry, no tie.
 */
@Service
class LoginAdministration(
    private val logins: Logins,
    private val policy: Policy,
    private val administrators: Administrators,
    private val acts: LoginActLog,
    private val clock: Clock,
) {
    /** Ties a login of the administrator's own issuer — the one the api is configured with — to a registered party. */
    // Rule: PM-SEC-001
    @Transactional
    fun tie(by: Asking, subject: String, partyId: UUID?): LoginAnswer<LoginTied> {
        val refusal = refusal(by)
        if (refusal != null) return refused(refusal, "TIE_REFUSED", by.login?.issuer.orEmpty(), subject, partyId, by)
        val issuer = by.login!!.issuer
        require(subject.isNotBlank() && subject.length <= SUBJECT_MAX) { "a login's subject is 1 to $SUBJECT_MAX characters" }
        requireNotNull(partyId) { "the party the login is tied to is required" }
        // An administrator says who others are and is nobody in the book: tied to a party, the login would hold that party's roles.
        require(!administrators.names(issuer, subject)) { "an administrator's login is not tied to a party" }
        logins.tie(issuer, subject, partyId)
        acts.record("TIED", issuer, subject, partyId, by, Action.TIE_LOGIN.governedBy)
        return LoginAnswer.Done(LoginTied(issuer, subject, partyId))
    }

    /**
     * Unties a login. The issuer is the administrator's own unless another is named — the only way to clear what a
     * changed issuer URL left behind. A login tied to none is answered so, and is not an entry: nothing was done.
     */
    // Rule: PM-SEC-001
    @Transactional
    fun untie(by: Asking, subject: String, issuer: String?): LoginAnswer<LoginUntied> {
        val refusal = refusal(by)
        if (refusal != null) return refused(refusal, "UNTIE_REFUSED", issuer ?: by.login?.issuer.orEmpty(), subject, null, by)
        val of = issuer?.trim()?.takeIf { it.isNotEmpty() } ?: by.login!!.issuer
        val was = logins.untie(of, subject) ?: return LoginAnswer.Done(LoginUntied(false, null))
        acts.record("UNTIED", of, subject, was, by, Action.TIE_LOGIN.governedBy)
        return LoginAnswer.Done(LoginUntied(true, was))
    }

    /** The rule that refuses this caller, or null when they may. Only a login can be allowed: there is nobody else to put on the record. */
    private fun refusal(by: Asking): String? {
        val decision = policy.decide(by, Action.TIE_LOGIN, Resource.Deployment, LocalDate.parse(toSofiaDate(clock.instant())))
        return if (decision.allowed && by.login != null) null else decision.ruleId
    }

    /** A refused attempt is entered as the login that made it; with nobody signed in there is nobody to enter, and nothing is written. */
    private fun <T> refused(ruleId: String, act: String, issuer: String, subject: String, partyId: UUID?, by: Asking): LoginAnswer<T> {
        if (by.login != null) acts.record(act, kept(issuer), kept(subject), partyId, by, ruleId)
        return LoginAnswer.Refused(ruleId)
    }

    /** What a refused caller sent, as it is kept: a line of ordinary characters, no longer than a subject. */
    private fun kept(text: String) = text.filterNot { it.isISOControl() }.take(SUBJECT_MAX)
}
