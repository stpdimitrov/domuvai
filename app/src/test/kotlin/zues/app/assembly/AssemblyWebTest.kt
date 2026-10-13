package zues.app.assembly

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
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
import zues.law.Comparison
import zues.law.Denominator
import zues.law.MajorityRule
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Optional
import java.util.UUID

private const val ISSUER = "https://id.example.test/realms/domuvai"
private const val AUDIENCE = "domuvai-api"

/**
 * The assembly endpoints with sign-in configured, the real policy and the real guard ([AssemblyActs], [Acting],
 * [WhoAsks]) over mocked services — no database. Tokens are signed by a key of the test's own, so the application's
 * decoder reads them. Proves who acts is the party the api's record ties the login to — never a field of the request —
 * that the office stated is one they hold, and that whoever may not gets a 403 and reaches nothing.
 */
@WebMvcTest(AssemblyController::class, properties = ["domuvai.auth.issuer-uri=$ISSUER", "domuvai.auth.audience=$AUDIENCE", "domuvai.auth.mode="])
@Import(WhoAsks::class, Acting::class, Policy::class, AssemblyActs::class)
class AssemblyWebTest {

    companion object {
        val issuer: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val entranceId: UUID = UUID.randomUUID()
        val held = mutableMapOf<UUID, Set<Role>>()

        /** The day a person's roles begin, for those the test dates; everybody else has held theirs all along. */
        val since = mutableMapOf<UUID, LocalDate>()
    }

    @TestConfiguration
    class Fixed {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-09T09:00:00Z"), ZoneOffset.UTC)
        @Bean fun keys() = IssuerKeys { NimbusJwtDecoder.withPublicKey(issuer.public as RSAPublicKey).build() }

        /** Roles as the test says them, in the one entrance and nowhere else. */
        @Bean fun roles() = RoleSource { who, entrance, asAt ->
            if (entrance != entranceId || since[who.party]?.let { asAt.isBefore(it) } == true) emptySet()
            else held[who.party].orEmpty().mapTo(mutableSetOf()) { Held(it, entrance, setOf(UUID.randomUUID())) }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var assemblies: AssemblyService
    @MockitoBean lateinit var notices: NoticeService
    @MockitoBean lateinit var proxies: ProxyService
    @MockitoBean lateinit var sittings: SittingService
    @MockitoBean lateinit var attendance: AttendanceService
    @MockitoBean lateinit var repository: AssemblyRepository
    @MockitoBean lateinit var logins: Logins

    private val at = Instant.parse("2026-11-20T17:00:00Z")
    private val base = "/api/assembly/entrances/$entranceId/assemblies"

    private lateinit var manager: UUID
    private lateinit var controller: UUID
    private lateinit var owner: UUID

    @BeforeEach
    fun people() {
        manager = person("login-manager", Role.BM)
        controller = person("login-controller", Role.CTL)
        owner = person("login-owner", Role.OWN)
        person("login-cashier", Role.CSH)
        person("login-stranger")
    }

    private fun person(subject: String, vararg roles: Role): UUID = UUID.randomUUID().also {
        whenever(logins.partyOf(ISSUER, subject)).thenReturn(it)
        held[it] = roles.toSet()
    }

    private fun actor(party: UUID, subject: String) = Actor(party, ISSUER, subject)

    private fun MockHttpServletRequestBuilder.by(subject: String) = header(
        HttpHeaders.AUTHORIZATION,
        "Bearer " + SignedJWT(
            JWSHeader(JWSAlgorithm.RS256),
            JWTClaimsSet.Builder().subject(subject).issuer(ISSUER).audience(AUDIENCE).expirationTime(Date.from(Instant.now().plusSeconds(300))).build(),
        ).apply { sign(RSASSASigner(issuer.private)) }.serialize(),
    ).contentType(MediaType.APPLICATION_JSON)

    private fun convening(convenedAs: String, convenedBy: UUID? = null) =
        """{${convenedBy?.let { "\"convenedBy\":\"$it\"," }.orEmpty()}"convenedAs":"$convenedAs","scheduledAt":"$at","place":"фоайето","mode":"IN_PERSON","urgent":true,"urgencyReason":"спукана тръба"}"""

    private fun draft(convenedBy: UUID, convenedAs: String) = Assembly(
        UUID.randomUUID(), entranceId, convenedBy, convenedAs, at, "фоайето", "IN_PERSON", "DRAFT", true, "спукана тръба",
        convenedLoginIssuer = ISSUER, convenedLoginSubject = "login-of-the-convenor",
    )

    private fun stored(assembly: Assembly) = assembly.also { whenever(repository.findById(it.id)).thenReturn(Optional.of(it)) }

    @Test
    fun `PM-GA-002 an assembly is convened by the signed-in holder of the office stated - the body cannot name a convenor`() {
        val command = Convene(controller, "CTL", at, "фоайето", "IN_PERSON", true, "спукана тръба")
        whenever(assemblies.convene(entranceId, command, actor(controller, "login-controller"))).thenReturn(draft(controller, "CTL"))
        mvc.perform(post(base).by("login-controller").content(convening("CTL", convenedBy = manager)))     // the controller, naming the manager
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.convenedBy").value(controller.toString()))
            .andExpect(jsonPath("$.convenedAs").value("CTL"))
            .andExpect(jsonPath("$.urgent").value(true))
            .andExpect(jsonPath("$.urgencyReason").value("спукана тръба"))
        verify(assemblies).convene(entranceId, command, actor(controller, "login-controller"))
    }

    @Test
    fun `PM-GA-002 the office stated must be held today - a controller is not the manager, and a cashier, an owner and a stranger convene nothing`() {
        mvc.perform(post(base).by("login-controller").content(convening("BM"))).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-GA-002"))
        for (subject in listOf("login-cashier", "login-owner", "login-stranger")) {
            for (office in listOf("BM", "MB", "CTL", "CSH", "OWNERS")) {
                mvc.perform(post(base).by(subject).content(convening(office))).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-GA-002"))
            }
        }
        mvc.perform(post(base).by("login-tied-to-nobody").content(convening("BM"))).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        // an office holder stating a capacity that convenes nothing is told so; the owners' capacity is never taken from a request
        mvc.perform(post(base).by("login-manager").content(convening("OWNERS"))).andExpect(status().isBadRequest)
        mvc.perform(post(base).by("login-manager").content(convening("CSH"))).andExpect(status().isBadRequest)
        // and a manager of this entrance is nobody in another
        mvc.perform(post("/api/assembly/entrances/${UUID.randomUUID()}/assemblies").by("login-manager").content(convening("BM"))).andExpect(status().isForbidden)
        verifyNoInteractions(assemblies)
    }

    @Test
    fun `PM-GA-002 an assembly is run by the one who convened it, while they hold the office they convened in - no other office, owner or stranger touches its agenda or date`() {
        val assembly = stored(draft(manager, "BM"))
        val majority = MajorityRule("GENERAL", "50", Comparison.MORE_THAN, Denominator.REPRESENTED, "2009-01-01", "чл. 17 ЗУЕС", true, "PM-VOTE-002")
        val item = AgendaItem(UUID.randomUUID(), entranceId, assembly.id, 1, "Отчет", "GENERAL", majority.id)
        whenever(assemblies.addAgendaItem(entranceId, assembly.id, "Отчет", "GENERAL")).thenReturn(AgendaItemBound(item, majority, noticeVoided = true))
        whenever(assemblies.reschedule(entranceId, assembly.id, at, "двора")).thenReturn(assembly)
        whenever(assemblies.read(entranceId, assembly.id)).thenReturn(assembly to listOf(item))
        val agenda = """{"text":"Отчет","itemType":"GENERAL"}"""
        val move = """{"scheduledAt":"$at","place":"двора"}"""

        // the controller holds an office too — and cannot void the notice of what the manager convened
        for (subject in listOf("login-controller", "login-owner", "login-cashier", "login-stranger", "login-tied-to-nobody")) {
            mvc.perform(post("$base/${assembly.id}/agenda").by(subject).content(agenda)).andExpect(status().isForbidden)
            mvc.perform(post("$base/${assembly.id}/schedule").by(subject).content(move)).andExpect(status().isForbidden)
        }
        verifyNoInteractions(assemblies)

        mvc.perform(post("$base/${assembly.id}/agenda").by("login-manager").content(agenda))
            .andExpect(status().isCreated).andExpect(jsonPath("$.noticeVoided").value(true)).andExpect(jsonPath("$.majority.source").value("чл. 17 ЗУЕС"))
        mvc.perform(post("$base/${assembly.id}/schedule").by("login-manager").content(move)).andExpect(status().isOk)

        // another manager, while the convenor still holds the office, does not run it either
        person("login-second-manager", Role.BM)
        mvc.perform(post("$base/${assembly.id}/agenda").by("login-second-manager").content(agenda)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-GA-002"))

        // the convenor who has left the office, or holds another one now, no longer runs it
        held[manager] = setOf(Role.CTL)
        mvc.perform(post("$base/${assembly.id}/agenda").by("login-manager").content(agenda)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-GA-002"))
        held[manager] = emptySet()
        mvc.perform(post("$base/${assembly.id}/schedule").by("login-manager").content(move)).andExpect(status().isForbidden)
        mvc.perform(post("$base/${assembly.id}/notice-posting").by("login-manager").content("""{"postedAt":"$at","coSignatoryPartyId":"$owner","photoHash":"${"ab".repeat(32)}"}"""))
            .andExpect(status().isForbidden)
        verify(assemblies).addAgendaItem(entranceId, assembly.id, "Отчет", "GENERAL")      // once: the manager's, while the manager
        verify(assemblies).reschedule(entranceId, assembly.id, at, "двора")
        verifyNoInteractions(notices)
    }

    @Test
    fun `PM-GA-002 PM-GA-007 once the convenor has left the office their successor in it runs the assembly and signs the posting act - and no other office does`() {
        val assembly = stored(draft(manager, "BM"))
        val successor = person("login-successor", Role.BM)
        val agenda = """{"text":"Отчет","itemType":"GENERAL"}"""
        val posted = Instant.parse("2026-10-09T08:00:00Z")
        val photo = "ab".repeat(32)
        val posting = """{"postedAt":"$posted","coSignatoryPartyId":"$owner","photoHash":"$photo"}"""
        // while the manager who convened holds the office, the newcomer waits
        mvc.perform(post("$base/${assembly.id}/agenda").by("login-successor").content(agenda)).andExpect(status().isForbidden)
        mvc.perform(post("$base/${assembly.id}/notice-posting").by("login-successor").content(posting)).andExpect(status().isForbidden)
        verifyNoInteractions(assemblies, notices)

        held[manager] = setOf(Role.CTL)                                               // the convenor is the manager no more — the controller now, another office
        val majority = MajorityRule("GENERAL", "50", Comparison.MORE_THAN, Denominator.REPRESENTED, "2009-01-01", "чл. 17 ЗУЕС", true, "PM-VOTE-002")
        whenever(assemblies.addAgendaItem(entranceId, assembly.id, "Отчет", "GENERAL"))
            .thenReturn(AgendaItemBound(AgendaItem(UUID.randomUUID(), entranceId, assembly.id, 1, "Отчет", "GENERAL", majority.id), majority, false))
        whenever(notices.recordPosting(entranceId, assembly.id, actor(successor, "login-successor"), posted, owner, photo)).thenReturn(
            NoticePosting(UUID.randomUUID(), entranceId, assembly.id, posted, successor, owner, photo, at, "фоайето", "1. Отчет", posted, ISSUER, "login-successor"),
        )
        mvc.perform(post("$base/${assembly.id}/agenda").by("login-successor").content(agenda)).andExpect(status().isCreated)
        mvc.perform(post("$base/${assembly.id}/notice-posting").by("login-successor").content(posting))
            .andExpect(status().isCreated).andExpect(jsonPath("$.convenorPartyId").value(successor.toString()))
        // the controller is an office, and not this one; the former manager is nobody now
        for (subject in listOf("login-controller", "login-manager", "login-owner")) {
            mvc.perform(post("$base/${assembly.id}/agenda").by(subject).content(agenda)).andExpect(status().isForbidden)
            mvc.perform(post("$base/${assembly.id}/notice-posting").by(subject).content(posting)).andExpect(status().isForbidden)
        }
        verify(assemblies).addAgendaItem(entranceId, assembly.id, "Отчет", "GENERAL")
        verify(notices).recordPosting(entranceId, assembly.id, actor(successor, "login-successor"), posted, owner, photo)
        verifyNoMoreInteractions(notices)
    }

    @Test
    fun `PM-GA-007 whoever signs the posting act held the office on the day of posting - a successor does not certify a posting from before their time`() {
        val assembly = stored(draft(manager, "BM"))
        held[manager] = emptySet()
        val newcomer = person("login-newcomer", Role.BM).also { since[it] = LocalDate.parse("2026-10-09") }       // in office from today
        val photo = "ab".repeat(32)
        fun posting(at: Instant) = """{"postedAt":"$at","coSignatoryPartyId":"$owner","photoHash":"$photo"}"""
        val yesterday = Instant.parse("2026-10-08T08:00:00Z")
        mvc.perform(post("$base/${assembly.id}/notice-posting").by("login-newcomer").content(posting(yesterday)))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-GA-007"))
        verifyNoInteractions(notices)
        val today = Instant.parse("2026-10-09T08:00:00Z")
        whenever(notices.recordPosting(entranceId, assembly.id, actor(newcomer, "login-newcomer"), today, owner, photo)).thenReturn(
            NoticePosting(UUID.randomUUID(), entranceId, assembly.id, today, newcomer, owner, photo, at, "фоайето", "1. Отчет", today, ISSUER, "login-newcomer"),
        )
        mvc.perform(post("$base/${assembly.id}/notice-posting").by("login-newcomer").content(posting(today))).andExpect(status().isCreated)
    }

    @Test
    fun `PM-GA-003 an assembly the owners convened is run by the owner who convened it while they own - not another owner, an office, or an owner who is also the manager`() {
        val assembly = stored(draft(owner, "OWNERS"))
        person("login-other-owner", Role.OWN)
        person("login-owner-and-manager", Role.OWN, Role.BM)
        val agenda = """{"text":"Ремонт на покрива","itemType":"GENERAL"}"""
        for (subject in listOf("login-other-owner", "login-owner-and-manager", "login-manager", "login-controller")) {
            mvc.perform(post("$base/${assembly.id}/agenda").by(subject).content(agenda)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-GA-003"))
        }
        verify(assemblies, never()).addAgendaItem(any(), any(), any(), any())
        val majority = MajorityRule("GENERAL", "50", Comparison.MORE_THAN, Denominator.REPRESENTED, "2009-01-01", "чл. 17 ЗУЕС", true, "PM-VOTE-002")
        whenever(assemblies.addAgendaItem(entranceId, assembly.id, "Ремонт на покрива", "GENERAL"))
            .thenReturn(AgendaItemBound(AgendaItem(UUID.randomUUID(), entranceId, assembly.id, 1, "Ремонт на покрива", "GENERAL", majority.id), majority, false))
        mvc.perform(post("$base/${assembly.id}/agenda").by("login-owner").content(agenda)).andExpect(status().isCreated)
        // sold: the convening owner is an owner no more, and runs it no more
        held[owner] = emptySet()
        mvc.perform(post("$base/${assembly.id}/agenda").by("login-owner").content(agenda)).andExpect(status().isForbidden)
        // … not even if they have since become the manager: an owners' assembly is run as an owner
        held[owner] = setOf(Role.BM)
        mvc.perform(post("$base/${assembly.id}/agenda").by("login-owner").content(agenda)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-GA-003"))
        verify(assemblies).addAgendaItem(entranceId, assembly.id, "Ремонт на покрива", "GENERAL")      // the one time
    }

    @Test
    fun `PM-GA-006 once somebody is on the register the date, the place and the agenda no longer change - a 409 for the convenor too`() {
        val assembly = stored(draft(manager, "BM"))
        whenever(attendance.begun(assembly.id)).thenReturn(true)
        mvc.perform(post("$base/${assembly.id}/agenda").by("login-manager").content("""{"text":"Още една точка","itemType":"GENERAL"}""")).andExpect(status().isConflict)
        mvc.perform(post("$base/${assembly.id}/schedule").by("login-manager").content("""{"scheduledAt":"$at","place":"двора"}""")).andExpect(status().isConflict)
        // … nor once its quorum has been weighed, though nobody came
        whenever(attendance.begun(assembly.id)).thenReturn(false)
        whenever(sittings.weighed(assembly.id)).thenReturn(true)
        mvc.perform(post("$base/${assembly.id}/agenda").by("login-manager").content("""{"text":"Още една точка","itemType":"GENERAL"}""")).andExpect(status().isConflict)
        mvc.perform(post("$base/${assembly.id}/schedule").by("login-manager").content("""{"scheduledAt":"$at","place":"двора"}""")).andExpect(status().isConflict)
        verifyNoInteractions(assemblies)
    }

    @Test
    fun `an assembly convened before a convening named its sign-in is not carried on - a 409, not a failed write`() {
        val old = stored(draft(manager, "BM").copy(convenedLoginIssuer = null, convenedLoginSubject = null))
        mvc.perform(post("$base/${old.id}/agenda").by("login-manager").content("""{"text":"Отчет","itemType":"GENERAL"}""")).andExpect(status().isConflict)
        verifyNoInteractions(assemblies)
    }

    @Test
    fun `PM-GA-007 the posting act is recorded by the convenor signed in - the service is handed them, whoever the body names`() {
        val assembly = stored(draft(manager, "BM"))
        val coSignatory = UUID.randomUUID()
        val posted = Instant.parse("2026-10-09T08:00:00Z")
        val photo = "ab".repeat(32)
        whenever(notices.recordPosting(entranceId, assembly.id, actor(manager, "login-manager"), posted, coSignatory, photo)).thenReturn(
            NoticePosting(UUID.randomUUID(), entranceId, assembly.id, posted, manager, coSignatory, photo, at, "фоайето", "1. Отчет", posted, ISSUER, "login-manager"),
        )
        val body = """{"convenorPartyId":"$controller","postedAt":"$posted","coSignatoryPartyId":"$coSignatory","photoHash":"$photo"}"""
        mvc.perform(post("$base/${assembly.id}/notice-posting").by("login-manager").content(body))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.convenorPartyId").value(manager.toString()))
            .andExpect(jsonPath("$.coSignatoryPartyId").value(coSignatory.toString()))
            .andExpect(jsonPath("$.photoHash").value(photo))
            .andExpect(jsonPath("$.statedAgenda").value("1. Отчет"))
        // nobody records it for the convenor: not another office, an owner, a stranger — refused by the guard itself, before the service
        for (subject in listOf("login-controller", "login-owner", "login-stranger", "login-tied-to-nobody")) {
            mvc.perform(post("$base/${assembly.id}/notice-posting").by(subject).content(body)).andExpect(status().isForbidden)
        }
        verify(notices).recordPosting(entranceId, assembly.id, actor(manager, "login-manager"), posted, coSignatory, photo)
        verifyNoMoreInteractions(notices)
    }

    @Test
    fun `PM-SEC-001 a caller the policy allows nothing learns nothing - 403 whether or not the assembly exists - and one it allows finds a missing one missing`() {
        val missing = UUID.randomUUID()
        whenever(repository.findById(missing)).thenReturn(Optional.empty())
        val agenda = """{"text":"Отчет","itemType":"GENERAL"}"""
        mvc.perform(post("$base/$missing/agenda").by("login-stranger").content(agenda)).andExpect(status().isForbidden)
        verify(repository, never()).findById(any<UUID>())                                  // refused before anything is looked up
        mvc.perform(post("$base/$missing/agenda").by("login-manager").content(agenda)).andExpect(status().isNotFound)
        // an assembly of another entrance is not this entrance's to run
        val elsewhere = stored(draft(manager, "BM").copy(entranceId = UUID.randomUUID()))
        mvc.perform(post("$base/${elsewhere.id}/agenda").by("login-manager").content(agenda)).andExpect(status().isNotFound)
        verifyNoInteractions(assemblies)
    }

    @Test
    fun `PM-GA-004 PM-VOTE-004 what the service refuses keeps its status - too late a 409, pending counsel a 422, a bad request a 400 - and a read is served`() {
        val assembly = stored(draft(manager, "BM"))
        whenever(notices.recordPosting(any(), any(), any(), any(), any(), any())).thenThrow(NoticeTooLate("PM-GA-004"))
        mvc.perform(
            post("$base/${assembly.id}/notice-posting").by("login-manager")
                .content("""{"postedAt":"$at","coSignatoryPartyId":"${UUID.randomUUID()}","photoHash":"${"ab".repeat(32)}"}"""),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.error").value("PM-GA-004"))
        whenever(assemblies.addAgendaItem(any(), any(), any(), eq("COMMON_PART_USE_RIGHT"))).thenThrow(MajorityPending("TODO(legal): PM-VOTE-004"))
        mvc.perform(post("$base/${assembly.id}/agenda").by("login-manager").content("""{"text":"Покривът под наем","itemType":"COMMON_PART_USE_RIGHT"}"""))
            .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.error").value("TODO(legal): PM-VOTE-004"))
        whenever(assemblies.reschedule(any(), any(), any(), any())).thenThrow(IllegalArgumentException("PM-GA-004"))
        mvc.perform(post("$base/${assembly.id}/schedule").by("login-manager").content("""{"scheduledAt":"$at","place":"двора"}""")).andExpect(status().isBadRequest)

        whenever(assemblies.read(entranceId, assembly.id)).thenReturn(assembly to emptyList())
        mvc.perform(get("$base/${assembly.id}").by("login-stranger")).andExpect(status().isOk).andExpect(jsonPath("$.place").value("фоайето"))
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(convening("BM"))).andExpect(status().isUnauthorized)   // no token
    }
}
