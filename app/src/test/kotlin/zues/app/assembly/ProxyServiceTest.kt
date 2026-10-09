package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.law.constantOn
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Proxies with the repositories mocked; the "table" is a list, so each registration sees the ones before it. */
class ProxyServiceTest {

    private val now = Instant.parse("2026-10-09T09:00:00Z")
    private val entranceId = UUID.randomUUID()
    private val assembly = Assembly(
        UUID.randomUUID(), entranceId, UUID.randomUUID(), "BM", Instant.parse("2026-11-20T16:00:00Z"), "фоайето", "IN_PERSON", "DRAFT", false, null,
    )
    private var current: Assembly? = assembly
    private val registered = mutableListOf<Proxy>()

    private val aggregates: JdbcAggregateTemplate = mock {
        on { insert(any<Any>()) } doAnswer { (it.arguments[0] as Proxy).also { p -> registered += p } }
    }
    private val assemblies: AssemblyRepository = mock {
        on { lock(any(), any()) } doAnswer { current?.takeIf { a -> a.id == it.arguments[0] && a.entranceId == it.arguments[1] } }
        on { findById(any<UUID>()) } doAnswer { java.util.Optional.ofNullable(current?.takeIf { a -> a.id == it.arguments[0] }) }
    }
    private val agenda: AgendaItemRepository = mock {
        on { findByAssemblyIdOrderByOrdinal(any()) } doAnswer {
            (1..3).map { n -> AgendaItem(UUID.randomUUID(), entranceId, assembly.id, n, "т. $n", "GENERAL", "GENERAL@2009-01-01") }
        }
    }
    private val proxies: ProxyRepository = mock { on { findByAssemblyId(any()) } doAnswer { registered.toList() } }
    private val service = ProxyService(aggregates, assemblies, agenda, proxies, Clock.fixed(now, ZoneOffset.UTC))

    private val agent = UUID.randomUUID()

    private fun proxy(
        principal: UUID = UUID.randomUUID(), by: UUID = agent, kind: String = "OWNER", scope: String = "WHOLE_AGENDA",
        items: List<Int> = emptyList(), form: String = "WRITTEN",
    ) = service.register(entranceId, assembly.id, RegisterProxy(principal, by, kind, scope, items, form))

    @Test
    fun `PM-GA-009 a proxy registration captures principal, agent, scope and form`() {
        val principal = UUID.randomUUID()
        val whole = proxy(principal, kind = "HOUSEHOLD_MEMBER", form = "WRITTEN")
        assertThat(whole.principalPartyId).isEqualTo(principal)
        assertThat(whole.agentPartyId).isEqualTo(agent)
        assertThat(whole.agentKind).isEqualTo("HOUSEHOLD_MEMBER")
        assertThat(whole.scope).isEqualTo("WHOLE_AGENDA")
        assertThat(whole.scopeItems).isNull()
        assertThat(whole.form).isEqualTo("WRITTEN")
        assertThat(whole.assemblyId).isEqualTo(assembly.id)
        assertThat(whole.registeredAt).isEqualTo(now)

        val listed = proxy(by = UUID.randomUUID(), kind = "THIRD_PARTY", scope = "LISTED_ITEMS", items = listOf(3, 1, 3), form = "NOTARISED")
        assertThat(listed.scope).isEqualTo("LISTED_ITEMS")
        assertThat(listed.scopeItems).isEqualTo("1,3")
        assertThat(listed.form).isEqualTo("NOTARISED")
    }

    @Test
    fun `PM-GA-009 the agent is one of the rule's three kinds, and each of the four things captured is required to be a known one`() {
        for (kind in listOf("HOUSEHOLD_MEMBER", "OWNER", "THIRD_PARTY")) assertThat(proxy(by = UUID.randomUUID(), kind = kind).agentKind).isEqualTo(kind)
        val before = registered.size
        assertThatThrownBy { proxy(kind = "NEIGHBOUR") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("THIRD_PARTY")
        assertThatThrownBy { proxy(scope = "EVERYTHING") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { proxy(form = "ORAL") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { proxy(scope = "LISTED_ITEMS") }.isInstanceOf(IllegalArgumentException::class.java)                       // lists nothing
        assertThatThrownBy { proxy(scope = "LISTED_ITEMS", items = listOf(2, 4)) }.isInstanceOf(IllegalArgumentException::class.java) // no item 4
        assertThatThrownBy { proxy(scope = "WHOLE_AGENDA", items = listOf(1)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { proxy(principal = agent) }.isInstanceOf(IllegalArgumentException::class.java)                            // one's own proxy
        assertThat(registered).hasSize(before)
    }

    @Test
    fun `PM-GA-009 a principal has one proxy at an assembly`() {
        val principal = UUID.randomUUID()
        proxy(principal)
        assertThatThrownBy { proxy(principal, by = UUID.randomUUID()) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(registered).hasSize(1)
    }

    @Test
    fun `PM-GA-010 a registration past the configured limit for the same agent is rejected with the limit shown`() {
        val limit = constantOn("GA_PROXY_MAX_PRINCIPALS", "2026-11-20")
        assertThat(limit.verified).isFalse()                                         // TODO(legal): PM-GA-010
        val max = limit.value.toInt()
        repeat(max) { proxy() }                                                      // up to the limit: accepted
        assertThatThrownBy { proxy() }                                               // one more — the 4th while the limit is 3
            .isInstanceOf(ProxyLimitReached::class.java)
            .hasMessageContaining("at most $max").hasMessageContaining("чл. 14 ЗУЕС").hasMessageContaining("PM-GA-010")
            .extracting { (it as ProxyLimitReached).limit }.isEqualTo(max)
        assertThat(registered).hasSize(max)
    }

    @Test
    fun `PM-GA-010 the limit is on the person, whatever kind of representative they are for each principal`() {
        val max = constantOn("GA_PROXY_MAX_PRINCIPALS", "2026-11-20").value.toInt()
        val kinds = listOf("OWNER", "HOUSEHOLD_MEMBER", "THIRD_PARTY")
        repeat(max) { proxy(kind = kinds[it % kinds.size]) }
        assertThatThrownBy { proxy(kind = "THIRD_PARTY") }.isInstanceOf(ProxyLimitReached::class.java)
    }

    @Test
    fun `PM-GA-010 a proxy is not passed on - a represented person represents nobody, and a representative is not represented`() {
        val absent = UUID.randomUUID()
        proxy(principal = absent)                                                    // absent is represented by the agent
        assertThatThrownBy { proxy(by = absent) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("PM-GA-010")
        assertThatThrownBy { proxy(principal = agent, by = UUID.randomUUID()) }      // the agent hands their principals to another
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("PM-GA-010")
        assertThat(registered).hasSize(1)
    }

    @Test
    fun `PM-GA-010 the limit is per agent - another agent is not affected`() {
        val max = constantOn("GA_PROXY_MAX_PRINCIPALS", "2026-11-20").value.toInt()
        repeat(max) { proxy() }
        assertThat(proxy(by = UUID.randomUUID()).agentPartyId).isNotEqualTo(agent)
        assertThat(registered).hasSize(max + 1)
    }

    @Test
    fun `an assembly that is missing, or past its notice, takes no proxy`() {
        current = assembly.copy(status = "OPEN")
        assertThatThrownBy { proxy() }.isInstanceOf(IllegalStateException::class.java)
        current = assembly
        // through another entrance's path the assembly is not there: nothing is registered, nothing is listed
        val elsewhere = UUID.randomUUID()
        assertThatThrownBy { service.register(elsewhere, assembly.id, RegisterProxy(UUID.randomUUID(), agent, "OWNER", "WHOLE_AGENDA", emptyList(), "WRITTEN")) }
            .isInstanceOf(NoSuchElementException::class.java)
        proxy()
        assertThatThrownBy { service.forAssembly(elsewhere, assembly.id) }.isInstanceOf(NoSuchElementException::class.java)
        assertThat(service.forAssembly(entranceId, assembly.id)).hasSize(1)
    }
}
