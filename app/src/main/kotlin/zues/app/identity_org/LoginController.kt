package zues.app.identity_org

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Tie this login — a subject of the api's own issuer — to this registered party. */
data class TieLogin(val subject: String? = null, val partyId: UUID? = null)

/** Untie this login. The issuer is the api's own unless another is named: rows a changed issuer URL left behind. */
data class UntieLogin(val subject: String? = null, val issuer: String? = null)

/**
 * Who a signed-in person is, said by the deployment's administrator and by nobody else (PM-SEC-001). The administrator
 * is the login of the request's token, never a parameter; everybody else gets a 403. Re-tying — a recreated account
 * has a new subject — is an untie and a tie. Every act, and every refused attempt, is on the record (PM-SEC-004).
 */
@RestController
@RequestMapping("/api/identity/logins")
class LoginController(private val administration: LoginAdministration, private val who: WhoAsks) {

    @PostMapping
    fun tie(@RequestBody request: TieLogin, signedIn: JwtAuthenticationToken?): LoginTied =
        done(administration.tie(who.of(signedIn), request.subject.orEmpty(), request.partyId))

    @PostMapping("/untie")
    fun untie(@RequestBody request: UntieLogin, signedIn: JwtAuthenticationToken?): LoginUntied =
        done(administration.untie(who.of(signedIn), request.subject.orEmpty(), request.issuer))

    /** The refusal leaves the service as an answer, so its entry is kept; it becomes a 403 only here. */
    private fun <T> done(answer: LoginAnswer<T>): T = when (answer) {
        is LoginAnswer.Done -> answer.value
        is LoginAnswer.Refused -> throw LoginAdministrationForbidden(answer.ruleId)
    }

    /** Not the deployment's administrator → 403, naming the rule. Nobody signed in is refused the same way. */
    @ExceptionHandler(LoginAdministrationForbidden::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onForbidden(e: LoginAdministrationForbidden): Map<String, String> = mapOf("error" to "a login is tied and untied by the deployment's administrator", "rule" to e.ruleId)

    /** The login is tied already, or the party to another login of this issuer → 409: untie first. */
    @ExceptionHandler(LoginAlreadyTied::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onTied(e: LoginAlreadyTied): Map<String, String> = mapOf("error" to (e.message ?: "already tied"))

    /** No such party, no party named, a subject that is not one, or an administrator's own login → 400 — said only to the administrator. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid"))
}

/** The policy refused this caller; the entry of the refusal is already written. */
class LoginAdministrationForbidden(val ruleId: String) : RuntimeException("refused by $ruleId")
