package zues.app.money

import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** A disbursement to sign off: its purpose, and a GA decision or an emergency's justification (PM-FUND-006…008). */
data class CommitDisbursement(
    val amountMinor: Long,
    val purpose: String,
    val authorisedBy: UUID,
    val decisionId: String? = null,
    val emergencyJustification: String? = null,
    val passportMeasure: String? = null,
)

data class DisbursementView(
    val id: UUID,
    val amountMinor: Long,
    val purpose: String,
    val decisionId: String?,
    val passportMeasure: String?,
    val emergencyJustification: String?,
    val authorisedBy: UUID,
    val status: String,
    val committedOn: LocalDate,
) {
    companion object {
        fun of(row: FundDisbursementRow) = DisbursementView(
            row.id, row.amountMinor, row.purpose, row.decisionId, row.passportMeasure, row.emergencyJustification,
            row.authorisedBy, row.status, row.committedOn,
        )
    }
}

/** The repair and renewal fund: its balance, and what is available net of committed disbursements (PM-FUND-009). */
data class FundView(
    val entranceId: UUID,
    val iban: String,
    val holderName: String,
    val balanceMinor: Long,
    val committedMinor: Long,
    val availableMinor: Long,
    val disbursements: List<DisbursementView>,
)

/** An emergency disbursement the fund's available balance does not cover (Rule: PM-FUND-008). */
class FundShortfall(message: String) : RuntimeException(message)

/**
 * The repair and renewal fund's disbursements and balance. A disbursement is for a lawful purpose
 * (Rule: PM-FUND-006) and is signed off by the party holding the fund's account on a GA decision (Rule:
 * PM-FUND-007) — or, without one, as an emergency with its justification, and only while the available
 * balance covers it (Rule: PM-FUND-008). The balance is what the fund's bank account has received, read
 * from the ledger (paying out is S-G1-02c); committed but unpaid disbursements are not available (Rule:
 * PM-FUND-009). The money moves in the fund's own bank account, never through the platform (ADR-007).
 */
@Service
class FundService(
    private val aggregates: JdbcAggregateTemplate,
    private val accounts: FundAccountRepository,
    private val disbursements: FundDisbursementRepository,
    private val postings: PostingRepository,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    fun commit(entranceId: UUID, command: CommitDisbursement): DisbursementView {
        // One signing-off at a time per entrance, so two emergencies cannot both pass the cap (PM-FUND-008).
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))", Int::class.java, "fund:$entranceId")
        val fund = fundAccount(entranceId)
        require(command.amountMinor > 0) { "amountMinor must be positive" }
        val purpose = enumValueOf<DisbursementPurpose>(command.purpose)                 // Rule: PM-FUND-006
        require(purpose != DisbursementPurpose.PASSPORT_MEASURE || !command.passportMeasure.isNullOrBlank()) {
            "a passport-measure disbursement names the measure (PM-FUND-006)"
        }
        val holder = fund.holderParty                                                   // Rule: PM-FUND-007
            ?: throw IllegalStateException("the fund account's holder is not a registered party, so nobody can sign off a disbursement (PM-FUND-007)")
        require(command.authorisedBy == holder) { "only the party holding the fund's account signs off a disbursement (PM-FUND-007)" }
        val decided = !command.decisionId.isNullOrBlank()
        val emergency = !command.emergencyJustification.isNullOrBlank()
        require(decided != emergency) {
            "a disbursement rests on a GA decision, or is an emergency with its justification — one of the two (PM-FUND-007, PM-FUND-008)"
        }
        if (emergency) {                                                                // Rule: PM-FUND-008
            val available = view(entranceId).availableMinor
            if (command.amountMinor > available) {
                throw FundShortfall("an emergency disbursement of ${command.amountMinor} exceeds the ${available} available (PM-FUND-008)")
            }
        }
        val row = aggregates.insert(
            FundDisbursementRow(
                id = UUID.randomUUID(),
                entranceId = entranceId,
                fundAccountId = fund.id,
                amountMinor = command.amountMinor,
                currency = "EUR",
                purpose = purpose.name,
                decisionId = command.decisionId.takeIf { decided },
                passportMeasure = command.passportMeasure?.takeIf { it.isNotBlank() },
                emergencyJustification = command.emergencyJustification.takeIf { emergency },
                authorisedBy = command.authorisedBy,
                status = DisbursementStatus.COMMITTED.name,
                committedOn = LocalDate.parse(toSofiaDate(clock.instant())),           // a Sofia calendar day (PM-SYS-004)
            ),
        )
        return DisbursementView.of(row)
    }

    @Transactional(readOnly = true)
    fun view(entranceId: UUID): FundView {
        val fund = fundAccount(entranceId)
        val inAccount = postings.findByEntranceIdAndAccount(entranceId, Ledger.receivedAccount(ReceivedInto.REPAIR_RENEWAL))
            .sumOf { it.amountMinor }                                                   // derived from the ledger (ADR-006)
        val all = disbursements.findByEntranceId(entranceId).sortedBy { it.committedOn }
        val committed = all.filter { it.status == DisbursementStatus.COMMITTED.name }.sumOf { it.amountMinor }
        return FundView(entranceId, fund.iban, fund.holderName, inAccount, committed, inAccount - committed, all.map(DisbursementView::of))
    }

    private fun fundAccount(entranceId: UUID): FundAccountRow =
        accounts.findByEntranceId(entranceId).firstOrNull { it.purpose == FundPurpose.REPAIR_RENEWAL.name }
            ?: throw NoSuchElementException("entrance $entranceId has no repair and renewal fund account (PM-FUND-001)")
}
