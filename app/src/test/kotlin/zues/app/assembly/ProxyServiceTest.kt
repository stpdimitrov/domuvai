package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.law.constantOn
import zues.app.policy.Asking
import zues.app.policy.Login
import zues.app.registry.BookAccessService
import zues.app.registry.BookHouseholdsAdapter
import zues.app.registry.HeldOn
import zues.app.registry.Holding
import zues.app.registry.Holdings
import zues.app.registry.Resident
import zues.app.registry.TitleRole
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
    /** The book as the registry's port answers it today: who owns, who holds a right of use, and who lives in whose unit. */
    private val owners = mutableMapOf<UUID, UUID>()                              // party → unit
    private val holdersOfUse = mutableMapOf<UUID, UUID>()
    private val living = mutableMapOf<UUID, UUID>()
    private val childrenUnder6 = mutableSetOf<UUID>()
    private val otherRows = mutableListOf<Resident>()                            // a second row of the book for a party already living somewhere
    private val book = object : Holdings {
        override fun inForce(entranceId: UUID, on: LocalDate, role: TitleRole) = HeldOn(
            (if (role == TitleRole.OWN) owners else holdersOfUse).takeIf { asked(entranceId, on) }.orEmpty()
                .map { (party, unit) -> Holding(party, unit, role.name, "1", "10", "10", "DECLARED") },
            emptyList(),
        )
        override fun residents(entranceId: UUID, on: LocalDate) =
            living.takeIf { asked(entranceId, on) }.orEmpty().map { (party, unit) -> Resident(party, unit, party in childrenUnder6) } + otherRows

        /** The book answers for this entrance today in Sofia, and for nothing else: the wrong entrance or day finds it empty. */
        private fun asked(entrance: UUID, on: LocalDate) = entrance == entranceId && on == LocalDate.parse("2026-10-09")
    }
    /** Whether the book lets the one registering be told; the entry and the decision are `BookHouseholdsTest`'s. */
    private var told = true
    private val access: BookAccessService = mock { on { tested(any(), any(), any(), any(), any()) } doAnswer { told } }
    private val service = ProxyService(aggregates, assemblies, agenda, proxies, book, BookHouseholdsAdapter(book, access), Clock.fixed(now, ZoneOffset.UTC))

    private fun actor(party: UUID) = Actor(party, "https://id.example.test/realms/domuvai", "login-$party")
    /** An owner of a unit of their own, living in it. */
    private fun owner(): UUID = UUID.randomUUID().also { owners[it] = UUID.randomUUID(); living[it] = owners.getValue(it) }

    private val agent = owner()

    private fun proxy(
        principal: UUID = owner(), by: UUID = agent, kind: String = "OWNER", scope: String = "WHOLE_AGENDA",
        items: List<Int> = emptyList(), form: String = "WRITTEN",
    ) = service.register(entranceId, assembly.id, RegisterProxy(principal, by, kind, scope, items, form), actor(principal), null)

    @Test
    fun `PM-GA-009 a proxy registration captures principal, agent, scope and form`() {
        val principal = owner()
        living[agent] = owners.getValue(principal)                                   // the agent lives in the principal's unit
        val whole = proxy(principal, kind = "HOUSEHOLD_MEMBER", form = "WRITTEN")
        assertThat(whole.loginSubject).isEqualTo("login-$principal")                 // the sign-in that appointed, on the record
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
        for (kind in listOf("HOUSEHOLD_MEMBER", "OWNER", "THIRD_PARTY")) {
            val principal = owner()
            val by = owner().also { living[it] = owners.getValue(principal) }        // an owner who lives in the principal's unit: any kind fits
            assertThat(proxy(principal, by = by, kind = kind).agentKind).isEqualTo(kind)
        }
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
    fun `PM-GA-009 the principal is an owner or a holder of use, another owner owns, and a household member lives in the principal's unit - as the book stands today`() {
        val stranger = UUID.randomUUID()
        val before = registered.size
        // the principal: an owner or a holder of a right of use — nobody else appoints
        assertThatThrownBy { proxy(principal = stranger) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-009")
        val holderOfUse = UUID.randomUUID().also { holdersOfUse[it] = UUID.randomUUID() }
        assertThat(proxy(principal = holderOfUse).principalPartyId).isEqualTo(holderOfUse)
        // "another owner" owns in the entrance: a holder of use, a resident and a stranger are not owners
        for (notAnOwner in listOf(stranger, UUID.randomUUID().also { holdersOfUse[it] = UUID.randomUUID() }, UUID.randomUUID().also { living[it] = UUID.randomUUID() })) {
            assertThatThrownBy { proxy(by = notAnOwner, kind = "OWNER") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("owns nothing")
        }
        // "a household member": of the principal's own household — a resident of another unit is not, nor is an owner who lives elsewhere
        val principal = owner()
        val nextDoor = UUID.randomUUID().also { living[it] = UUID.randomUUID() }
        for (notOfTheHousehold in listOf(stranger, nextDoor, owner())) {
            assertThatThrownBy { proxy(principal, by = notOfTheHousehold, kind = "HOUSEHOLD_MEMBER") }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("household")
        }
        val atHome = UUID.randomUUID().also { living[it] = owners.getValue(principal) }
        // the household is the principal's own: a landlord who does not live in the unit names no member of the household living there
        val landlord = owner().also { living.remove(it) }
        val lodger = UUID.randomUUID().also { living[it] = owners.getValue(landlord) }
        assertThatThrownBy { proxy(landlord, by = lodger, kind = "HOUSEHOLD_MEMBER") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("household")
        assertThat(proxy(landlord, by = lodger, kind = "THIRD_PARTY").agentKind).isEqualTo("THIRD_PARTY")       // … and appoints them as what they are
        assertThat(proxy(principal, by = atHome, kind = "HOUSEHOLD_MEMBER").agentPartyId).isEqualTo(atHome)
        // a third party is anybody
        assertThat(proxy(by = stranger, kind = "THIRD_PARTY").agentKind).isEqualTo("THIRD_PARTY")
        assertThat(registered).hasSize(before + 4)
    }

    private fun recorded(principal: UUID, by: UUID) = service.register(
        entranceId, assembly.id, RegisterProxy(principal, by, "HOUSEHOLD_MEMBER", "WHOLE_AGENDA", emptyList(), "WRITTEN"), actor(UUID.randomUUID()), "5d".repeat(32),
    )

    @Test
    fun `PM-GA-009 a child under six of the principal's home is no adult household member, in person or recorded - the same person unflagged is`() {
        val principal = owner()
        val child = UUID.randomUUID().also { living[it] = owners.getValue(principal); childrenUnder6 += it }
        assertThatThrownBy { proxy(principal, by = child, kind = "HOUSEHOLD_MEMBER") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("child under six").hasMessageContaining("PM-GA-009")
        assertThatThrownBy { recorded(principal, child) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("child under six").hasMessageContaining("PM-GA-009")
        assertThat(registered).isEmpty()

        childrenUnder6 -= child
        assertThat(proxy(principal, by = child, kind = "HOUSEHOLD_MEMBER").agentPartyId).isEqualTo(child)
        val otherPrincipal = owner()
        val grown = UUID.randomUUID().also { living[it] = owners.getValue(otherPrincipal) }
        assertThat(recorded(otherPrincipal, grown).agentPartyId).isEqualTo(grown)
    }

    @Test
    fun `PM-GA-009 a child of another home is refused as not of this household - the flag is told only of the principal's own home`() {
        val principal = owner()
        val nextDoor = UUID.randomUUID().also { living[it] = UUID.randomUUID(); childrenUnder6 += it }
        assertThatThrownBy { proxy(principal, by = nextDoor, kind = "HOUSEHOLD_MEMBER") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("household").hasMessageNotContaining("child")
        // flagged elsewhere and not at home: the home's row is what is asked, and an adult there is appointed
        val atHome = UUID.randomUUID().also { living[it] = owners.getValue(principal) }
        otherRows += Resident(atHome, UUID.randomUUID(), childUnder6 = true)
        assertThat(proxy(principal, by = atHome, kind = "HOUSEHOLD_MEMBER").agentPartyId).isEqualTo(atHome)
    }

    @Test
    fun `PM-GA-009 two rows of one home that disagree refuse the agent - the book's flag is not outvoted`() {
        val principal = owner()
        val home = owners.getValue(principal)
        val member = UUID.randomUUID().also { living[it] = home }
        otherRows += Resident(member, home, childUnder6 = true)
        assertThatThrownBy { proxy(principal, by = member, kind = "HOUSEHOLD_MEMBER") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("child under six")
        assertThat(registered).isEmpty()
    }

    @Test
    fun `PM-BOOK-006 a recorder the book does not let read gets one plain refusal, with no word of the flag or of who lives where`() {
        told = false
        val principal = owner()
        val child = UUID.randomUUID().also { living[it] = owners.getValue(principal); childrenUnder6 += it }
        val nextDoor = UUID.randomUUID().also { living[it] = UUID.randomUUID() }

        val refusals = listOf(child, nextDoor).map { agent ->
            assertThatThrownBy { recorded(principal, agent) }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("does not bear that out").hasMessageContaining("PM-GA-009").hasMessageNotContaining("child").hasMessageNotContaining("living")
            runCatching { recorded(principal, agent) }.exceptionOrNull()!!.message
        }
        assertThat(refusals.toSet()).hasSize(1)
        assertThat(registered).isEmpty()

        val grown = UUID.randomUUID().also { living[it] = owners.getValue(principal) }
        assertThat(recorded(principal, grown).agentPartyId).isEqualTo(grown)
    }

    @Test
    fun `PM-BOOK-006 a recorder the book lets read keeps today's two refusals`() {
        val principal = owner()
        val child = UUID.randomUUID().also { living[it] = owners.getValue(principal); childrenUnder6 += it }
        val nextDoor = UUID.randomUUID().also { living[it] = UUID.randomUUID() }

        assertThatThrownBy { recorded(principal, child) }.hasMessageContaining("child under six")
        assertThatThrownBy { recorded(principal, nextDoor) }.hasMessageContaining("does not have the two living in one of the principal's units")
    }

    @Test
    fun `PM-BOOK-007 a household member is tested once, as the sign-in, of the principal, today in Sofia`() {
        val principal = owner()
        val atHome = UUID.randomUUID().also { living[it] = owners.getValue(principal) }

        proxy(principal, by = atHome, kind = "HOUSEHOLD_MEMBER")

        val asking = Asking(Login("https://id.example.test/realms/domuvai", "login-$principal"), principal)
        verify(access).tested(entranceId, asking, principal, LocalDate.parse("2026-10-09"), BookHouseholdsAdapter.PURPOSE)

        // recorded from a document: the recorder is who asked, and the household is still the principal's
        val other = owner()
        val member = UUID.randomUUID().also { living[it] = owners.getValue(other) }
        val recorder = UUID.randomUUID()
        service.register(entranceId, assembly.id, RegisterProxy(other, member, "HOUSEHOLD_MEMBER", "WHOLE_AGENDA", emptyList(), "WRITTEN"), actor(recorder), "5d".repeat(32))

        val theRecorder = Asking(Login("https://id.example.test/realms/domuvai", "login-$recorder"), recorder)
        verify(access).tested(entranceId, theRecorder, other, LocalDate.parse("2026-10-09"), BookHouseholdsAdapter.PURPOSE)
    }

    @Test
    fun `PM-BOOK-007 a proxy of another kind asks nothing about residents and enters nothing`() {
        proxy(kind = "OWNER")
        proxy(by = UUID.randomUUID(), kind = "THIRD_PARTY")

        assertThat(registered).hasSize(2)
        verify(access, never()).tested(any(), any(), any(), any(), any())
    }

    @Test
    fun `PM-GA-009 the book is asked about this entrance, today in Sofia - an owner elsewhere or on another day is not one here`() {
        val principal = owner()
        val elsewhere = UUID.randomUUID()
        whenever(assemblies.lock(any(), any())).thenReturn(assembly.copy(entranceId = elsewhere))
        assertThatThrownBy { service.register(elsewhere, assembly.id, RegisterProxy(principal, agent, "OWNER", "WHOLE_AGENDA", emptyList(), "WRITTEN"), actor(principal), null) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-009")
        val tomorrow = ProxyService(aggregates, assemblies, agenda, proxies, book, BookHouseholdsAdapter(book, access), Clock.fixed(now.plusSeconds(86_400), ZoneOffset.UTC))
        whenever(assemblies.lock(any(), any())).thenReturn(assembly)
        assertThatThrownBy { tomorrow.register(entranceId, assembly.id, RegisterProxy(principal, agent, "OWNER", "WHOLE_AGENDA", emptyList(), "WRITTEN"), actor(principal), null) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-009")
        assertThat(registered).isEmpty()
    }

    @Test
    fun `somebody who already has their proxy learns nothing of the book by trying again - refused before it is asked`() {
        val principal = owner()
        proxy(principal)
        for (probe in listOf(owner(), UUID.randomUUID())) {
            assertThatThrownBy { proxy(principal, by = probe, kind = "OWNER") }.isInstanceOf(IllegalStateException::class.java)   // the same answer, an owner or not
        }
    }

    @Test
    fun `PM-GA-009 a proxy is appointed in person, or recorded from a written authorisation with its SHA-256 - never a principal named with nothing behind it`() {
        val principal = owner()
        val recorder = actor(UUID.randomUUID())
        val command = RegisterProxy(principal, agent, "OWNER", "WHOLE_AGENDA", emptyList(), "WRITTEN")
        val document = "5d".repeat(32)
        for (missing in listOf(null, "", "authorisation.pdf", "5D".repeat(32))) {
            assertThatThrownBy { service.register(entranceId, assembly.id, command, recorder, missing) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-009")
        }
        assertThatThrownBy { service.register(entranceId, assembly.id, command, actor(principal), document) }     // in person: no document
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(registered).isEmpty()

        val recorded = service.register(entranceId, assembly.id, command, recorder, document)
        assertThat(recorded.principalPartyId).isEqualTo(principal)
        assertThat(recorded.recordedBy).isEqualTo(recorder.party)
        assertThat(recorded.authorisationHash).isEqualTo(document)
        assertThat(recorded.loginSubject).isEqualTo("login-${recorder.party}")       // the recorder's sign-in, not the principal's
        val inPerson = proxy()
        assertThat(inPerson.recordedBy).isEqualTo(inPerson.principalPartyId)
        assertThat(inPerson.authorisationHash).isNull()
    }

    @Test
    fun `PM-GA-009 a recorded proxy is held to the same book - the principal named must hold a title, and the agent be who the kind says`() {
        val recorder = actor(UUID.randomUUID())
        val document = "5d".repeat(32)
        fun recorded(principal: UUID, by: UUID, kind: String) =
            service.register(entranceId, assembly.id, RegisterProxy(principal, by, kind, "WHOLE_AGENDA", emptyList(), "WRITTEN"), recorder, document)
        assertThatThrownBy { recorded(UUID.randomUUID(), agent, "OWNER") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-009")
        assertThatThrownBy { recorded(owner(), UUID.randomUUID(), "OWNER") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("owns nothing")
        assertThat(registered).isEmpty()
    }

    @Test
    fun `PM-GA-009 a principal has one proxy at an assembly`() {
        val principal = owner()
        proxy(principal)
        assertThatThrownBy { proxy(principal, by = owner()) }.isInstanceOf(IllegalStateException::class.java)
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
        repeat(max) {
            val principal = owner().also { p -> living[agent] = owners.getValue(p) }     // the agent lives where this principal's unit is, when asked
            proxy(principal, kind = kinds[it % kinds.size])
        }
        assertThatThrownBy { proxy(kind = "THIRD_PARTY") }.isInstanceOf(ProxyLimitReached::class.java)
    }

    @Test
    fun `PM-GA-010 a proxy is not passed on - a represented person represents nobody, and a representative is not represented`() {
        val absent = owner()
        proxy(principal = absent)                                                    // absent is represented by the agent
        assertThatThrownBy { proxy(by = absent) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("PM-GA-010")
        assertThatThrownBy { proxy(principal = agent, by = owner()) }      // the agent hands their principals to another
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("PM-GA-010")
        assertThat(registered).hasSize(1)
    }

    @Test
    fun `PM-GA-010 the limit is per agent - another agent is not affected`() {
        val max = constantOn("GA_PROXY_MAX_PRINCIPALS", "2026-11-20").value.toInt()
        repeat(max) { proxy() }
        assertThat(proxy(by = owner()).agentPartyId).isNotEqualTo(agent)
        assertThat(registered).hasSize(max + 1)
    }

    @Test
    fun `an assembly that is missing, or past its notice, takes no proxy`() {
        current = assembly.copy(status = "OPEN")
        assertThatThrownBy { proxy() }.isInstanceOf(IllegalStateException::class.java)
        current = assembly
        // through another entrance's path the assembly is not there: nothing is registered, nothing is listed
        val elsewhere = UUID.randomUUID()
        assertThatThrownBy { owner().let { p -> service.register(elsewhere, assembly.id, RegisterProxy(p, agent, "OWNER", "WHOLE_AGENDA", emptyList(), "WRITTEN"), actor(p), null) } }
            .isInstanceOf(NoSuchElementException::class.java)
        proxy()
        assertThatThrownBy { service.forAssembly(elsewhere, assembly.id) }.isInstanceOf(NoSuchElementException::class.java)
        assertThat(service.forAssembly(entranceId, assembly.id)).hasSize(1)
    }
}
