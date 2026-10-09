package zues.app.identity_org

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service
import zues.app.policy.Asking
import zues.app.policy.Login

/**
 * Who is asking, as the policy takes it (ADR-002): the login is the token's issuer and subject, and the party is the
 * one the api's own record ties that login to ([Logins]) — never a parameter, never a claim. With no token — sign-in
 * switched off — or a token naming no issuer or no subject, nobody is asking, and the policy allows nobody anything.
 */
@Service
class WhoAsks(private val logins: Logins) {

    fun of(signedIn: JwtAuthenticationToken?): Asking {
        val issuer = signedIn?.token?.getClaimAsString("iss")
        val subject = signedIn?.token?.subject
        if (issuer.isNullOrBlank() || subject.isNullOrBlank()) return Asking(null, null)
        return Asking(Login(issuer, subject), logins.partyOf(issuer, subject))
    }
}
