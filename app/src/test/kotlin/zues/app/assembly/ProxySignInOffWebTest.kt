package zues.app.assembly

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.identity_org.Logins
import zues.app.identity_org.PartyNames
import zues.app.identity_org.Succession
import zues.app.identity_org.WhoAsks
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.time.Clock
import java.util.UUID

/**
 * The proxy endpoints with sign-in switched off (`DOMUVAI_AUTH=off`, as the test task runs): nobody is asking, so
 * nobody is represented — even under a source of roles that would make anybody an owner; the list is still read.
 */
@WebMvcTest(ProxyController::class)
@Import(WhoAsks::class, Acting::class, AssemblyReads::class, Policy::class, AssemblyActs::class, ProxySignInOffWebTest.Generous::class)
class ProxySignInOffWebTest {

    class Generous {
        @Bean fun clock(): Clock = Clock.systemUTC()
        @Bean fun roles() = RoleSource { _, entrance, _ -> Role.entries.mapTo(mutableSetOf()) { Held(it, entrance, setOf(UUID.randomUUID())) } }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var proxies: ProxyService
    @MockitoBean lateinit var sittings: SittingService
    @MockitoBean lateinit var attendance: AttendanceService
    @MockitoBean lateinit var assemblies: AssemblyService
    @MockitoBean lateinit var notices: NoticeService
    @MockitoBean lateinit var repository: AssemblyRepository
    @MockitoBean lateinit var logins: Logins
    @MockitoBean lateinit var names: PartyNames
    @MockitoBean lateinit var succession: Succession

    @Test
    fun `PM-SEC-001 with sign-in switched off no proxy is appointed - whatever the body says - and the list is still read`() {
        val base = "/api/assembly/entrances/${UUID.randomUUID()}/assemblies/${UUID.randomUUID()}/proxies"
        val body = """{"principalPartyId":"${UUID.randomUUID()}","agentPartyId":"${UUID.randomUUID()}","agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"}"""
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(body.replace("{", """{"authorisationHash":"${"5d".repeat(32)}",""")))
            .andExpect(status().isForbidden)                                          // nor recorded from a document, by nobody
        whenever(proxies.forAssembly(any(), any())).thenReturn(emptyList())
        mvc.perform(get(base)).andExpect(status().isOk)
        verify(proxies).forAssembly(any(), any())
        verifyNoMoreInteractions(proxies)
    }

    @Test
    fun `PM-SEC-001 with sign-in switched off the proxies are served named - the book is asked for nobody, who it gives names and enters as open`() {
        val entrance = UUID.randomUUID()
        val (principal, agent) = listOf(UUID.randomUUID(), UUID.randomUUID())
        whenever(proxies.forAssembly(any(), any())).thenReturn(
            listOf(Proxy(UUID.randomUUID(), entrance, UUID.randomUUID(), principal, agent, "OWNER", "WHOLE_AGENDA", null, "WRITTEN", java.time.Instant.now(), "https://id.example.test", "login-p", principal, null)),
        )
        whenever(names.named(any(), any(), any(), any())).thenReturn(mapOf(principal to "Борис Недев", agent to "Ана Колева"))
        mvc.perform(get("/api/assembly/entrances/$entrance/assemblies/${UUID.randomUUID()}/proxies")).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].names.['$agent']").value("Ана Колева"))
        verify(names).named(entrance, Asking(null, null), setOf(principal, agent), NamedRead.PROXIES)
    }
}
