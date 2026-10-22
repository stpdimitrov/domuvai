package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
import java.time.LocalDate
import java.util.UUID

/** Whether an entrance held its assemblies in a calendar year (PM-GA-001): how many, against how many, and the first. */
data class HeldInYearView(
    val year: Int,
    val held: Int,
    val required: Int,
    val requiredSource: String,
    val met: Boolean,
    val firstAssemblyId: UUID?,
    val firstHeldOn: LocalDate?,
)

/** The yearly assembly of an entrance (PM-GA-001), read as an assembly is read. */
@RestController
@RequestMapping("/api/assembly/entrances/{entranceId}/years/{year}")
class YearlyAssemblyController(private val yearly: YearlyAssembly, private val reads: AssemblyReads, private val who: WhoAsks) {

    // Rule: PM-GA-001
    // Rule: PM-SEC-001
    @GetMapping
    fun heldIn(@PathVariable entranceId: UUID, @PathVariable year: Int, signedIn: JwtAuthenticationToken?): HeldInYearView {
        reads.require(entranceId, who.of(signedIn))
        require(year in 1..9999) { "a calendar year, not $year" }
        return yearly.heldIn(entranceId, year).let { HeldInYearView(it.year, it.held, it.required, it.requiredSource, it.met, it.firstAssemblyId, it.firstHeldOn) }
    }

    /** The policy refuses: the year is read as an assembly is. */
    @ExceptionHandler(ActRefused::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onRefused(e: ActRefused): Map<String, String> = mapOf("error" to (e.message ?: "not allowed"), "rule" to e.ruleId)

    /** A year no law of the catalogue was in force for: there is nothing to have held an assembly against. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onNoLaw(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "no law in force for that year"))

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onBadYear(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "bad request"))
}
