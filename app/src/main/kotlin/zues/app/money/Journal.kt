package zues.app.money

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/** One leg of a journal (ADR-006): a signed amount against an account — a debit above zero, a credit below. */
data class JournalLeg(val account: String, val unitId: UUID?, val amountMinor: Long)

/** What wrote a journal. A journal's id is the id of the record it books, so this is read, never stored. */
enum class JournalSource { CHARGE_RUN, PAYMENT, FUND_PAYOUT }

/** One journal, whole: every leg it has, on its one date, summing to zero (ADR-006). `source` is absent if nothing in money claims the id. */
data class JournalEntry(val journalId: UUID, val valueDate: LocalDate, val source: JournalSource?, val legs: List<JournalLeg>)

/** An entrance's journals dated within a range, oldest first (Rule: PM-PMC-008). `account` is the filter asked for, if any. */
data class JournalView(val entranceId: UUID, val from: LocalDate, val to: LocalDate, val account: String?, val journals: List<JournalEntry>)

/**
 * The operating account (Rule: PM-FUND-004, PM-FEE-019): its IBAN and holder, and what the ledger holds for that
 * bank account — apart from the fund's account, and apart from the cash box, which is neither. `outflowsRecorded` is
 * false: nothing in money can post a payment out of the operating account yet, so `balanceMinor` is what was paid
 * into it to date, not what the bank would show. It turns true when operating expenses are recorded.
 */
data class OperatingAccountView(
    val entranceId: UUID,
    val iban: String,
    val holderName: String,
    val balanceMinor: Long,
    val outflowsRecorded: Boolean,
)

/**
 * Reads of the ledger itself (ADR-006): the journals as they were posted, and an account's balance derived from
 * them. Money reads its own postings only, by entrance — one entrance's read never holds another's (Rule:
 * PM-FUND-005, PM-PMC-008). It does not know which entrances exist, so one with no postings reads as empty.
 */
@Service
class LedgerReads(
    private val postings: PostingRepository,
    private val accounts: FundAccountRepository,
    private val chargeRuns: ChargeRunRepository,
    private val payments: PaymentRepository,
    private val disbursements: FundDisbursementRepository,
) {
    /**
     * Rule: PM-PMC-008 — the entrance's journals dated from [from] to [to], both included, each whole. Every writer
     * dates all of a journal's legs alike and within one entrance, so a range never cuts one — and if a writer ever
     * does otherwise, the read stops rather than show a journal that does not sum to zero. With [account], only the
     * journals that touch it — still with every leg.
     */
    @Transactional(readOnly = true)
    fun journal(entranceId: UUID, from: LocalDate, to: LocalDate, account: String? = null): JournalView {
        require(!to.isBefore(from)) { "to ($to) must not be before from ($from)" }
        val journals = postings.findByEntranceIdAndValueDateBetween(entranceId, from, to)
            .groupBy { it.journalId }
            .filterValues { legs -> account == null || legs.any { it.account == account } }
        val source = sources(journals.keys)
        val entries = journals.map { (id, legs) ->
            check(legs.sumOf { it.amountMinor } == 0L && legs.all { it.valueDate == legs.first().valueDate }) {
                "journal $id is not whole within entrance $entranceId from $from to $to — its legs are dated apart or do not sum to zero (ADR-006)"
            }
            JournalEntry(
                id, legs.first().valueDate, source[id],
                legs.sortedWith(                                                         // debits, then credits; the same order on every read
                    compareBy<PostingRow> { it.amountMinor < 0 }.thenBy { it.account }.thenBy { it.unitId?.toString().orEmpty() }
                        .thenByDescending { kotlin.math.abs(it.amountMinor) }.thenBy { it.id.toString() },
                ).map { JournalLeg(it.account, it.unitId, it.amountMinor) },
            )
        }.sortedWith(compareBy<JournalEntry> { it.valueDate }.thenBy { it.journalId.toString() })
        return JournalView(entranceId, from, to, account, entries)
    }

    /** Which record each journal books: a charge run, a payment or a fund disbursement's payout share its id. */
    private fun sources(ids: Set<UUID>): Map<UUID, JournalSource> =
        chargeRuns.findAllById(ids).associate { it.id to JournalSource.CHARGE_RUN } +
            payments.findAllById(ids).associate { it.id to JournalSource.PAYMENT } +
            disbursements.findAllById(ids).associate { it.id to JournalSource.FUND_PAYOUT }

    /** Rule: PM-FUND-004, PM-FEE-019 — the operating account's balance, from the ledger (ADR-006), never the fund's. */
    @Transactional(readOnly = true)
    fun operatingAccount(entranceId: UUID): OperatingAccountView {
        val account = accounts.findByEntranceId(entranceId).firstOrNull { it.purpose == FundPurpose.OPERATING.name }
            ?: throw NoSuchElementException("entrance $entranceId has no operating account registered")
        val held = postings.findByEntranceIdAndAccount(entranceId, Ledger.receivedAccount(ReceivedInto.OPERATING)).sumOf { it.amountMinor }
        return OperatingAccountView(entranceId, account.iban, account.holderName, held, outflowsRecorded = false)
    }
}

/**
 * The ledger's reads for an entrance (Rule: PM-FUND-005, PM-PMC-008). Read access will narrow to the board and the
 * firm's staff for that entrance with the authorization module (PM-SEC-002, ADR-002); until then the reads are open.
 */
@RestController
@RequestMapping("/api/money/entrances/{entranceId}")
class LedgerController(private val ledger: LedgerReads) {

    @GetMapping("/journal")
    fun journal(
        @PathVariable entranceId: UUID,
        @RequestParam from: String,
        @RequestParam to: String,
        @RequestParam(required = false) account: String?,
    ): JournalView = ledger.journal(entranceId, date("from", from), date("to", to), account?.takeIf { it.isNotBlank() })

    @GetMapping("/operating-account")
    fun operatingAccount(@PathVariable entranceId: UUID): OperatingAccountView = ledger.operatingAccount(entranceId)

    private fun date(name: String, value: String): LocalDate = runCatching { LocalDate.parse(value) }.getOrNull()
        ?.takeIf { it.year in 1..9999 }                                                  // a calendar year the database can hold
        ?: throw IllegalArgumentException("$name must be an ISO date (YYYY-MM-DD): $value")

    /** A date that is not one, or a range that ends before it starts → 400. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid request"))

    /** No operating account registered for the entrance → 404. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))
}
