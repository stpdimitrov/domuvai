package zues.app.money

import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
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

/** A payout the fund's bank made on [paidOn], recorded by the party holding the fund's account (PM-FUND-007). */
data class PayDisbursement(val paidOn: LocalDate, val paidBy: UUID)

/** A signed-off disbursement withdrawn, with its reason, by the party holding the fund's account (PM-FUND-007). */
data class CancelDisbursement(val cancelledBy: UUID, val reason: String)

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
    val paidOn: LocalDate? = null,
    val paidBy: UUID? = null,
    val cancelledOn: LocalDate? = null,
    val cancelledBy: UUID? = null,
    val cancelReason: String? = null,
) {
    companion object {
        fun of(row: FundDisbursementRow) = DisbursementView(
            row.id, row.amountMinor, row.purpose, row.decisionId, row.passportMeasure, row.emergencyJustification,
            row.authorisedBy, row.status, row.committedOn, row.paidOn, row.paidBy, row.cancelledOn, row.cancelledBy, row.cancelReason,
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

/** A fund account whose holder is not a registered party, so nobody can sign off a disbursement (Rule: PM-FUND-007). */
class FundUnsignable(message: String) : RuntimeException(message)

/** A disbursement already paid out or cancelled: it is closed, and neither can happen twice (Rule: PM-FUND-007). */
class DisbursementClosed(message: String) : RuntimeException(message)

/**
 * The repair and renewal fund's disbursements and balance. A disbursement is for a lawful purpose
 * (Rule: PM-FUND-006) and is signed off by the party holding the fund's account on a GA decision (Rule:
 * PM-FUND-007) — or, without one, as an emergency repair with its justification, and only while the
 * available balance covers it (Rule: PM-FUND-008). Once signed off it is paid out, or cancelled, once.
 * The balance is what the fund's bank account received less what it paid out, read from the ledger;
 * committed but unpaid disbursements are not available (Rule: PM-FUND-009). The money moves in the
 * fund's own bank account, never through the platform (ADR-007) — a payout records what the bank did.
 */
@Service
class FundService(
    private val aggregates: JdbcAggregateTemplate,
    private val accounts: FundAccountRepository,
    private val disbursements: FundDisbursementRepository,
    private val postings: PostingRepository,
    private val keys: FundRequestKeys,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    /** A repeat with the same key and request returns the disbursement the first one signed off, as it stands now (DEVBRIEF §8). */
    @Transactional
    fun commit(entranceId: UUID, idempotencyKey: String, command: CommitDisbursement): DisbursementView {
        lock(entranceId)
        keys.prior(entranceId, idempotencyKey, FundOperation.COMMIT, command.asRequest())?.let { return DisbursementView.of(disbursements.findById(it).orElseThrow()) }
        val fund = fundAccount(entranceId)
        require(command.amountMinor > 0) { "amountMinor must be positive" }
        val purpose = enumValueOf<DisbursementPurpose>(command.purpose)                 // Rule: PM-FUND-006
        require(purpose != DisbursementPurpose.PASSPORT_MEASURE || !command.passportMeasure.isNullOrBlank()) {
            "a passport-measure disbursement names the measure (PM-FUND-006)"
        }
        requireHolder(fund, command.authorisedBy, "sign off")                           // Rule: PM-FUND-007
        val decided = !command.decisionId.isNullOrBlank()
        val emergency = !command.emergencyJustification.isNullOrBlank()
        require(decided != emergency) {
            "a disbursement rests on a GA decision, or is an emergency with its justification — one of the two (PM-FUND-007, PM-FUND-008)"
        }
        if (emergency) {                                                                // Rule: PM-FUND-008
            require(purpose == DisbursementPurpose.WORKS) {
                "only repair works may be ordered as an emergency, without a GA decision (PM-FUND-008)"
            }
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
                committedOn = today(),
            ),
        )
        keys.keep(entranceId, idempotencyKey, FundOperation.COMMIT, command.asRequest(), row.id)
        return DisbursementView.of(row)
    }

    /**
     * Records that the fund's bank paid a signed-off disbursement out (Rule: PM-FUND-007): exactly the amount
     * signed off, once, on the bank's value date — not after today, not before the sign-off — naming who recorded it. Its journal credits
     * the fund's bank account, so the balance and what is committed fall alike and what is available does not
     * move (Rule: PM-FUND-009). The balance may go below zero: the bank is the truth, and a negative balance
     * shows receipts not yet recorded. A repeat with the same key and request returns the paid disbursement, not a
     * refusal that it is closed (DEVBRIEF §8).
     */
    @Transactional
    fun pay(entranceId: UUID, disbursementId: UUID, idempotencyKey: String, command: PayDisbursement): DisbursementView {
        lock(entranceId)
        keys.prior(entranceId, idempotencyKey, FundOperation.PAY, command.asRequest(disbursementId))?.let { return DisbursementView.of(disbursements.findById(it).orElseThrow()) }
        val row = open(entranceId, disbursementId)
        requireHolder(fundAccount(entranceId), command.paidBy, "pay out")
        val today = today()
        require(!command.paidOn.isAfter(today)) { "a payout cannot be dated after today ($today)" }
        require(!command.paidOn.isBefore(row.committedOn)) { "a payout cannot be dated before the sign-off (${row.committedOn})" }
        val paid = aggregates.update(row.copy(status = DisbursementStatus.PAID.name, paidOn = command.paidOn, paidBy = command.paidBy))
        Ledger.forPayout(paid, command.paidOn).forEach { aggregates.insert(it) }
        keys.keep(entranceId, idempotencyKey, FundOperation.PAY, command.asRequest(disbursementId), paid.id)
        return DisbursementView.of(paid)
    }

    /**
     * Withdraws a signed-off, unpaid disbursement (Rule: PM-FUND-007): the party holding the fund's account
     * cancels it with a written reason, and it is no longer committed, so what is available rises (Rule:
     * PM-FUND-009). A paid disbursement is not cancelled. A repeat with the same key and request returns the
     * cancelled disbursement (DEVBRIEF §8).
     */
    @Transactional
    fun cancel(entranceId: UUID, disbursementId: UUID, idempotencyKey: String, command: CancelDisbursement): DisbursementView {
        lock(entranceId)
        keys.prior(entranceId, idempotencyKey, FundOperation.CANCEL, command.asRequest(disbursementId))?.let { return DisbursementView.of(disbursements.findById(it).orElseThrow()) }
        val row = open(entranceId, disbursementId)
        requireHolder(fundAccount(entranceId), command.cancelledBy, "cancel")
        require(command.reason.isNotBlank()) { "a cancellation gives its reason (PM-FUND-007)" }
        val cancelled = aggregates.update(
            row.copy(
                status = DisbursementStatus.CANCELLED.name, cancelledOn = today(),
                cancelledBy = command.cancelledBy, cancelReason = command.reason,
            ),
        )
        keys.keep(entranceId, idempotencyKey, FundOperation.CANCEL, command.asRequest(disbursementId), cancelled.id)
        return DisbursementView.of(cancelled)
    }

    /** One snapshot for both reads, so a payout committing between them cannot pair the old balance with the new committed. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun view(entranceId: UUID): FundView {
        val fund = fundAccount(entranceId)
        val inAccount = postings.findByEntranceIdAndAccount(entranceId, Ledger.receivedAccount(ReceivedInto.REPAIR_RENEWAL))
            .sumOf { it.amountMinor }                                                   // derived from the ledger (ADR-006)
        val all = disbursements.findByEntranceId(entranceId).sortedBy { it.committedOn }
        val committed = all.filter { it.status == DisbursementStatus.COMMITTED.name }.sumOf { it.amountMinor }
        return FundView(entranceId, fund.iban, fund.holderName, inAccount, committed, inAccount - committed, all.map(DisbursementView::of))
    }

    /** One write at a time per entrance, so two emergencies cannot both pass the cap (PM-FUND-008) nor two payouts close one disbursement. */
    private fun lock(entranceId: UUID) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))", Int::class.java, "fund:$entranceId")
    }

    /** Only the party holding the fund's account signs off, pays out or cancels a disbursement (Rule: PM-FUND-007). */
    private fun requireHolder(fund: FundAccountRow, party: UUID, act: String) {
        val holder = fund.holderParty
            ?: throw FundUnsignable("the fund account's holder is not a registered party, so nobody can $act a disbursement (PM-FUND-007)")
        require(party == holder) { "only the party holding the fund's account can $act a disbursement (PM-FUND-007)" }
    }

    /** The entrance's disbursement, still committed — neither paid out nor cancelled. */
    private fun open(entranceId: UUID, disbursementId: UUID): FundDisbursementRow {
        val row = disbursements.findById(disbursementId).orElse(null)?.takeIf { it.entranceId == entranceId }
            ?: throw NoSuchElementException("entrance $entranceId has no disbursement $disbursementId")
        if (row.status != DisbursementStatus.COMMITTED.name) {
            throw DisbursementClosed("disbursement $disbursementId is already ${row.status.lowercase()}")
        }
        return row
    }

    // What each request asked, field by field, for its Idempotency-Key's hash (DEVBRIEF §8).
    private fun CommitDisbursement.asRequest() = mapOf(
        "amountMinor" to amountMinor, "purpose" to purpose, "authorisedBy" to authorisedBy.toString(), "decisionId" to decisionId,
        "emergencyJustification" to emergencyJustification, "passportMeasure" to passportMeasure,
    )

    private fun PayDisbursement.asRequest(disbursementId: UUID) =
        mapOf("disbursementId" to disbursementId.toString(), "paidOn" to paidOn.toString(), "paidBy" to paidBy.toString())

    private fun CancelDisbursement.asRequest(disbursementId: UUID) =
        mapOf("disbursementId" to disbursementId.toString(), "cancelledBy" to cancelledBy.toString(), "reason" to reason)

    private fun today(): LocalDate = LocalDate.parse(toSofiaDate(clock.instant()))   // a Sofia calendar day (PM-SYS-004)

    private fun fundAccount(entranceId: UUID): FundAccountRow =
        accounts.findByEntranceId(entranceId).firstOrNull { it.purpose == FundPurpose.REPAIR_RENEWAL.name }
            ?: throw NoSuchElementException("entrance $entranceId has no repair and renewal fund account (PM-FUND-001)")
}
