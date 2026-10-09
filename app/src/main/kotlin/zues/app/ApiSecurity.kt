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

/**
 * Sign-in as the api sees it (ADR-011): where an issuer and an audience are configured, a request is let in only
 * with a token that issuer signed, in date, and issued for this api. The api never issues one (ADR-009), and the
 * token says who is asking, not what they may do (ADR-002). Provider-agnostic — standard OIDC discovery and keys;
 * the provider is Keycloak.
 */
data class ApiAuth(val issuer: String, val audience: String) {

    /** Whether sign-in is configured. Where it is not, the api is open — a machine of one's own, and it says so at start. */
    val required: Boolean get() = issuer.isNotBlank()

    init {
        // A realm signs tokens for every application in it: without an audience, one issued for another would be let in.
        require(issuer.isBlank() || audience.isNotBlank()) {
            "DOMUVAI_AUTH_ISSUER is set and DOMUVAI_AUTH_AUDIENCE is not: say which audience a token must be issued for"
        }
        require(issuer.isNotBlank() || audience.isBlank()) {
            "DOMUVAI_AUTH_AUDIENCE is set and DOMUVAI_AUTH_ISSUER is not: say which issuer signs the tokens"
        }
        if (!required) LoggerFactory.getLogger(ApiAuth::class.java).warn("the api is OPEN: no DOMUVAI_AUTH_ISSUER is set, so no request needs a token (ADR-011)")
    }

    /** The issuer's own checks — its name and the token's dates — and that the token was issued for this api. */
    fun tokenValidator(): OAuth2TokenValidator<Jwt> = DelegatingOAuth2TokenValidator(
        JwtValidators.createDefaultWithIssuer(issuer),
        OAuth2TokenValidator { token ->
            if (audience in token.audience.orEmpty()) OAuth2TokenValidatorResult.success()
            else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "the token was not issued for $audience", null))
        },
    )

    /** Reads the issuer's keys on the first token, not at start: the api starts whether or not the issuer is up yet. */
    fun decoder(): JwtDecoder = SupplierJwtDecoder {
        JwtDecoders.fromIssuerLocation<NimbusJwtDecoder>(issuer).also { it.setJwtValidator(tokenValidator()) }
    }
}
