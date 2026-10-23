package zues.app.assembly

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
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
import zues.app.identity_org.Succession
import zues.app.identity_org.WhoAsks
import zues.app.policy.Held
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.math.BigDecimal
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

private const val ISSUER = "https://id.example.test/realms/domuvai"
private const val AUDIENCE = "domuvai-api"

/**
 * The petition endpoints with sign-in configured, the real policy and the service mocked — no database. The test
 * stands in for the issuer with a key of its own, so the application's decoder reads the tokens and the real
 * [WhoAsks] says who is asking. Proves the party the service is handed is the one the api's record ties the token's
 * login to — never a field of the request — and that whoever the policy does not allow gets a 403 and reaches nothing.
 */
@WebMvcTest(PetitionController::class, properties = ["domuvai.auth.issuer-uri=$ISSUER", "domuvai.auth.audience=$AUDIENCE", "domuvai.auth.mode="])
@Import(WhoAsks::class, Acting::class, AssemblyReads::class, Policy::class)
class PetitionWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val entranceId: UUID = UUID.randomUUID()
        val owners = mutableSetOf<UUID>()
        val managers = mutableSetOf<UUID>()
    }

    @TestConfiguration
    class Fixed {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-09T09:00:00Z"), ZoneOffset.UTC)
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }

        /** Roles as the test says them: owners and managers of the one entrance, and nothing anywhere else. */
        @Bean fun roles() = RoleSource { who, entrance, _ ->
            if (entrance != entranceId) return@RoleSource emptySet()
            buildSet {
                if (who.party in owners) add(Held(Role.OWN, entrance, setOf(UUID.randomUUID())))
                if (who.party in managers) add(Held(Role.BM, entrance))
            }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var petitions: PetitionService
    @MockitoBean lateinit var logins: Logins
    @MockitoBean lateinit var succession: Succession

    private val at = Instant.parse("2026-10-09T09:00:00Z")
    private val base = "/api/assembly/entrances/$entranceId/petitions"

    /** A person the api knows: a login tied to a party, who is an owner, the manager, or neither. */
    private fun person(subject: String, owner: Boolean = false, manager: Boolean = false): UUID = UUID.randomUUID().also {
        whenever(logins.partyOf(ISSUER, subject)).thenReturn(it)
        if (owner) owners += it
        if (manager) managers += it
    }

    private fun MockHttpServletRequestBuilder.by(subject: String) = header(
        HttpHeaders.AUTHORIZATION,
        "Bearer " + SignedJWT(
            JWSHeader(JWSAlgorithm.RS256),
            JWTClaimsSet.Builder().subject(subject).issuer(ISSUER).audience(AUDIENCE).expirationTime(Date.from(Instant.now().plusSeconds(300))).build(),
        ).apply { sign(RSASSASigner(issuer.private)) }.serialize(),
    )

    private fun actor(party: UUID, subject: String) = Actor(party, ISSUER, subject)

    private fun petition(openedBy: UUID) = Petition(UUID.randomUUID(), entranceId, openedBy, "Ремонт на покрива", at)

    private fun weighed(petition: Petition, held: String, cannotWeigh: List<String> = emptyList()) = PetitionRead(
        petition, listOf(petition.openedBy),
        PetitionWeight(
            LocalDate.parse("2026-10-09"), BigDecimal(held), BigDecimal("20"), "чл. 12 ЗУЕС", false, "GA_PETITION_MIN_PCT@2009-01-01", cannotWeigh, false,
            setOf(petition.openedBy),
        ),
        null,
    )

    private val meeting = """"scheduledAt":"2026-11-20T16:00:00Z","place":"фоайето","mode":"IN_PERSON","demandUnmet":"не е свикано""""

    @Test
    fun `PM-GA-003 a petition is opened and signed by the signed-in owner - the body cannot name anyone else`() {
        val ana = person("login-ana", owner = true)
        val boris = person("login-boris", owner = true)
        val opened = petition(ana)
        whenever(petitions.open(entranceId, actor(ana, "login-ana"), "Ремонт на покрива")).thenReturn(opened)
        whenever(petitions.read(entranceId, opened.id)).thenReturn(weighed(opened, "8.5"))

        // Ana opens, and tries to open as Boris: the service is handed Ana
        mvc.perform(post(base).by("login-ana").contentType(MediaType.APPLICATION_JSON).content("""{"openedBy":"$boris","subject":"Ремонт на покрива"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.openedBy").value(ana.toString()))
            .andExpect(jsonPath("$.heldPct").value("8.5"))
            .andExpect(jsonPath("$.thresholdPct").value("20"))
            .andExpect(jsonPath("$.thresholdSource").value("чл. 12 ЗУЕС"))
            .andExpect(jsonPath("$.thresholdVerified").value(false))
            .andExpect(jsonPath("$.unlocked").value(false))
        verify(petitions).open(entranceId, actor(ana, "login-ana"), "Ремонт на покрива")

        // Ana signs "as Boris": the signature is Ana's own
        mvc.perform(post("$base/${opened.id}/signatures").by("login-ana").contentType(MediaType.APPLICATION_JSON).content("""{"partyId":"$boris"}"""))
            .andExpect(status().isCreated)
        verify(petitions).sign(entranceId, opened.id, actor(ana, "login-ana"))
        // Boris signs with no body at all
        mvc.perform(post("$base/${opened.id}/signatures").by("login-boris")).andExpect(status().isCreated)
        verify(petitions).sign(entranceId, opened.id, actor(boris, "login-boris"))
    }

    @Test
    fun `PM-GA-003 the owners' assembly is convened by the signed-in signatory - the body cannot name a convenor`() {
        val ana = person("login-ana-2", owner = true)
        val boris = person("login-boris-2", owner = true)
        val opened = petition(ana)
        val request = ConveneOnPetition(Instant.parse("2026-11-20T16:00:00Z"), "фоайето", "IN_PERSON", "не е свикано")
        whenever(petitions.convene(entranceId, opened.id, actor(ana, "login-ana-2"), request)).thenReturn(
            Assembly(UUID.randomUUID(), entranceId, ana, "OWNERS", request.scheduledAt, "фоайето", "IN_PERSON", "DRAFT", false, null, petitionId = opened.id) to
                PetitionUnlock(
                    opened.id, entranceId, ana, "не е свикано", LocalDate.parse("2026-10-09"), BigDecimal("21.7500000000"), BigDecimal("20"),
                    "GA_PETITION_MIN_PCT@2009-01-01", false, "1.3", "0.3.0", at, ISSUER, "login-ana-2",
                ),
        )
        mvc.perform(post("$base/${opened.id}/assembly").by("login-ana-2").contentType(MediaType.APPLICATION_JSON).content("""{"convenedBy":"$boris",$meeting}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.convenedAs").value("OWNERS"))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.heldPct").value("21.75"))
            .andExpect(jsonPath("$.thresholdPct").value("20"))
        verify(petitions).convene(entranceId, opened.id, actor(ana, "login-ana-2"), request)
    }

    @Test
    fun `PM-SEC-001 whoever is not an owner of the entrance is a 403 naming the rule, and reaches nothing - the manager, a stranger, a login tied to no party`() {
        person("login-manager", manager = true)
        person("login-stranger")
        val id = UUID.randomUUID()
        for (subject in listOf("login-manager", "login-stranger", "login-tied-to-nobody")) {
            for (request in listOf(
                post(base).content("""{"subject":"Ремонт на покрива"}"""),
                post("$base/$id/signatures"),
                post("$base/$id/assembly").content("{$meeting}"),
            )) {
                mvc.perform(request.by(subject).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.rule").value(if (subject == "login-tied-to-nobody") "PM-SEC-001" else "PM-GA-003"))
            }
        }
        // an owner of this entrance is nobody in another
        val ana = person("login-ana-3", owner = true)
        mvc.perform(post("/api/assembly/entrances/${UUID.randomUUID()}/petitions").by("login-ana-3").contentType(MediaType.APPLICATION_JSON).content("""{"subject":"Чуждо"}"""))
            .andExpect(status().isForbidden)
        verifyNoInteractions(petitions)
        assert(ana in owners)
    }

    @Test
    fun `PM-GA-003 a petition that cannot be weighed says why, and convening on it is a 409 with the reason`() {
        val ana = person("login-ana-4", owner = true)
        val opened = petition(ana)
        val why = "unit 7 has both an owner and a holder of a right of use — whose ideal parts count is not settled (TODO(legal): PM-GA-003)"
        whenever(petitions.read(entranceId, opened.id)).thenReturn(weighed(opened, "0", listOf(why)))
        mvc.perform(get("$base/${opened.id}").by("login-ana-4"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.unlocked").value(false))
            .andExpect(jsonPath("$.cannotWeigh[0]").value(why))

        whenever(petitions.convene(eq(entranceId), eq(opened.id), eq(actor(ana, "login-ana-4")), any())).thenThrow(PetitionLocked("the petition cannot be weighed: $why"))
        mvc.perform(post("$base/${opened.id}/assembly").by("login-ana-4").contentType(MediaType.APPLICATION_JSON).content("{$meeting}"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.error").value("the petition cannot be weighed: $why"))
    }

    @Test
    fun `PM-SEC-001 a petition is read as an assembly is - by an owner or an office of the entrance - a stranger, a login tied to nobody and an owner elsewhere are a 403 naming the rule, and nothing is read`() {
        val ana = person("login-ana-6", owner = true)
        person("login-manager-6", manager = true)
        person("login-stranger-6")
        val opened = petition(ana)
        for (subject in listOf("login-stranger-6", "login-tied-to-nobody")) {
            mvc.perform(get("$base/${opened.id}").by(subject)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        }
        mvc.perform(get("/api/assembly/entrances/${UUID.randomUUID()}/petitions/${opened.id}").by("login-ana-6")).andExpect(status().isForbidden)
        verifyNoInteractions(petitions)
        whenever(petitions.read(entranceId, opened.id)).thenReturn(weighed(opened, "8.5", emptyList()))
        for (subject in listOf("login-ana-6", "login-manager-6")) {
            mvc.perform(get("$base/${opened.id}").by(subject)).andExpect(status().isOk).andExpect(jsonPath("$.signatories[0]").value(ana.toString()))
        }
    }

    @Test
    fun `no token is a 401, a missing petition a 404, and a signature the service refuses a 400`() {
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content("""{"subject":"Ремонт на покрива"}""")).andExpect(status().isUnauthorized)
        val ana = person("login-ana-5", owner = true)
        whenever(petitions.sign(any(), any(), eq(actor(ana, "login-ana-5")))).thenThrow(IllegalArgumentException("only an owner"))
        mvc.perform(post("$base/${UUID.randomUUID()}/signatures").by("login-ana-5")).andExpect(status().isBadRequest)
        whenever(petitions.read(any(), any())).thenThrow(NoSuchElementException("no petition"))
        mvc.perform(get("$base/${UUID.randomUUID()}").by("login-ana-5")).andExpect(status().isNotFound)
    }
}
