package zues.app.money

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.data.repository.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The status of a run that has been issued. */
const val RUN_ISSUED = "COMPLETE"

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

/** A stored run's header alone: never the basis it keeps (Rule: PM-FEE-014). A read of [ChargeRunRow]'s table, with no writes. */
@Table("charge_run")
data class ChargeRunHeader(@Id val id: UUID, val period: String, val legalDate: LocalDate)

/**
 * A stored charge line — one typed amount per unit, cost stream and named item (PM-FEE-011). Immutable: an issued
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
    /** The GA decision that set the line's key and rate (PM-FEE-003); empty for lines stored before it was kept. */
    val decisionId: String? = null,
    /** A named cost within the stream, e.g. CONCIERGE in MAINTENANCE (PM-FEE-011); empty for the stream's own line. */
    val item: String? = null,
)

interface ChargeRunRepository : ListCrudRepository<ChargeRunRow, UUID> {
    fun existsByEntranceIdAndPeriod(entranceId: UUID, period: String): Boolean
    fun findByEntranceIdAndPeriod(entranceId: UUID, period: String): ChargeRunRow?
}

interface ChargeRunHeaders : Repository<ChargeRunHeader, UUID> {
    /** The entrance's issued runs, the newest first. */
    @Query("SELECT id, period, legal_date FROM charge_run WHERE entrance_id = :entranceId AND status = '$RUN_ISSUED' ORDER BY legal_date DESC, period DESC")
    fun issued(entranceId: UUID): List<ChargeRunHeader>
}

interface ChargeLineRepository : ListCrudRepository<ChargeLineRow, UUID> {
    fun findByChargeRunId(chargeRunId: UUID): List<ChargeLineRow>
    fun findByUnitId(unitId: UUID): List<ChargeLineRow>
}
