package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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

/** Proxies for a general assembly (PM-GA-009, PM-GA-010). */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/proxies")
class ProxyController(private val proxies: ProxyService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID, @RequestBody request: RegisterProxy): ProxyView =
        view(proxies.register(entranceId, assemblyId, request))

    @GetMapping
    fun list(@PathVariable entranceId: UUID, @PathVariable assemblyId: UUID): List<ProxyView> =
        proxies.forAssembly(entranceId, assemblyId).map(::view)

    private fun view(p: Proxy) = ProxyView(
        p.id, p.principalPartyId, p.agentPartyId, p.agentKind, p.scope,
        p.scopeItems?.split(",")?.map { it.toInt() } ?: emptyList(), p.form, p.registeredAt,
    )

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
