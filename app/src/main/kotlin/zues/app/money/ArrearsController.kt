package zues.app.money

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * A unit's arrears, or an entrance's, aged as of a date (Rule: PM-DEBT-001). Read access will narrow to the owner
 * and the board with the authorization module (PM-SEC-002, ADR-002); until then the read is open.
 */
@RestController
@RequestMapping("/api/money")
class ArrearsController(private val arrears: ArrearsService) {

    @GetMapping("/units/{unitId}/arrears")
    fun arrears(@PathVariable unitId: UUID, @RequestParam asOf: String): UnitArrears = arrears.forUnit(unitId, date(asOf))

    /**
     * Every unit of the entrance that owes on the date, aged, largest first (Rule: PM-DEBT-001) — one read, not one per
     * unit. Money does not know which entrances exist (registry does), so one with no receivable reads as owing nothing.
     */
    @GetMapping("/entrances/{entranceId}/arrears")
    fun entrance(@PathVariable entranceId: UUID, @RequestParam asOf: String): EntranceArrears =
        arrears.forEntrance(entranceId, date(asOf))

    private fun date(asOf: String): LocalDate = runCatching { LocalDate.parse(asOf) }
        .getOrElse { throw IllegalArgumentException("asOf must be an ISO date (YYYY-MM-DD): $asOf") }

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid request"))
}
