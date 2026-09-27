package zues.app.money

import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.registry.Units
import zues.kernel.toSofiaDate
import zues.law.CATALOGUE_VERSION
import zues.law.ENGINE_VERSION
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Where a payment landed: one of the entrance's registered accounts (PM-FUND-004), or cash. */
enum class ReceivedInto { OPERATING, REPAIR_RENEWAL, CASH }

/**
 * A recorded payment (Rule: PM-DEBT-008) — immutable, with the rule that allocated it. Its id is
 * also the journal id of its postings, which carry the allocation; `basis` records the rule, its
 * remainder rule and the debts it saw (ADR-006). `recorded_at` is left to the database default.
 * Mapped unqualified; the search_path resolves `payment` to `money.payment`.
 */
@Table("payment")
data class PaymentRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val unitId: UUID,
    val amountMinor: Long,
    val currency: String,
    val valueDate: LocalDate,
    val receivedInto: String,       // ReceivedInto
    val allocationRule: String,     // PaymentAllocation.OLDEST_FIRST | DESIGNATED
    val designatedDate: LocalDate?,
    val basis: JsonbValue,
    val basisHash: String,
    val lawVersion: String,
    val engineVersion: String,
    val idempotencyKey: String,
)

interface PaymentRepository : ListCrudRepository<PaymentRow, UUID> {
    fun findByEntranceIdAndIdempotencyKey(entranceId: UUID, idempotencyKey: String): PaymentRow?
    fun findFirstByUnitIdOrderByValueDateDesc(unitId: UUID): PaymentRow?
}

data class RecordPaymentRequest(
    val unitId: UUID,
    val amountMinor: Long,
    val valueDate: String,
    val receivedInto: String,                // OPERATING | REPAIR_RENEWAL | CASH
    val designatedDebtDate: String? = null,  // the payer's designation; oldest-first when absent
)

/** What one debt — identified by its value date — received from the payment. */
data class AllocatedPart(val debtDate: String, val amountMinor: Long)

/**
 * A payment and how it was allocated (Rule: PM-DEBT-008): the rule applied, what each debt
 * received, and any overpayment credited to the unit's advance. Built from the journal, so the
 * answer at recording and every later read are the same.
 */
data class PaymentView(
    val paymentId: UUID,
    val entranceId: UUID,
    val unitId: UUID,
    val amountMinor: Long,
    val valueDate: String,
    val receivedInto: String,
    val allocationRule: String,
    val designatedDebtDate: String?,
    val allocation: List<AllocatedPart>,
    val unallocatedMinor: Long,
) {
    companion object {
        fun of(payment: PaymentRow, journal: List<PostingRow>) = PaymentView(
            paymentId = payment.id,
            entranceId = payment.entranceId,
            unitId = payment.unitId,
            amountMinor = payment.amountMinor,
            valueDate = payment.valueDate.toString(),
            receivedInto = payment.receivedInto,
            allocationRule = payment.allocationRule,
            designatedDebtDate = payment.designatedDate?.toString(),
            allocation = journal.filter { it.account == Ledger.RECEIVABLE }
                .sortedBy(Ledger::debtDate)
                .map { AllocatedPart(Ledger.debtDate(it).toString(), -it.amountMinor) },
            unallocatedMinor = -journal.filter { it.account == Ledger.ADVANCE }.sumOf { it.amountMinor },
        )
    }
}

/**
 * Raised when a payment is recorded, in the same transaction as the write. The externalized
 * contract (docs/events/PaymentPosted.schema.json) carries `entranceId` in the envelope and the
 * amount as `{amount_minor, currency}` — projected when externalization is wired. `postingId` is
 * the journal id.
 */
data class PaymentPosted(
    val entranceId: UUID,
    val postingId: UUID,
    val unitId: UUID,
    val amountMinor: Long,
    val valueDate: LocalDate,
    val allocationRule: String,
)

/**
 * The allocation's basis (ADR-006): the rule applied, the payer's designation, what happens to the
 * remainder, and the open debts the payment saw — enough to reproduce the allocation exactly.
 */
object PaymentBasis {
    const val REMAINDER_RULE = "OLDEST_FIRST_THEN_ADVANCE"

    fun json(
        debts: List<PaymentAllocation.OutstandingDebt>,
        amountMinor: Long,
        valueDate: LocalDate,
        designated: LocalDate?,
        rule: String,
    ): String = BasisJson.canonical(
        sortedMapOf(
            "rule" to rule,
            "designatedDebtDate" to designated?.toString(),
            "remainderRule" to REMAINDER_RULE,
            "amountMinor" to amountMinor,
            "valueDate" to valueDate.toString(),
            "openDebts" to debts.sortedBy { it.valueDate }
                .map { sortedMapOf("debtDate" to it.valueDate.toString(), "outstandingMinor" to it.outstandingMinor) },
        ),
    )
}

/** An `Idempotency-Key` already used for a different payment — a 409, never a second payment. */
class IdempotencyKeyReused(message: String) : RuntimeException(message)

/**
 * A payment dated before one already recorded for the unit — a 409. The later payment has already
 * settled debts oldest-first; the ledger is append-only, so an earlier one cannot be slotted in
 * behind it without re-allocating history.
 */
class PaymentOutOfOrder(message: String) : RuntimeException(message)

/**
 * Records payments (Rule: PM-DEBT-008). A payment settles the unit's oldest debt first unless the
 * payer designates one; the allocation is posted as a balanced journal and the rule applied is
 * stored with it. The unit's debts are derived from the ledger (ADR-006), never a stored balance.
 */
@Service
class PaymentService(
    private val payments: PaymentRepository,
    private val postings: PostingRepository,
    private val fundAccounts: FundAccountRepository,
    private val units: Units,
    private val aggregates: JdbcAggregateTemplate,
    private val jdbc: JdbcTemplate,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    // Rule: PM-DEBT-008
    @Transactional
    fun record(entranceId: UUID, idempotencyKey: String, request: RecordPaymentRequest): PaymentView {
        require(idempotencyKey.isNotBlank()) { "an Idempotency-Key is required" }
        // One payment per unit at a time: two concurrent payments must not both settle one debt.
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))", Int::class.java, request.unitId.toString())

        payments.findByEntranceIdAndIdempotencyKey(entranceId, idempotencyKey)?.let { prior ->
            if (!prior.matches(request)) throw IdempotencyKeyReused("Idempotency-Key $idempotencyKey was used for a different payment")
            return PaymentView.of(prior, postings.findByJournalId(prior.id))
        }

        val valueDate = LocalDate.parse(request.valueDate)
        val into = ReceivedInto.valueOf(request.receivedInto)
        val designated = request.designatedDebtDate?.let(LocalDate::parse)
        val today = LocalDate.parse(toSofiaDate(clock.instant()))
        require(!valueDate.isAfter(today)) { "a payment cannot be dated after today ($today)" }
        payments.findFirstByUnitIdOrderByValueDateDesc(request.unitId)?.let { latest ->
            if (latest.valueDate.isAfter(valueDate)) {
                throw PaymentOutOfOrder("unit ${request.unitId} already has a payment dated ${latest.valueDate}; record this one on or after it")
            }
        }
        if (units.forEntrance(entranceId, valueDate).none { it.unitId == request.unitId }) {
            throw NoSuchElementException("unit ${request.unitId} is not in entrance $entranceId")
        }
        require(into == ReceivedInto.CASH || fundAccounts.existsByEntranceIdAndPurpose(entranceId, into.name)) {
            "entrance $entranceId has no $into account registered"
        }

        val debts = Ledger.openDebts(postings.findByUnitIdAndAccount(request.unitId, Ledger.RECEIVABLE), valueDate)
        // A designation must name a debt actually owed, or the stored DESIGNATED would be false.
        require(designated == null || debts.any { it.valueDate == designated }) {
            "unit ${request.unitId} owes no debt dated $designated"
        }
        val allocation = PaymentAllocation.allocate(debts, request.amountMinor, designated)
        val basis = PaymentBasis.json(debts, request.amountMinor, valueDate, designated, allocation.rule)
        val payment = PaymentRow(
            id = UUID.randomUUID(),
            entranceId = entranceId,
            unitId = request.unitId,
            amountMinor = request.amountMinor,
            currency = "EUR",
            valueDate = valueDate,
            receivedInto = into.name,
            allocationRule = allocation.rule,
            designatedDate = designated,
            basis = JsonbValue(basis),
            basisHash = BasisJson.hash(basis),
            lawVersion = CATALOGUE_VERSION,
            engineVersion = ENGINE_VERSION,
            idempotencyKey = idempotencyKey,
        )
        aggregates.insert(payment)
        val journal = Ledger.forPayment(payment, allocation)
        journal.forEach { aggregates.insert(it) }
        events.publishEvent(PaymentPosted(entranceId, payment.id, payment.unitId, payment.amountMinor, valueDate, allocation.rule))
        return PaymentView.of(payment, journal)
    }

    /** A recorded payment with its allocation, read back from the journal (PM-DEBT-008). */
    @Transactional(readOnly = true)
    fun find(entranceId: UUID, paymentId: UUID): PaymentView {
        val payment = payments.findById(paymentId).filter { it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no payment $paymentId in entrance $entranceId") }
        return PaymentView.of(payment, postings.findByJournalId(payment.id))
    }

    /** A replay carries the same payment: same unit, amount, date, account and designation. */
    private fun PaymentRow.matches(r: RecordPaymentRequest) =
        unitId == r.unitId && amountMinor == r.amountMinor && valueDate.toString() == r.valueDate &&
            receivedInto == r.receivedInto && designatedDate?.toString() == r.designatedDebtDate
}
