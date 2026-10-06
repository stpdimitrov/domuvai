package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import zues.kernel.toSofiaDate
import zues.law.CATALOGUE_VERSION
import zues.law.ENGINE_VERSION
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.UUID

/** A handover to state the fund for (Rule: PM-FUND-010): who hands over to whom, on which day, and the bank's own balance. */
data class IssueHandover(
    val handoverOn: LocalDate,
    val from: LocalDate?,
    val outgoingPartyId: UUID,
    val incomingPartyId: UUID,
    val bankBalanceMinor: Long,
)

/** A signed-off disbursement still unpaid at the handover: the incoming side inherits it (Rule: PM-FUND-010). */
data class InheritedDisbursement(
    val disbursementId: UUID,
    val amountMinor: Long,
    val purpose: String,
    val decisionId: String?,
    val emergencyJustification: String?,
    val passportMeasure: String?,
    val committedOn: LocalDate,
)

/**
 * The fund's reconciled balance statement at a handover (Rule: PM-FUND-010) — all it says, so all its hash covers.
 * [closingMinor] is [openingMinor] + [receivedMinor] − [paidOutMinor] by construction. [differenceMinor] is the
 * bank's balance less [closingMinor] — positive when the bank shows more — and the statement is [reconciled]
 * only when it is zero.
 */
data class HandoverStatement(
    val entranceId: UUID,
    val fundAccountId: UUID,
    val iban: String,
    val holderName: String,
    val from: LocalDate?,
    val handoverOn: LocalDate,
    val outgoingPartyId: UUID,
    val incomingPartyId: UUID,
    val openingMinor: Long,
    val receivedMinor: Long,
    val paidOutMinor: Long,
    val closingMinor: Long,
    val bankBalanceMinor: Long,
    val differenceMinor: Long,
    val reconciled: Boolean,
    val committedMinor: Long,
    val availableMinor: Long,
    val inherited: List<InheritedDisbursement>,
    val issuedOn: LocalDate,
    val issuedAt: Instant,
) {
    /** The canonical basis — keys sorted, dates, times and ids as text — so its hash is byte-stable (ADR-006). */
    fun basisJson(): String = BasisJson.canonical(
        sortedMapOf(
            "entranceId" to entranceId.toString(), "fundAccountId" to fundAccountId.toString(), "iban" to iban,
            "holderName" to holderName, "from" to from?.toString(), "handoverOn" to handoverOn.toString(),
            "outgoingPartyId" to outgoingPartyId.toString(), "incomingPartyId" to incomingPartyId.toString(),
            "openingMinor" to openingMinor, "receivedMinor" to receivedMinor, "paidOutMinor" to paidOutMinor,
            "closingMinor" to closingMinor, "bankBalanceMinor" to bankBalanceMinor, "differenceMinor" to differenceMinor,
            "reconciled" to reconciled, "committedMinor" to committedMinor, "availableMinor" to availableMinor,
            "inherited" to inherited.map {
                sortedMapOf(
                    "disbursementId" to it.disbursementId.toString(), "amountMinor" to it.amountMinor, "purpose" to it.purpose,
                    "decisionId" to it.decisionId, "emergencyJustification" to it.emergencyJustification,
                    "passportMeasure" to it.passportMeasure, "committedOn" to it.committedOn.toString(),
                )
            },
            "issuedOn" to issuedOn.toString(), "issuedAt" to issuedAt.toString(),
        ),
    )

    companion object {
        /**
         * The statement from the fund's bank-account postings and its disbursements as recorded when it is issued, up to
         * and including the handover day (Rule: PM-FUND-010): the balance before the period (zero without a start),
         * what was paid out within it — the disbursements' payout journals — and what was received, net, and the
         * closing balance beside the bank's. A disbursement signed off by the handover day, and neither paid out nor
         * cancelled by then, is inherited.
         */
        fun of(
            fund: FundAccountRow, bank: List<PostingRow>, disbursements: List<FundDisbursementRow>, command: IssueHandover, issuedAt: Instant,
        ): HandoverStatement {
            val on = command.handoverOn
            val (before, within) = bank.filter { !it.valueDate.isAfter(on) }
                .partition { command.from != null && it.valueDate.isBefore(command.from) }
            val payouts = disbursements.map { it.id }.toSet()                          // a payout's journal is its disbursement's id
            val opening = before.sumOf { it.amountMinor }
            val paidOut = -within.filter { it.journalId in payouts }.sumOf { it.amountMinor }
            val received = within.filter { it.journalId !in payouts }.sumOf { it.amountMinor }
            val closing = opening + received - paidOut
            val inherited = disbursements
                .filter { !it.committedOn.isAfter(on) && it.paidOn?.isAfter(on) != false && it.cancelledOn?.isAfter(on) != false }
                .sortedWith(compareBy({ it.committedOn }, { it.id }))
                .map { InheritedDisbursement(it.id, it.amountMinor, it.purpose, it.decisionId, it.emergencyJustification, it.passportMeasure, it.committedOn) }
            val committed = inherited.sumOf { it.amountMinor }
            return HandoverStatement(
                fund.entranceId, fund.id, fund.iban, fund.holderName, command.from, on, command.outgoingPartyId, command.incomingPartyId,
                opening, received, paidOut, closing, command.bankBalanceMinor, command.bankBalanceMinor - closing,
                command.bankBalanceMinor == closing, committed, closing - committed, inherited,
                LocalDate.parse(toSofiaDate(issuedAt)), issuedAt,                          // a Sofia calendar day (PM-SYS-004)
            )
        }
    }
}

/**
 * A statement as it was issued (Rule: PM-FUND-010), with [basis] — the exact canonical text its [basisHash] covers,
 * which both parties will sign (the signing itself is the evidence lane's) — so anyone can check the one against the other.
 */
data class HandoverStatementView(
    val id: UUID,
    val statement: HandoverStatement,
    val basis: String,
    val basisHash: String,
    val lawVersion: String,
    val engineVersion: String,
)

/** A stored handover statement (Rule: PM-FUND-010). Insert-only: the table ignores updates and deletes. */
@Table("fund_handover_statement")
data class FundHandoverRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val fundAccountId: UUID,
    val periodFrom: LocalDate?,
    val handoverOn: LocalDate,
    val outgoingParty: UUID,
    val incomingParty: UUID,
    val openingMinor: Long,
    val receivedMinor: Long,
    val paidOutMinor: Long,
    val closingMinor: Long,
    val bankMinor: Long,
    val committedMinor: Long,
    val currency: String,
    val basis: JsonbValue,
    val basisHash: String,
    val lawVersion: String,
    val engineVersion: String,
    val issuedOn: LocalDate,
    val issuedAt: Instant,
)

/** Reads only: a statement is written once, through the aggregate template, and never saved over or deleted. */
interface FundHandoverRepository : Repository<FundHandoverRow, UUID> {
    fun findById(id: UUID): Optional<FundHandoverRow>
    fun findByEntranceIdOrderByIssuedAtDesc(entranceId: UUID): List<FundHandoverRow>
}

/** A stored statement whose basis no longer matches the hash it was issued with. */
class StatementTampered(message: String) : IllegalStateException(message)

/**
 * The fund's handover statements (Rule: PM-FUND-010). The fund account and its ledger stay with the entrance
 * (ADR-005) whoever manages it; re-titling the account to the incoming holder, who then signs, is not done here.
 * On a handover the fund's statement is issued and stored as issued; a correction is a new statement, never an edit.
 */
@Service
class FundHandoverService(
    private val aggregates: JdbcAggregateTemplate,
    private val accounts: FundAccountRepository,
    private val disbursements: FundDisbursementRepository,
    private val postings: PostingRepository,
    private val statements: FundHandoverRepository,
    private val keys: FundRequestKeys,
    private val json: ObjectMapper,
    private val clock: Clock,
) {
    /**
     * Rule: PM-FUND-010 — one snapshot, so a payout cannot fall between the ledger and the disbursements it reads.
     * A repeat with the same key and request returns the statement as first issued, and stores no second one (DEVBRIEF §8).
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun issue(entranceId: UUID, idempotencyKey: String, command: IssueHandover): HandoverStatementView {
        keys.prior(entranceId, idempotencyKey, FundOperation.HANDOVER, command.asRequest())?.let { return asIssued(statements.findById(it).orElseThrow()) }
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)                  // as the database keeps it
        val today = LocalDate.parse(toSofiaDate(issuedAt))                             // a Sofia calendar day (PM-SYS-004)
        require(!command.handoverOn.isAfter(today)) { "a handover statement cannot be dated after today ($today)" }
        require(command.from?.isAfter(command.handoverOn) != true) { "the period starts on or before the handover date" }
        require(command.outgoingPartyId != command.incomingPartyId) { "the outgoing and incoming sides are two different parties (PM-FUND-010)" }
        val fund = accounts.findByEntranceId(entranceId).firstOrNull { it.purpose == FundPurpose.REPAIR_RENEWAL.name }
            ?: throw NoSuchElementException("entrance $entranceId has no repair and renewal fund account (PM-FUND-001)")
        val bank = postings.findByEntranceIdAndAccount(entranceId, Ledger.receivedAccount(ReceivedInto.REPAIR_RENEWAL))
        val statement = HandoverStatement.of(fund, bank, disbursements.findByEntranceId(entranceId), command, issuedAt)
        val basis = statement.basisJson()
        val row = aggregates.insert(
            FundHandoverRow(
                UUID.randomUUID(), entranceId, fund.id, command.from, command.handoverOn, command.outgoingPartyId, command.incomingPartyId,
                statement.openingMinor, statement.receivedMinor, statement.paidOutMinor, statement.closingMinor,
                statement.bankBalanceMinor, statement.committedMinor, "EUR",
                JsonbValue(basis), BasisJson.hash(basis), CATALOGUE_VERSION, ENGINE_VERSION, statement.issuedOn, issuedAt,
            ),
        )
        keys.keep(entranceId, idempotencyKey, FundOperation.HANDOVER, command.asRequest(), row.id)
        return HandoverStatementView(row.id, statement, basis, row.basisHash, row.lawVersion, row.engineVersion)
    }

    /** Rule: PM-FUND-010 — a statement read back as it was issued: from its stored basis, checked against its hash, never recomputed. */
    @Transactional(readOnly = true)
    fun find(entranceId: UUID, statementId: UUID): HandoverStatementView =
        statements.findById(statementId).orElse(null)?.takeIf { it.entranceId == entranceId }?.let(::asIssued)
            ?: throw NoSuchElementException("entrance $entranceId has no handover statement $statementId")

    /** Rule: PM-FUND-010 — every statement issued for the entrance, newest first: a correction never hides the one it corrects. */
    @Transactional(readOnly = true)
    fun list(entranceId: UUID): List<HandoverStatementView> =
        statements.findByEntranceIdOrderByIssuedAtDesc(entranceId).map(::asIssued)

    /** What the request asked, field by field, for its Idempotency-Key's hash (DEVBRIEF §8). */
    private fun IssueHandover.asRequest() = mapOf(
        "handoverOn" to handoverOn.toString(), "from" to from?.toString(), "outgoingPartyId" to outgoingPartyId.toString(),
        "incomingPartyId" to incomingPartyId.toString(), "bankBalanceMinor" to bankBalanceMinor,
    )

    private fun asIssued(row: FundHandoverRow): HandoverStatementView {
        val statement = runCatching { json.readValue(row.basis.json, HandoverStatement::class.java) }
            .getOrElse { throw StatementTampered("handover statement ${row.id} cannot be read back as issued: ${it.message}") }
        val basis = statement.basisJson()
        if (BasisJson.hash(basis) != row.basisHash) {
            throw StatementTampered("handover statement ${row.id} no longer matches the hash it was issued with")
        }
        return HandoverStatementView(row.id, statement, basis, row.basisHash, row.lawVersion, row.engineVersion)
    }
}
