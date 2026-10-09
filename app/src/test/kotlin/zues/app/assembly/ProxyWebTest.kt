package zues.app.assembly

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(ProxyController::class)
class ProxyWebTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var proxies: ProxyService

    private val entranceId = UUID.randomUUID()
    private val assemblyId = UUID.randomUUID()
    private val principal = UUID.randomUUID()
    private val agent = UUID.randomUUID()
    private val base = "/api/assembly/entrances/$entranceId/assemblies/$assemblyId/proxies"
    private val body = """{"principalPartyId":"$principal","agentPartyId":"$agent","agentKind":"THIRD_PARTY","scope":"LISTED_ITEMS","items":[2,3],"form":"NOTARISED"}"""
    private val stored = Proxy(
        UUID.randomUUID(), entranceId, assemblyId, principal, agent, "THIRD_PARTY", "LISTED_ITEMS", "2,3", "NOTARISED", Instant.parse("2026-10-09T09:00:00Z"),
    )

    @Test
    fun `PM-GA-009 POST registers a proxy and answers its principal, agent, scope and form`() {
        whenever(proxies.register(entranceId, assemblyId, RegisterProxy(principal, agent, "THIRD_PARTY", "LISTED_ITEMS", listOf(2, 3), "NOTARISED")))
            .thenReturn(stored)
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.principalPartyId").value(principal.toString()))
            .andExpect(jsonPath("$.agentPartyId").value(agent.toString()))
            .andExpect(jsonPath("$.agentKind").value("THIRD_PARTY"))
            .andExpect(jsonPath("$.scope").value("LISTED_ITEMS"))
            .andExpect(jsonPath("$.items[1]").value(3))
            .andExpect(jsonPath("$.form").value("NOTARISED"))

        whenever(proxies.forAssembly(entranceId, assemblyId)).thenReturn(listOf(stored.copy(scope = "WHOLE_AGENDA", scopeItems = null)))
        mvc.perform(get(base)).andExpect(status().isOk).andExpect(jsonPath("$[0].items").isEmpty)
    }

    @Test
    fun `PM-GA-010 a registration past the limit is a 409 that shows the limit`() {
        whenever(proxies.register(eq(entranceId), eq(assemblyId), any())).thenThrow(ProxyLimitReached(3, "чл. 14 ЗУЕС"))
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.limit").value(3))
            .andExpect(jsonPath("$.error").value("one person represents at most 3 owners at an assembly (чл. 14 ЗУЕС) — PM-GA-010"))
    }

    @Test
    fun `an unknown kind is a 400 and a missing assembly a 404, neither showing a limit`() {
        whenever(proxies.register(any(), any(), any())).thenThrow(IllegalArgumentException("unknown kind"))
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.limit").doesNotExist())
        whenever(proxies.forAssembly(any(), any())).thenThrow(NoSuchElementException("no assembly"))
        mvc.perform(get(base)).andExpect(status().isNotFound)
    }
}
