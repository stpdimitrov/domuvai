package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
import zues.app.policy.Action
import java.time.Instant
import java.util.UUID

data class ProxyView(
    val id: UUID,
    val principalPartyId: UUID,
    val agentPartyId: UUID,
    val agentKind: String,
    val scope: String,
    val items: List<Int>,
    val form: String,
    val registeredAt: Instant,
)

/** A refusal; [limit] is the number one person may represent, when that is why (PM-GA-010). */
data class ProxyRefused(val error: String, val limit: Int? = null)

/** What a principal states when appointing their proxy. Who the principal is, is the sign-in's party. */
data class AppointProxyRequest(val agentPartyId: UUID, val agentKind: String, val scope: String, val items: List<Int> = emptyList(), val form: String)

/**
 * Proxies for a general assembly (PM-GA-009, PM-GA-010). A proxy is appointed by the one it represents: the principal
 * is the party the sign-in is tied to — never a field of the request — and must be an owner or a holder of use in the
 * entrance today (PM-SEC-001); anybody else is a 403 naming the rule. A body that cannot be read is a 400 for anyone.
 */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/proxies")
class ProxyController(private val proxies: ProxyService, private val who: WhoAsks, private val acting: Acting) {

    // Rule: PM-GA-009
    // Rule: PM-SEC-001
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @RequestBody request: AppointProxyRequest, signedIn: JwtAuthenticationToken?,
    ): ProxyView {
        val principal = acting.actor(who.of(signedIn), Action.APPOINT_PROXY, entranceId)
        return view(
            proxies.register(
                entranceId, assemblyId, RegisterProxy(principal.party, request.agentPartyId, request.agentKind, request.scope, request.items, request.form), principal,
            ),
        )
    }

    @GetMapping
    fun list(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID): List<ProxyView> =
        proxies.forAssembly(entranceId, assemblyId).map(::view)

    private fun view(p: Proxy) = ProxyView(
        p.id, p.principalPartyId, p.agentPartyId, p.agentKind, p.scope,
        p.scopeItems?.split(",")?.map { it.toInt() } ?: emptyList(), p.form, p.registeredAt,
    )

    /** The policy refuses, or nobody a record could name is asking. */
    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused) = ProxyRefused("${e.message} — ${e.ruleId}")

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException) = ProxyRefused(e.message ?: "invalid request")

    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException) = ProxyRefused(e.message ?: "not found")

    /** The agent is at the limit, the principal already has a proxy, or the assembly takes none any more. */
    @ExceptionHandler(IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: IllegalStateException) = ProxyRefused(e.message ?: "conflict", (e as? ProxyLimitReached)?.limit)
}
