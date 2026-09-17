package zues.app.money

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * A unit's itemised statement (Rule: PM-FEE-018) — what it owes and the derivation of every line.
 * Read access will be narrowed to the owner and the board by the authorization module (PM-SEC-002,
 * ADR-002); until then the read is open.
 */
@RestController
@RequestMapping("/api/money/units/{unitId}")
class StatementController(private val statements: StatementService) {

    @GetMapping("/statement")
    fun statement(@PathVariable unitId: UUID): UnitStatement = statements.forUnit(unitId)
}
