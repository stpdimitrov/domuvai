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
 * A unit's arrears, aged as of a date (Rule: PM-DEBT-001). Read access will narrow to the owner and
 * the board with the authorization module (PM-SEC-002, ADR-002); until then the read is open.
 */
@RestController
@RequestMapping("/api/money/units/{unitId}")
class ArrearsController(private val arrears: ArrearsService) {

    @GetMapping("/arrears")
    fun arrears(@PathVariable unitId: UUID, @RequestParam asOf: String): UnitArrears {
        val date = runCatching { LocalDate.parse(asOf) }
            .getOrElse { throw IllegalArgumentException("asOf must be an ISO date (YYYY-MM-DD): $asOf") }
        return arrears.forUnit(unitId, date)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid request"))
}
