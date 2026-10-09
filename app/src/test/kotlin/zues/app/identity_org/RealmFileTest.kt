package zues.app.identity_org

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI

/**
 * The realm as this repository describes it (ADR-011, `infra/keycloak`): the points the api's safety rests on, held
 * to the file. It reads the file only — no Keycloak runs here.
 */
class RealmFileTest {

    private val file = File("../infra/keycloak/domuvai-realm.json")
    private val realm: JsonNode = ObjectMapper().readTree(file)
    private val web: JsonNode = realm["clients"].single { it["clientId"].asText() == "domuvai-web" }

    @Test
    fun `the web signs in by the authorization-code flow with PKCE and by nothing else`() {
        assertThat(web["standardFlowEnabled"].asBoolean()).isTrue()
        assertThat(web["attributes"]["pkce.code.challenge.method"].asText()).isEqualTo("S256")
        for (other in listOf("implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled", "publicClient")) {
            assertThat(web[other].asBoolean()).`as`(other).isFalse()
        }
    }

    @Test
    fun `every access token carries the audience the api asks for, and the file carries no secret`() {
        val audiences = web["protocolMappers"].filter { it["protocolMapper"].asText() == "oidc-audience-mapper" }
        assertThat(audiences).hasSize(1)
        assertThat(audiences[0]["config"]["included.custom.audience"].asText()).isEqualTo("domuvai-api")
        assertThat(audiences[0]["config"]["access.token.claim"].asText()).isEqualTo("true")
        assertThat(file.readText().lowercase()).doesNotContain("secret", "password\"", "privatekey", "credentials")
    }

    @Test
    fun `nobody registers themselves, and the web is sent back only to this machine`() {
        assertThat(realm["registrationAllowed"].asBoolean()).isFalse()
        assertThat(realm["sslRequired"].asText()).isNotEqualTo("none")
        assertThat(realm["clients"].map { it["clientId"].asText() }).containsExactly("domuvai-web")
        val back = web["redirectUris"].map { it.asText() }
        assertThat(back).isNotEmpty()
        // the one path a sign-in comes back to (web/lib/auth/settings.ts) and nothing wider: no wildcard
        assertThat(back).allSatisfy {
            assertThat(URI(it).host).isIn("localhost", "127.0.0.1")
            assertThat(URI(it).path).isEqualTo("/auth/callback")
            assertThat(it).doesNotContain("*", "?", "#")
        }
    }

    @Test
    fun `a refresh token is not spent by its first use`() {
        // The web renews a session in its middleware, and a page with its prefetches renews more than once at the same
        // moment: with refresh tokens spent on first use, every renewal but one is refused and the person is signed out.
        assertThat(realm["revokeRefreshToken"].asBoolean()).isFalse()
    }
}
