package zues.app.money

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks

/**
 * The money module's charge-run calculator. Stateless: it computes a run and returns it —
 * it does not persist, because a stored run is coupled to a registry-owned entrance and
 * its units (V1 FKs), which a self-contained request does not supply. Persistence is a
 * later slice, once registry units exist.
 */
@RestController
@RequestMapping("/api/money/charge-runs")
class ChargeRunController(private val access: MoneyAccess, private val who: WhoAsks) {

    /** Compute the charges for a period without storing them — a live "what would this bill?". */
    @PostMapping("/preview")
    fun preview(@RequestBody request: ChargeRunRequest, signedIn: JwtAuthenticationToken?): ChargeRunResponse {
        access.keep(request.entranceId, who.of(signedIn))                       // the entrance the run is for
        return ChargeCalculator.run(request)
    }

    /**
     * The engine rejects an unlawful run by throwing — a tariff line with no GA decision
     * (PM-FEE-012), ideal parts that do not sum to 100% (PM-ORG-002), a business multiplier
     * outside the statutory range or missing where a unit would pay it (PM-FEE-010), an unknown stream or key. That is a bad
     * request, not a server fault.
     */
    @ExceptionHandler(IllegalStateException::class, IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalidRun(e: RuntimeException): Map<String, String> =
        mapOf("error" to (e.message ?: "invalid charge run"))
}
