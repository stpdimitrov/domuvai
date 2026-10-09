package zues.app

import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoders
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder
import java.net.URI

/** Where an issuer's signing keys come from. In the application: the issuer's own discovery document. A test hands over a key of its own. */
fun interface IssuerKeys {
    fun decoderFor(issuer: String): NimbusJwtDecoder
}

/**
 * Sign-in as the api sees it (ADR-011): a request is let in only with a token the configured issuer signed, in date,
 * and issued for this api. The api never issues one (ADR-009), and the token says who is asking, not what they may
 * do (ADR-002). Provider-agnostic — standard OIDC discovery and keys; the provider is Keycloak.
 *
 * Closed unless told otherwise: an api with no issuer does not start, unless it is switched off in so many words
 * (`DOMUVAI_AUTH=off`) — a machine of one's own, or a test. A deployment that loses its settings stays shut.
 */
class ApiAuth(issuer: String, audience: String, mode: String) {
    val issuer = issuer.trim()
    val audience = audience.trim()

    /** Whether a token is required. False only when sign-in was switched off in so many words. */
    val required: Boolean = this.issuer.isNotEmpty()

    init {
        val off = mode.trim().equals("off", ignoreCase = true)
        require(mode.isBlank() || off) { "DOMUVAI_AUTH is '$mode': the only value it takes is 'off'" }
        if (required) {
            require(!off) { "DOMUVAI_AUTH=off and DOMUVAI_AUTH_ISSUER are both set: either the api is open or it has an issuer, not both" }
            // A realm signs tokens for every application in it: without an audience, one issued for another would be let in.
            require(this.audience.isNotEmpty()) { "DOMUVAI_AUTH_ISSUER is set and DOMUVAI_AUTH_AUDIENCE is not: say which audience a token must be issued for" }
            val at = runCatching { URI(this.issuer) }.getOrNull()
            require(at?.scheme == "https" || (at?.scheme == "http" && at.host in LOCAL)) {
                "DOMUVAI_AUTH_ISSUER must be an https URL (http only for this machine): the signing keys are read from it"
            }
            log.info("the api requires a token from {} issued for {} (ADR-011)", this.issuer, this.audience)
        } else {
            require(this.audience.isEmpty()) { "DOMUVAI_AUTH_AUDIENCE is set and DOMUVAI_AUTH_ISSUER is not: say which issuer signs the tokens" }
            require(off) {
                "no sign-in is configured: set DOMUVAI_AUTH_ISSUER and DOMUVAI_AUTH_AUDIENCE, or DOMUVAI_AUTH=off to run the api open on a machine of your own"
            }
            log.warn("the api is OPEN: DOMUVAI_AUTH=off, so no request needs a token (ADR-011)")
        }
    }

    /** The issuer's own checks — its name and the token's dates — and that the token was issued for this api. */
    fun tokenValidator(): OAuth2TokenValidator<Jwt> = DelegatingOAuth2TokenValidator(
        JwtValidators.createDefaultWithIssuer(issuer),
        OAuth2TokenValidator { token ->
            if (audience in token.audience.orEmpty()) OAuth2TokenValidatorResult.success()
            else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "the token was not issued for $audience", null))
        },
    )

    /**
     * The one decoder the api uses: the issuer's keys, and always these checks on top. The keys are read on the first
     * token, not at start, so the api starts whether or not the issuer is up yet.
     */
    fun decoder(keys: IssuerKeys): JwtDecoder = SupplierJwtDecoder { keys.decoderFor(issuer).also { it.setJwtValidator(tokenValidator()) } }

    companion object {
        private val log = LoggerFactory.getLogger(ApiAuth::class.java)
        private val LOCAL = setOf("localhost", "127.0.0.1", "[::1]")

        /** The issuer's discovery document names its keys (standard OIDC). */
        val DISCOVERED = IssuerKeys { issuer -> JwtDecoders.fromIssuerLocation(issuer) }
    }
}
