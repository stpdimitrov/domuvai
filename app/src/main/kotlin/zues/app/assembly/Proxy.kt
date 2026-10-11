package zues.app.assembly

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.conversion.DbActionExecutionException
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.registry.Holdings
import zues.app.registry.TitleRole
import zues.kernel.toSofiaDate
import zues.law.constantOn
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * An authorisation to represent someone at one assembly (Rule: PM-GA-009): who is represented, by whom,
 * what kind of representative that is, for which part of the agenda, and in what form it was given.
 */
@Table("proxy")
data class Proxy(
    @Id val id: UUID,
    val entranceId: UUID,
    val assemblyId: UUID,
    val principalPartyId: UUID,
    val agentPartyId: UUID,
    val agentKind: String,           // AgentKind
    val scope: String,               // ProxyScope
    val scopeItems: String?,         // the agenda ordinals, "2,3", when the scope is LISTED_ITEMS
    val form: String,                // AuthorisationForm
    val registeredAt: Instant,
    /** The sign-in that registered it, as tied to the principal at that moment (PM-SEC-001). */
    val loginIssuer: String? = null,
    val loginSubject: String? = null,
)

/** Who may be a proxy: an adult household member, another owner, or a third party. Rule: PM-GA-009 */
enum class AgentKind { HOUSEHOLD_MEMBER, OWNER, THIRD_PARTY }

enum class ProxyScope { WHOLE_AGENDA, LISTED_ITEMS }

/** The form the authorisation was given in. What each kind of agent needs is PM-GA-011's to judge. */
enum class AuthorisationForm { WRITTEN, NOTARISED, LAWYER_AUTHORISATION }

interface ProxyRepository : ListCrudRepository<Proxy, UUID> {
    fun findByAssemblyId(assemblyId: UUID): List<Proxy>
}

/** A proxy to register, as the service takes it: the principal is the sign-in's party, put here by the controller, never read from a request. */
data class RegisterProxy(
    val principalPartyId: UUID,
    val agentPartyId: UUID,
    val agentKind: String,
    val scope: String,
    val items: List<Int> = emptyList(),
    val form: String,
)

/** The agent already represents as many as one person may: the refusal carries the limit in force. */
class ProxyLimitReached(val limit: Int, val source: String) : IllegalStateException(
    "one person represents at most $limit principals at an assembly ($source) — PM-GA-010",
)

@Service
class ProxyService(
    private val aggregates: JdbcAggregateTemplate,
    private val assemblies: AssemblyRepository,
    private val agenda: AgendaItemRepository,
    private val proxies: ProxyRepository,
    private val holdings: Holdings,
    private val clock: Clock,
) {
    // Rule: PM-GA-009
    // Rule: PM-GA-010
    @Transactional
    fun register(entranceId: UUID, assemblyId: UUID, request: RegisterProxy, by: Actor): Proxy {
        require(by.party == request.principalPartyId) { "a proxy is appointed by the one it represents" }
        val kind = named<AgentKind>(request.agentKind, "kind of representative")
        val scope = named<ProxyScope>(request.scope, "scope")
        val form = named<AuthorisationForm>(request.form, "form of authorisation")
        require(request.principalPartyId != request.agentPartyId) { "nobody is their own proxy" }
        // the assembly's row is locked, so two registrations for one agent are counted one after the other
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        check(assembly.status in PROXIES_OPEN) { "an assembly that is ${assembly.status} takes no new proxies" }
        require((request.items as List<Int?>).none { it == null }) { "an agenda item is named by its number" }
        val items = request.items.distinct().sorted()
        if (scope == ProxyScope.LISTED_ITEMS) {
            require(items.isNotEmpty()) { "a proxy for listed items names at least one" }
            val onAgenda = agenda.findByAssemblyIdOrderByOrdinal(assemblyId).map { it.ordinal }
            require(onAgenda.containsAll(items)) { "the proxy names agenda items ${items - onAgenda.toSet()} that this assembly does not have" }
        } else {
            require(items.isEmpty()) { "a proxy for the whole agenda lists no items" }
        }
        requireWhoTheRuleNames(entranceId, request, kind)
        val registered = proxies.findByAssemblyId(assemblyId)
        check(registered.none { it.principalPartyId == request.principalPartyId }) { "this principal already has a proxy at this assembly" }
        // A represented person does not attend, so cannot represent; and nobody passes on what they were given —
        // otherwise one person stands for more than the limit through another's proxies.
        check(registered.none { it.principalPartyId == request.agentPartyId }) { "the representative is represented at this assembly, and cannot represent (PM-GA-010)" }
        check(registered.none { it.agentPartyId == request.principalPartyId }) { "the principal represents others at this assembly, and cannot be represented (PM-GA-010)" }
        // TODO(legal): PM-GA-010 — the number is unconfirmed, and so is what it counts: here, the principals (owners or
        // users alike) one party represents at one assembly, whatever kind of representative it is for each.
        val limit = constantOn("GA_PROXY_MAX_PRINCIPALS", toSofiaDate(assembly.scheduledAt))
        if (registered.count { it.agentPartyId == request.agentPartyId } >= limit.value.toInt()) {
            throw ProxyLimitReached(limit.value.toInt(), limit.source)
        }
        val proxy = Proxy(
            UUID.randomUUID(), entranceId, assemblyId, request.principalPartyId, request.agentPartyId, kind.name,
            scope.name, items.joinToString(",").ifEmpty { null }, form.name, clock.instant(), by.loginIssuer, by.loginSubject,
        )
        try {
            return aggregates.insert(proxy)
        } catch (e: DbActionExecutionException) {
            if (missingReference(e) != null) throw IllegalArgumentException("the principal or the representative is not a registered party")
            throw e
        }
    }

    /**
     * "An owner or user … MAY appoint a proxy: an adult household member, another owner, or a third party" — as the
     * book stands today in Sofia, through the registry's port: the principal holds a title in the entrance, an agent
     * called an owner owns in it, and an agent called a household member resides in a unit the principal holds.
     */
    // Rule: PM-GA-009
    private fun requireWhoTheRuleNames(entranceId: UUID, request: RegisterProxy, kind: AgentKind) {
        val today = LocalDate.parse(toSofiaDate(clock.instant()))                  // a Sofia calendar day (PM-SYS-004)
        val owned = holdings.inForce(entranceId, today, TitleRole.OWN).holdings
        val principals = (owned + holdings.inForce(entranceId, today, TitleRole.USR).holdings).filter { it.partyId == request.principalPartyId }
        require(principals.isNotEmpty()) { "a proxy is appointed by an owner or a holder of a right of use in the entrance (PM-GA-009)" }
        when (kind) {
            AgentKind.OWNER -> require(owned.any { it.partyId == request.agentPartyId }) { "the representative is called another owner, and owns nothing in the entrance today (PM-GA-009)" }
            AgentKind.HOUSEHOLD_MEMBER -> {
                // TODO(legal): PM-GA-009 — "adult": the book records no date of birth, so adulthood is not checked
                val units = principals.map { it.unitId }.toSet()
                require(holdings.residents(entranceId, today).any { it.partyId == request.agentPartyId && it.unitId in units }) {
                    "the representative is called a member of the principal's household, and the book has them in none of the principal's units today (PM-GA-009)"
                }
            }
            AgentKind.THIRD_PARTY -> Unit                                          // anybody, under a written authorisation: its form is PM-GA-011's
        }
    }

    @Transactional(readOnly = true)
    fun forAssembly(entranceId: UUID, assemblyId: UUID): List<Proxy> {
        assemblies.findById(assemblyId).filter { it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no assembly $assemblyId in entrance $entranceId") }
        return proxies.findByAssemblyId(assemblyId)
    }

    private inline fun <reified E : Enum<E>> named(value: String, what: String): E =
        enumValues<E>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("unknown $what \"$value\" — one of ${enumValues<E>().joinToString { it.name }}")
}

private val PROXIES_OPEN = setOf(AssemblyStatus.DRAFT.name, AssemblyStatus.NOTICED.name)
