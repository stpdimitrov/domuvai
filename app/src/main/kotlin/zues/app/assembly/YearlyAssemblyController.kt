package zues.app.assembly

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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
class YearlyAssemblyController(private val yearly: YearlyAssembly) {

    // Rule: PM-GA-001
    @GetMapping
    fun heldIn(@PathVariable entranceId: UUID, @PathVariable year: Int): HeldInYearView {
        require(year in 1..9999) { "a calendar year, not $year" }
        return yearly.heldIn(entranceId, year).let { HeldInYearView(it.year, it.held, it.required, it.requiredSource, it.met, it.firstAssemblyId, it.firstHeldOn) }
    }

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onBadYear(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "bad request"))
}
