package zues.app.assembly

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.identity_org.Logins
import zues.app.identity_org.Succession
import zues.app.identity_org.WhoAsks
import zues.app.policy.Held
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.time.Clock
import java.util.UUID

/**
 * The register's endpoints with sign-in switched off: nobody is asking, so nobody is entered or verified — even under a
 * source of roles that would make anybody everything; the register is still read.
 */
@WebMvcTest(AttendanceController::class)
@Import(WhoAsks::class, Acting::class, AssemblyReads::class, Policy::class, AssemblyActs::class, AttendanceSignInOffWebTest.Generous::class)
class AttendanceSignInOffWebTest {

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
    @MockitoBean lateinit var succession: Succession

    @Test
    fun `PM-SEC-001 with sign-in switched off nobody is entered in the register or verified - and the register is still read`() {
        val base = "/api/assembly/entrances/${UUID.randomUUID()}/assemblies/${UUID.randomUUID()}/attendance"
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content("""{"attendeePartyId":"${UUID.randomUUID()}","channel":"IN_PERSON"}"""))
            .andExpect(status().isForbidden)
        mvc.perform(post("$base/${UUID.randomUUID()}/identity-verification").contentType(MediaType.APPLICATION_JSON).content("""{"how":"камера"}"""))
            .andExpect(status().isForbidden)
        whenever(attendance.register(any(), any(), anyOrNull())).thenReturn(emptyList())
        mvc.perform(get(base)).andExpect(status().isOk)
        verify(attendance).register(any(), any(), anyOrNull())
        verifyNoMoreInteractions(attendance)
    }
}
