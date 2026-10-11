package zues.app.identity_org

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.RoleSource
import java.time.LocalDate
import java.util.UUID

/**
 * The deployment's administrators (Rule: PM-SEC-001 — SYS_ADMIN): the logins `DOMUVAI_AUTH_ADMINS` names, by subject —
 * the token's `sub`, which the issuer never reuses or lets a person choose; not a username — under the api's own issuer. Named in the deployment's configuration and never made through the api — nobody can
 * promote themselves. An administrator is a login, not a party: the operator need not be in anyone's book. With no
 * issuer there are no logins, so naming administrators then is a mistake, and the application does not start.
 *
 * And an administrator is nobody in the book, whichever came first: a login tied to a party never administers, named
 * or not — tied, it would hold that party's roles beside the administrator's, and "does not read the book" would be
 * words (ADR-002). The party's roles stay; the administrator's, which rest on a line of configuration, give way.
 */
@Service
class Administrators(
    @Value("\${domuvai.auth.issuer-uri:}") issuer: String,
    @Value("\${domuvai.auth.admins:}") admins: String,
) : RoleSource {
    private val issuer = issuer.trim()
    private val subjects = admins.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    init {
        require(subjects.isEmpty() || this.issuer.isNotEmpty()) {
            "DOMUVAI_AUTH_ADMINS is set and DOMUVAI_AUTH_ISSUER is not: an administrator is a login of the issuer, and there is none"
        }
        if (this.issuer.isNotEmpty()) log.info("{} administrator login(s) are named for this deployment (PM-SEC-001)", subjects.size)
    }

    /** An administrator holds nothing in an entrance: the role is the deployment's. */
    override fun held(who: Asking, entranceId: UUID, asAt: LocalDate): Set<Held> = emptySet()

    // Rule: PM-SEC-001
    override fun administers(who: Asking, asAt: LocalDate): Boolean {
        val login = who.login ?: return false
        return who.party == null && names(login.issuer, login.subject)
    }

    /** The named administrators that [partyOf] — the api's record of ties, asked by issuer and subject — says are tied to a party. The list itself stays here. */
    fun tiedAmong(partyOf: (String, String) -> UUID?): List<String> = subjects.filter { partyOf(issuer, it) != null }.sorted()

    /** Whether the deployment names this login an administrator — whoever is asking. */
    fun names(issuer: String, subject: String): Boolean = this.issuer.isNotEmpty() && issuer == this.issuer && subject in subjects

    private companion object {
        val log = LoggerFactory.getLogger(Administrators::class.java)
    }
}

/** An administrator's login is tied to a party: the two may not be one login, and the deployment says which before it serves anything. */
class AdministratorIsTied(message: String) : IllegalStateException(message)

/**
 * At start, the deployment's word against its own record (ADR-002): no login `DOMUVAI_AUTH_ADMINS` names may be tied to
 * a party. [Administrators.administers] already keeps such a login from administering; this makes the mistake seen
 * the moment it is made — a manager's subject added to the list, say — instead of leaving an administrator who is
 * quietly refused everything: **the application does not come up**, and names the subject. Untie it, or take it off
 * the list. Run once every bean exists and the schema is in place; if the record cannot be read, the application does
 * not come up either. The cost, accepted: whoever can write that table can keep the api from restarting — and whoever
 * can write that table already can do worse.
 */
@Component
class AdministratorsAreNobody(private val administrators: Administrators, private val logins: Logins) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        val tied = administrators.tiedAmong(logins::partyOf)
        if (tied.isNotEmpty()) {
            throw AdministratorIsTied(
                "DOMUVAI_AUTH_ADMINS names ${tied.size} login(s) tied to a party in the book: ${tied.joinToString()} — an administrator is nobody in the book. " +
                    "Untie the login, or take it off the list; the application does not start with both.",
            )
        }
    }
}
