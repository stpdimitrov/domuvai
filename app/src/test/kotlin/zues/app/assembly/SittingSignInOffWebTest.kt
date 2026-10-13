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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.identity_org.Logins
import zues.app.identity_org.WhoAsks
import zues.app.policy.Held
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Quorum and sittings with sign-in switched off: nobody is asking, so no sitting is weighed or opened — even under a
 * source of roles that would make anybody everything; the meter is still read.
 */
@WebMvcTest(SittingController::class)
@Import(WhoAsks::class, Acting::class, Policy::class, AssemblyActs::class, SittingSignInOffWebTest.Generous::class)
class SittingSignInOffWebTest {

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

    @Test
    fun `PM-SEC-001 with sign-in switched off no sitting is weighed or opened - and the meter is still read`() {
        val base = "/api/assembly/entrances/${UUID.randomUUID()}/assemblies/${UUID.randomUUID()}"
        mvc.perform(post("$base/sittings").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden)
        whenever(sittings.read(any(), any())).thenReturn(
            QuorumRead(Quorum(LocalDate.parse("2026-10-09"), BigDecimal.ZERO, BigDecimal("51"), "чл. 15 ал. 1 ЗУЕС", true, "GA_QUORUM_PCT@2009-01-01", emptyList(), false, ""), emptyList()),
        )
        mvc.perform(get("$base/quorum")).andExpect(status().isOk)
        verify(sittings).read(any(), any())
        verifyNoMoreInteractions(sittings)
    }
}
