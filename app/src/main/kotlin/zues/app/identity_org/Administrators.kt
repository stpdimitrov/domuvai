package zues.app.identity_org

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
        return names(login.issuer, login.subject)
    }

    /** Whether the deployment names this login an administrator — whoever is asking. */
    fun names(issuer: String, subject: String): Boolean = this.issuer.isNotEmpty() && issuer == this.issuer && subject in subjects

    private companion object {
        val log = LoggerFactory.getLogger(Administrators::class.java)
    }
}
