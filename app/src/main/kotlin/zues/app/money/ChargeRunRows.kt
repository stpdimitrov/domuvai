package zues.app.money

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * A stored charge run — the immutable header carrying the basis that produced it, so a past
 * bill is exactly reproducible (Rule: PM-FEE-014). Mapped unqualified; the search_path
 * resolves `charge_run` to `money.charge_run`.
 */
@Table("charge_run")
data class ChargeRunRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val period: String,
    val legalDate: LocalDate,
    val basis: JsonbValue,
    val basisHash: String,
    val lawVersion: String,
    val engineVersion: String,
    val status: String,
)

/**
 * A stored charge line — one typed amount per unit and cost stream. Immutable: an issued
 * charge is never rewritten (Rule: PM-FEE-015), enforced by a rule on the table itself.
 */
@Table("charge_line")
data class ChargeLineRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val chargeRunId: UUID,
    val unitId: UUID,
    val component: String,        // CostStream
    val allocationKey: String,    // AllocationKey
    val quantity: BigDecimal,
    val amountMinor: Long,
    val currency: String,
    val derivation: String,
)

interface ChargeRunRepository : ListCrudRepository<ChargeRunRow, UUID> {
    fun existsByEntranceIdAndPeriod(entranceId: UUID, period: String): Boolean
}

interface ChargeLineRepository : ListCrudRepository<ChargeLineRow, UUID> {
    fun findByChargeRunId(chargeRunId: UUID): List<ChargeLineRow>
    fun findByUnitId(unitId: UUID): List<ChargeLineRow>
}
