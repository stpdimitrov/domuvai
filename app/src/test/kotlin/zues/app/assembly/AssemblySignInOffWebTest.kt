package zues.app.assembly

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
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
import zues.app.identity_org.WhoAsks
import zues.app.policy.Held
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The assembly endpoints with sign-in switched off (`DOMUVAI_AUTH=off`, as the test task runs): nobody is asking. An
 * assembly, its agenda and its posting act are not undone and name who did them, so none is made with nobody
 * accountable — even under a source of roles that would give anybody every office; an assembly is still read.
 */
@WebMvcTest(AssemblyController::class)
@Import(WhoAsks::class, Acting::class, Policy::class, AssemblyActs::class, AssemblySignInOffWebTest.Generous::class)
class AssemblySignInOffWebTest {

    class Generous {
        @Bean fun clock(): Clock = Clock.systemUTC()
        @Bean fun roles() = RoleSource { _, entrance, _ -> Role.entries.mapTo(mutableSetOf()) { Held(it, entrance, setOf(UUID.randomUUID())) } }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var assemblies: AssemblyService
    @MockitoBean lateinit var notices: NoticeService
    @MockitoBean lateinit var proxies: ProxyService
    @MockitoBean lateinit var repository: AssemblyRepository
    @MockitoBean lateinit var logins: Logins

    private val entranceId = UUID.randomUUID()
    private val base = "/api/assembly/entrances/$entranceId/assemblies"

    @Test
    fun `PM-SEC-001 with sign-in switched off nothing is convened, put on an agenda, moved or posted - whatever the body says - and an assembly is still read`() {
        val anyone = UUID.randomUUID()
        val id = UUID.randomUUID()
        val at = Instant.parse("2026-11-20T17:00:00Z")
        for (request in listOf(
            post(base).content("""{"convenedBy":"$anyone","convenedAs":"BM","scheduledAt":"$at","place":"фоайето","mode":"IN_PERSON"}"""),
            post("$base/$id/agenda").content("""{"text":"Отчет","itemType":"GENERAL"}"""),
            post("$base/$id/schedule").content("""{"scheduledAt":"$at","place":"двора"}"""),
            post("$base/$id/notice-posting").content("""{"postedAt":"$at","coSignatoryPartyId":"$anyone","photoHash":"${"ab".repeat(32)}"}"""),
        )) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        }
        verifyNoInteractions(notices, repository)

        val assembly = Assembly(id, entranceId, anyone, "BM", at, "фоайето", "IN_PERSON", "DRAFT", false, null)
        whenever(assemblies.read(entranceId, id)).thenReturn(assembly to emptyList())
        mvc.perform(get("$base/$id")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("DRAFT"))
        verify(assemblies).read(any(), any())
        verifyNoMoreInteractions(assemblies)
    }
}
