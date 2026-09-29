package zues.app.money

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.time.LocalDate
import java.util.UUID

/**
 * A disbursement from the repair and renewal fund (Rule: PM-FUND-006): its purpose, what authorises it —
 * a GA decision (Rule: PM-FUND-007) or an emergency with its justification (Rule: PM-FUND-008) — and the
 * party who signed it off. COMMITTED until paid, and committed money is not available (Rule: PM-FUND-009).
 */
@Table("fund_disbursement")
data class FundDisbursementRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val fundAccountId: UUID,
    val amountMinor: Long,
    val currency: String,
    val purpose: String,                    // DisbursementPurpose
    val decisionId: String?,
    val passportMeasure: String?,
    val emergencyJustification: String?,
    val authorisedBy: UUID,
    val status: String,                     // DisbursementStatus
    val committedOn: LocalDate,
)

/** What fund money may be spent on (Rule: PM-FUND-006). */
enum class DisbursementPurpose {
    /** works under чл. 48–49 ЗУЕС, and equipment */
    WORKS,
    /** a measure the building's technical passport requires — its reference is then required */
    PASSPORT_MEASURE,
    /** another purpose the general assembly decided */
    GA_PURPOSE,
}

enum class DisbursementStatus { COMMITTED, PAID }

interface FundDisbursementRepository : ListCrudRepository<FundDisbursementRow, UUID> {
    fun findByEntranceId(entranceId: UUID): List<FundDisbursementRow>
}
