package zues.app.identity_org

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Who is asking: the login's subject, and the registered party it is tied to — absent while it is tied to none. */
data class WhoIsAsking(val subject: String, val partyId: UUID?)

/**
 * The signed-in person, as the api knows them (ADR-011). The token says which login is asking; which party that is
 * comes from the api's own record ([Logins]), never from the caller and never from a claim. It says who, not what
 * they may do (ADR-002).
 */
@RestController
@RequestMapping("/api/identity")
class IdentityController(private val logins: Logins) {

    /** A 401 with no token — sign-in switched off, so nobody is asking. A login tied to no party is signed in and not yet registered. */
    @GetMapping("/me")
    fun me(signedIn: JwtAuthenticationToken?): ResponseEntity<WhoIsAsking> {
        val issuer = signedIn?.token?.getClaimAsString("iss")
        val subject = signedIn?.token?.subject
        if (issuer.isNullOrBlank() || subject.isNullOrBlank()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return ResponseEntity.ok(WhoIsAsking(subject, logins.partyOf(issuer, subject)))
    }
}
