package zues.app.money

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
import java.util.UUID

/**
 * A unit's itemised statement (Rule: PM-FEE-018) — what it owes and the derivation of every line. Read by the unit's
 * owner, the manager, the board and the controller, as the sign-in says who is asking; anybody else is a 403
 * (Rule: PM-SEC-002, ADR-002).
 */
@RestController
@RequestMapping("/api/money/units/{unitId}")
class StatementController(private val statements: StatementService, private val access: MoneyAccess, private val who: WhoAsks) {

    @GetMapping("/statement")
    fun statement(@PathVariable unitId: UUID, signedIn: JwtAuthenticationToken?): UnitStatement {
        access.unitCharges(unitId, who.of(signedIn))
        return statements.forUnit(unitId)
    }
}
