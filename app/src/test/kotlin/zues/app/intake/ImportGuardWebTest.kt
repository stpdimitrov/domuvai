package zues.app.intake

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.IssuerKeys
import zues.app.identity_org.Logins
import zues.app.identity_org.WhoAsks
import zues.app.policy.Held
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID

private const val INTAKE_REALM = "https://id.example.test/realms/domuvai"

/**
 * The import path with sign-in configured: tokens the test signs are read by the application's decoder, the real
 * resolver, policy and guard decide over roles the test gives — only the import service is mocked. The import is the
 * book's entry (PM-BOOK-005): its entrance's manager or board, and nobody else, on the import's own entrance.
 */
@WebMvcTest(
    controllers = [ImportController::class, IntakeController::class],
    properties = ["domuvai.auth.issuer-uri=$INTAKE_REALM", "domuvai.auth.audience=domuvai-api", "domuvai.auth.mode=", "domuvai.auth.admins=operator"],
)
@Import(ImportGuard::class, WhoAsks::class, Policy::class, zues.app.identity_org.Administrators::class)
class ImportGuardWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val entrance: UUID = UUID.randomUUID()
        val otherEntrance: UUID = UUID.randomUUID()
        val manager: UUID = UUID.randomUUID(); val board: UUID = UUID.randomUUID(); val owner: UUID = UUID.randomUUID()
        val controller: UUID = UUID.randomUUID(); val cashier: UUID = UUID.randomUUID(); val nextDoor: UUID = UUID.randomUUID()
        val roles = mapOf(
            manager to (entrance to Role.BM), board to (entrance to Role.MB), owner to (entrance to Role.OWN),
            controller to (entrance to Role.CTL), cashier to (entrance to Role.CSH), nextDoor to (otherEntrance to Role.BM),
        )
    }

    @TestConfiguration
    class Fixed {
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }
        @Bean fun clock(): Clock = Clock.systemUTC()
        /** Who holds what, as the test says: each party one role in one entrance. */
        @Bean fun held() = RoleSource { who, at, _ ->
            roles[who.party]?.takeIf { it.first == at }?.let { setOf(Held(it.second, at, emptySet())) } ?: emptySet()
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var imports: ImportService
    @MockitoBean lateinit var logins: Logins

    private val importId = UUID.randomUUID()
    private val sheet = """{"period":"2026-05","legalDate":"2026-05-01","lines":[],"csv":"designation,ideal_parts,occupants,fee_minor"}"""
    private val stranger = UUID.randomUUID()                                                       // what a body says, never who acts

    private fun MockHttpServletRequestBuilder.by(subject: String, party: UUID?): MockHttpServletRequestBuilder {
        whenever(logins.partyOf(INTAKE_REALM, subject)).thenReturn(party)
        val token = SignedJWT(
            JWSHeader(JWSAlgorithm.RS256),
            JWTClaimsSet.Builder().subject(subject).issuer(INTAKE_REALM).audience("domuvai-api").expirationTime(Date.from(Instant.now().plusSeconds(300))).build(),
        ).apply { sign(RSASSASigner(issuer.private)) }.serialize()
        return header(HttpHeaders.AUTHORIZATION, "Bearer $token").contentType(MediaType.APPLICATION_JSON)
    }

    private fun stored(at: UUID = entrance) = whenever(imports.find(importId)).thenReturn(ImportRow(importId, at, "REPRODUCED", "sha", 2, 0, 0))

    @Test
    fun `PM-BOOK-005 the manager and the board record, read, commit and revert an import — and the record names them, whatever the body says`() {
        stored()
        whenever(imports.commit(eq(importId), any(), any())).thenReturn(CommitResult(importId, 2, 0, emptyList()))
        whenever(imports.revert(eq(importId), any(), any())).thenReturn(ImportRow(importId, entrance, "REVERTING", "sha", 2, 0, 0))
        for ((subject, party) in listOf("login-manager" to manager, "login-board" to board)) {
            mvc.perform(get("/api/intake/imports/$importId").by(subject, party)).andExpect(status().isOk)
            mvc.perform(post("/api/intake/imports/$importId/commit").by(subject, party).content("""{"committedBy":"$stranger","sheet":$sheet}""")).andExpect(status().is2xxSuccessful)
            verify(imports).commit(eq(importId), eq(party), any())
            mvc.perform(post("/api/intake/imports/$importId/revert").by(subject, party).content("""{"revertedBy":"$stranger","reason":"wrong sheet"}""")).andExpect(status().is2xxSuccessful)
            verify(imports).revert(importId, party, "wrong sheet")
        }
        verify(imports, never()).commit(any(), eq(stranger), any())
        verify(imports, never()).revert(any(), eq(stranger), any())
    }

    @Test
    fun `PM-BOOK-005 an owner, the controller, the cashier, another entrance's manager and a login tied to nobody touch no import — a 403, and nothing recorded, adopted or removed`() {
        stored()
        val refused = listOf(
            "login-owner" to owner, "login-controller" to controller, "login-cashier" to cashier, "login-next-door" to nextDoor, "login-of-nobody" to null,
            "operator" to null,                                                                     // the deployment's administrator: says who people are, enters no book
        )
        for ((subject, party) in refused) {
            mvc.perform(post("/api/intake/entrances/$entrance/imports").by(subject, party).content(sheet)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-BOOK-005"))
            mvc.perform(get("/api/intake/imports/$importId").by(subject, party)).andExpect(status().isForbidden)
            mvc.perform(post("/api/intake/imports/$importId/commit").by(subject, party).content("""{"committedBy":"$manager","sheet":$sheet}""")).andExpect(status().isForbidden)
            mvc.perform(post("/api/intake/imports/$importId/revert").by(subject, party).content("""{"revertedBy":"$manager","reason":"x"}""")).andExpect(status().isForbidden)
            mvc.perform(post("/api/intake/entrances/$entrance/fee-sheet/profile").by(subject, party).content("""{"csv":"designation"}""")).andExpect(status().isForbidden)
            mvc.perform(post("/api/intake/entrances/$entrance/fee-sheet/dry-run").by(subject, party).content(sheet)).andExpect(status().isForbidden)
        }
        verify(imports, never()).record(any(), any())
        verify(imports, never()).commit(any(), any(), any())
        verify(imports, never()).revert(any(), any(), any())
    }

    @Test
    fun `PM-SEC-001 an import is decided on its own entrance — another entrance's manager cannot revert it by naming their own in the path or the body`() {
        stored(at = otherEntrance)                                                                  // the import belongs to the next entrance
        mvc.perform(post("/api/intake/imports/$importId/revert").by("login-manager", manager).content("""{"revertedBy":"$manager","reason":"x"}""")).andExpect(status().isForbidden)
        mvc.perform(post("/api/intake/imports/$importId/commit").by("login-manager", manager).content("""{"committedBy":"$manager","sheet":$sheet}""")).andExpect(status().isForbidden)
        verify(imports, never()).revert(any(), any(), any())
        // … and the next entrance's own manager may
        whenever(imports.revert(eq(importId), any(), any())).thenReturn(ImportRow(importId, otherEntrance, "REVERTING", "sha", 2, 0, 0))
        mvc.perform(post("/api/intake/imports/$importId/revert").by("login-next-door", nextDoor).content("""{"reason":"x"}""")).andExpect(status().is2xxSuccessful)
        verify(imports).revert(importId, nextDoor, "x")
    }

    @Test
    fun `with no token, or one this api does not accept, nothing is asked — a 401`() {
        stored()
        val json = MediaType.APPLICATION_JSON
        mvc.perform(post("/api/intake/entrances/$entrance/imports").contentType(json).content(sheet)).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/intake/imports/$importId")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/intake/imports/$importId/commit").contentType(json).content("""{"committedBy":"$manager","sheet":$sheet}""")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/intake/imports/$importId/revert").contentType(json).content("""{"revertedBy":"$manager","reason":"x"}""")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/intake/entrances/$entrance/fee-sheet/profile").contentType(json).content("""{"csv":"designation"}""")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/intake/entrances/$entrance/fee-sheet/dry-run").contentType(json).content(sheet)).andExpect(status().isUnauthorized)
        verify(imports, never()).find(any())
        verify(imports, never()).revert(any(), any(), any())
    }
}
