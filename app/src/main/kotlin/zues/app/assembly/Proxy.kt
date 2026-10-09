package zues.app.assembly

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.conversion.DbActionExecutionException
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.kernel.toSofiaDate
import zues.law.constantOn
import java.time.Clock
import java.time.Instant
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
)

/** Who may be a proxy: an adult household member, another owner, or a third party. Rule: PM-GA-009 */
enum class AgentKind { HOUSEHOLD_MEMBER, OWNER, THIRD_PARTY }

enum class ProxyScope { WHOLE_AGENDA, LISTED_ITEMS }

/** The form the authorisation was given in. What each kind of agent needs is PM-GA-011's to judge. */
enum class AuthorisationForm { WRITTEN, NOTARISED, LAWYER }

interface ProxyRepository : ListCrudRepository<Proxy, UUID> {
    fun findByAssemblyId(assemblyId: UUID): List<Proxy>
}

/** What is stated when a proxy is registered. */
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
    "one person represents at most $limit owners at an assembly ($source) — PM-GA-010",
)

@Service
class ProxyService(
    private val aggregates: JdbcAggregateTemplate,
    private val assemblies: AssemblyRepository,
    private val agenda: AgendaItemRepository,
    private val proxies: ProxyRepository,
    private val clock: Clock,
) {
    // Rule: PM-GA-009
    // Rule: PM-GA-010
    @Transactional
    fun register(entranceId: UUID, assemblyId: UUID, request: RegisterProxy): Proxy {
        val kind = named<AgentKind>(request.agentKind, "kind of representative")
        val scope = named<ProxyScope>(request.scope, "scope")
        val form = named<AuthorisationForm>(request.form, "form of authorisation")
        require(request.principalPartyId != request.agentPartyId) { "nobody is their own proxy" }
        // the assembly's row is locked, so two registrations for one agent are counted one after the other
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        check(assembly.status in PROXIES_OPEN) { "an assembly that is ${assembly.status} takes no new proxies" }
        val items = request.items.distinct().sorted()
        if (scope == ProxyScope.LISTED_ITEMS) {
            require(items.isNotEmpty()) { "a proxy for listed items names at least one" }
            val onAgenda = agenda.findByAssemblyIdOrderByOrdinal(assemblyId).map { it.ordinal }
            require(onAgenda.containsAll(items)) { "the proxy names agenda items ${items - onAgenda.toSet()} that this assembly does not have" }
        } else {
            require(items.isEmpty()) { "a proxy for the whole agenda lists no items" }
        }
        val registered = proxies.findByAssemblyId(assemblyId)
        check(registered.none { it.principalPartyId == request.principalPartyId }) { "this principal already has a proxy at this assembly" }
        val limit = constantOn("GA_PROXY_MAX_PRINCIPALS", toSofiaDate(assembly.scheduledAt))   // TODO(legal): PM-GA-010 — unconfirmed
        if (registered.count { it.agentPartyId == request.agentPartyId } >= limit.value.toInt()) {
            throw ProxyLimitReached(limit.value.toInt(), limit.source)
        }
        val proxy = Proxy(
            UUID.randomUUID(), entranceId, assemblyId, request.principalPartyId, request.agentPartyId, kind.name,
            scope.name, items.joinToString(",").ifEmpty { null }, form.name, clock.instant(),
        )
        try {
            return aggregates.insert(proxy)
        } catch (e: DbActionExecutionException) {
            if (missingReference(e) != null) throw IllegalArgumentException("the principal or the representative is not a registered party")
            throw e
        }
    }

    @Transactional(readOnly = true)
    fun forAssembly(entranceId: UUID, assemblyId: UUID): List<Proxy> =
        proxies.findByAssemblyId(assemblyId).filter { it.entranceId == entranceId }

    private inline fun <reified E : Enum<E>> named(value: String, what: String): E =
        enumValues<E>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("unknown $what \"$value\" — one of ${enumValues<E>().joinToString { it.name }}")
}

private val PROXIES_OPEN = setOf(AssemblyStatus.DRAFT.name, AssemblyStatus.NOTICED.name)
