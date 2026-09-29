package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * The repair fund's disbursements and balance with the repositories mocked — no Spring, no database.
 * Proves the purpose, the signatory and the decision-or-emergency gate (PM-FUND-006…008), and that the
 * fund shows its balance and what is available net of committed disbursements (PM-FUND-009) — and that a
 * disbursement is paid out, or cancelled, once, by the fund account's holder (PM-FUND-007).
 * FundPersistenceIT tries each of the table's own checks against a violation.
 */
class FundServiceTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val accounts: FundAccountRepository = mock()
    private val disbursements: FundDisbursementRepository = mock()
    private val postings: PostingRepository = mock()
    private val jdbc: JdbcTemplate = mock()
    private val lateNight = Clock.fixed(Instant.parse("2026-09-28T21:30:00Z"), ZoneOffset.UTC)   // 00:30 on 29 September in Sofia
    private val service = FundService(aggregates, accounts, disbursements, postings, jdbc, lateNight)

    private val entranceId = UUID.randomUUID()
    private val chair = UUID.randomUUID()
    private val operating = FundAccountRow(UUID.randomUUID(), entranceId, "BG18RZBB91550123456789", "OPERATING", "Иван Петров", "MANAGER", chair)
    private val fund = FundAccountRow(UUID.randomUUID(), entranceId, "BG80BNBG96611020345678", "REPAIR_RENEWAL", "Иван Петров", "MANAGER", chair)
    private val inserted = mutableListOf<FundDisbursementRow>()
    private val updated = mutableListOf<FundDisbursementRow>()
    private val journal = mutableListOf<PostingRow>()
    private val lock = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))"

    private fun disbursement(amount: Long, status: String = "COMMITTED", on: String = "2026-09-01") = FundDisbursementRow(
        UUID.randomUUID(), entranceId, fund.id, amount, "EUR", "WORKS", "GA-2026-7", null, null, chair, status, LocalDate.parse(on),
    )

    private fun income(amount: Long) =
        PostingRow(UUID.randomUUID(), entranceId, UUID.randomUUID(), "BANK:REPAIR_RENEWAL", null, amount, "EUR", LocalDate.parse("2026-09-01"))

    private fun holding(inAccount: Long, rows: List<FundDisbursementRow> = emptyList(), out: List<PostingRow> = emptyList()) {
        whenever(postings.findByEntranceIdAndAccount(entranceId, "BANK:REPAIR_RENEWAL")).thenReturn(listOf(income(inAccount)) + out)
        whenever(disbursements.findByEntranceId(entranceId)).thenReturn(rows)
    }

    /** A disbursement already on record, found by its id. */
    private fun signedOff(amount: Long = 20_000, status: String = "COMMITTED") =
        disbursement(amount, status).also { whenever(disbursements.findById(it.id)).thenReturn(Optional.of(it)) }

    private fun pay(row: FundDisbursementRow, on: String = "2026-09-29", by: UUID = chair) =
        service.pay(entranceId, row.id, PayDisbursement(LocalDate.parse(on), by))

    private fun cancel(row: FundDisbursementRow, reason: String = "the GA revoked decision GA-2026-7", by: UUID = chair) =
        service.cancel(entranceId, row.id, CancelDisbursement(by, reason))

    private fun commit(
        amount: Long = 20_000, purpose: String = "WORKS", by: UUID = chair,
        decision: String? = "GA-2026-7", emergency: String? = null, measure: String? = null,
    ) = service.commit(entranceId, CommitDisbursement(amount, purpose, by, decision, emergency, measure))

    @BeforeEach
    fun fundWithMoney() {
        whenever(accounts.findByEntranceId(entranceId)).thenReturn(listOf(operating, fund))     // the operating account comes first
        holding(inAccount = 50_000)
        whenever(aggregates.insert(any<FundDisbursementRow>())).thenAnswer { (it.arguments[0] as FundDisbursementRow).also { row -> inserted += row } }
        whenever(aggregates.update(any<FundDisbursementRow>())).thenAnswer { (it.arguments[0] as FundDisbursementRow).also { row -> updated += row } }
        whenever(aggregates.insert(any<PostingRow>())).thenAnswer { (it.arguments[0] as PostingRow).also { row -> journal += row } }
    }

    @Test
    fun `PM-FUND-006 a disbursement names a lawful purpose, and a passport measure names the measure`() {
        assertThat(commit(purpose = "WORKS").purpose).isEqualTo("WORKS")
        assertThat(inserted.last().fundAccountId).isEqualTo(fund.id)                            // the repair fund's account, not the operating one
        assertThat(commit(purpose = "GA_PURPOSE").purpose).isEqualTo("GA_PURPOSE")
        assertThat(commit(purpose = "PASSPORT_MEASURE", measure = "ТП-2024 т. 3.2").passportMeasure).isEqualTo("ТП-2024 т. 3.2")
        assertThatThrownBy { commit(purpose = "PASSPORT_MEASURE") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { commit(purpose = "PASSPORT_MEASURE", measure = "  ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { commit(purpose = "NEW_YEAR_PARTY") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `PM-FUND-007 only the party holding the fund's account signs off, and on a GA decision`() {
        val signed = commit()
        assertThat(signed.decisionId).isEqualTo("GA-2026-7")
        assertThat(signed.authorisedBy).isEqualTo(chair)
        assertThat(signed.status).isEqualTo("COMMITTED")
        assertThat(signed.committedOn).isEqualTo(LocalDate.parse("2026-09-29"))             // the Sofia day, not the UTC one
        assertThatThrownBy { commit(by = UUID.randomUUID()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { commit(decision = null) }.isInstanceOf(IllegalArgumentException::class.java)   // no decision, no emergency
        whenever(accounts.findByEntranceId(entranceId)).thenReturn(listOf(fund.copy(holderParty = null)))
        assertThatThrownBy { commit() }.isInstanceOf(FundUnsignable::class.java)                          // nobody registered can sign
    }

    @Test
    fun `PM-FUND-008 an emergency repair needs no decision, but its justification and the available balance to cover it`() {
        holding(inAccount = 50_000, rows = listOf(disbursement(20_000)))                        // 30 000 available
        val urgent = commit(amount = 30_000, decision = null, emergency = "a burst riser floods the stairwell")
        assertThat(urgent.emergencyJustification).isEqualTo("a burst riser floods the stairwell")
        assertThat(urgent.decisionId).isNull()
        assertThatThrownBy { commit(amount = 30_001, decision = null, emergency = "the roof leaks") }.isInstanceOf(FundShortfall::class.java)
        assertThatThrownBy { commit(emergency = "both at once") }.isInstanceOf(IllegalArgumentException::class.java)
        for (purpose in listOf("GA_PURPOSE", "PASSPORT_MEASURE")) {                             // an emergency is repair works only
            assertThatThrownBy { commit(amount = 100, purpose = purpose, decision = null, emergency = "urgent", measure = "ТП-2024 т. 3.2") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `PM-FUND-008 the entrance is locked before the available balance is read, so two emergencies cannot both pass`() {
        commit(amount = 30_000, decision = null, emergency = "a burst riser floods the stairwell")
        inOrder(jdbc, postings) {
            verify(jdbc).queryForObject(lock, Int::class.java, "fund:$entranceId")
            verify(postings).findByEntranceIdAndAccount(entranceId, "BANK:REPAIR_RENEWAL")
        }
    }

    @Test
    fun `PM-FUND-009 a payout lowers the balance and what is committed alike, so what is available does not move`() {
        val works = signedOff(20_000)
        holding(inAccount = 50_000, rows = listOf(works))
        assertThat(service.view(entranceId).availableMinor).isEqualTo(30_000)
        val paid = pay(works, on = "2026-09-15")
        assertThat(paid.status).isEqualTo("PAID")
        assertThat(updated.single()).isEqualTo(works.copy(status = "PAID", paidOn = LocalDate.parse("2026-09-15"), paidBy = chair))
        assertThat(journal.map { it.journalId to it.valueDate }.toSet()).containsExactly(works.id to LocalDate.parse("2026-09-15"))
        holding(inAccount = 50_000, rows = updated.toList(), out = journal.filter { it.account == "BANK:REPAIR_RENEWAL" })
        val after = service.view(entranceId)
        assertThat(after.balanceMinor).isEqualTo(30_000)
        assertThat(after.committedMinor).isEqualTo(0)
        assertThat(after.availableMinor).isEqualTo(30_000)
    }

    @Test
    fun `PM-FUND-007 only a signed-off disbursement is paid out, once, by the fund account's holder, on the bank's date`() {
        val works = signedOff(20_000)
        assertThatThrownBy { pay(works, by = UUID.randomUUID()) }.isInstanceOf(IllegalArgumentException::class.java)   // not the holder
        assertThatThrownBy { pay(works, on = "2026-09-30") }.isInstanceOf(IllegalArgumentException::class.java)         // after today
        assertThatThrownBy { pay(works, on = "2026-08-31") }.isInstanceOf(IllegalArgumentException::class.java)         // before the sign-off
        assertThat(journal).isEmpty()                                                                                // a refused payout posts nothing
        assertThat(pay(works, on = "2026-09-01").amountMinor).isEqualTo(20_000)                // the sign-off day itself
        assertThat(pay(signedOff(1_000), on = "2026-09-29").paidOn).isEqualTo(LocalDate.parse("2026-09-29"))   // today in Sofia, still the 28th in UTC
        for (closed in listOf(signedOff(status = "PAID"), signedOff(status = "CANCELLED"))) {
            assertThatThrownBy { pay(closed) }.isInstanceOf(DisbursementClosed::class.java)
        }
        val elsewhere = disbursement(1_000).copy(entranceId = UUID.randomUUID())
        whenever(disbursements.findById(elsewhere.id)).thenReturn(Optional.of(elsewhere))
        assertThatThrownBy { pay(elsewhere) }.isInstanceOf(NoSuchElementException::class.java)                     // another entrance's
        assertThatThrownBy { pay(disbursement(1_000)) }.isInstanceOf(NoSuchElementException::class.java)           // none on record
        assertThat(journal).hasSize(4)
    }

    @Test
    fun `PM-FUND-009 a cancelled disbursement is no longer committed, so what is available rises`() {
        val works = signedOff(20_000)
        val cancelled = cancel(works)
        assertThat(cancelled.status).isEqualTo("CANCELLED")
        assertThat(updated.single()).isEqualTo(
            works.copy(status = "CANCELLED", cancelledOn = LocalDate.parse("2026-09-29"), cancelledBy = chair, cancelReason = "the GA revoked decision GA-2026-7"),
        )                                                                                          // stamped with the Sofia day
        assertThat(journal).isEmpty()                                                              // no money moved
        holding(inAccount = 50_000, rows = updated.toList())
        assertThat(service.view(entranceId).availableMinor).isEqualTo(50_000)
    }

    @Test
    fun `PM-FUND-007 only the fund account's holder cancels, with a reason, and never a closed disbursement`() {
        val works = signedOff(20_000)
        assertThatThrownBy { cancel(works, by = UUID.randomUUID()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { cancel(works, reason = "  ") }.isInstanceOf(IllegalArgumentException::class.java)
        for (closed in listOf(signedOff(status = "PAID"), signedOff(status = "CANCELLED"))) {
            assertThatThrownBy { cancel(closed) }.isInstanceOf(DisbursementClosed::class.java)
        }
        val elsewhere = disbursement(1_000).copy(entranceId = UUID.randomUUID())
        whenever(disbursements.findById(elsewhere.id)).thenReturn(Optional.of(elsewhere))
        assertThatThrownBy { cancel(elsewhere) }.isInstanceOf(NoSuchElementException::class.java)                  // another entrance's
        assertThatThrownBy { cancel(disbursement(1_000)) }.isInstanceOf(NoSuchElementException::class.java)        // none on record
        whenever(accounts.findByEntranceId(entranceId)).thenReturn(listOf(fund.copy(holderParty = null)))
        assertThatThrownBy { cancel(works) }.isInstanceOf(FundUnsignable::class.java)
        assertThatThrownBy { pay(works) }.isInstanceOf(FundUnsignable::class.java)
        assertThat(updated).isEmpty()
    }

    @Test
    fun `PM-FUND-007 the entrance is locked before a disbursement is read, so two payouts cannot close it twice`() {
        val first = signedOff()
        val second = signedOff()
        pay(first)
        cancel(second)
        inOrder(jdbc, disbursements) {
            verify(jdbc).queryForObject(lock, Int::class.java, "fund:$entranceId")
            verify(disbursements).findById(first.id)
            verify(jdbc).queryForObject(lock, Int::class.java, "fund:$entranceId")
            verify(disbursements).findById(second.id)
        }
    }

    @Test
    fun `PM-FUND-009 the fund shows its balance and, net of committed disbursements, what is available`() {
        holding(
            inAccount = 50_000,
            rows = listOf(disbursement(5_000, on = "2026-09-20"), disbursement(20_000), disbursement(7_000, status = "PAID", on = "2026-09-10")),
        )
        val view = service.view(entranceId)
        assertThat(view.iban).isEqualTo(fund.iban)                                              // the repair fund's account, not the operating one
        assertThat(view.balanceMinor).isEqualTo(50_000)
        assertThat(view.committedMinor).isEqualTo(25_000)                                     // a paid one is no longer committed
        assertThat(view.availableMinor).isEqualTo(25_000)
        assertThat(view.disbursements.map { it.committedOn.toString() }).containsExactly("2026-09-01", "2026-09-10", "2026-09-20")
    }

    @Test
    fun `PM-FUND-009 a payout may take the recorded balance below zero, since the bank is the truth`() {
        val works = signedOff(20_000)
        holding(inAccount = 10_000, rows = listOf(works))                                      // receipts not yet all recorded
        assertThat(pay(works).status).isEqualTo("PAID")
        holding(inAccount = 10_000, rows = updated.toList(), out = journal.filter { it.account == "BANK:REPAIR_RENEWAL" })
        assertThat(service.view(entranceId).balanceMinor).isEqualTo(-10_000)                  // shown, not hidden
    }

    @Test
    fun `PM-FUND-009 the fund is read from one snapshot, so a payout cannot fall between the balance and what is committed`() {
        // Two statements at READ COMMITTED paired the old balance with the new committed, overstating what is available
        // (seen by the S-G1-02c review against Postgres: 28 of 42 reads during 150 payouts).
        val view = FundService::class.java.getMethod("view", UUID::class.java).getAnnotation(Transactional::class.java)
        assertThat(view.isolation).isEqualTo(Isolation.REPEATABLE_READ)
        assertThat(view.readOnly).isTrue()
    }

    @Test
    fun `a disbursement is for a positive amount, and a blank reference counts as none`() {
        assertThatThrownBy { commit(amount = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(commit(amount = 1_000, decision = "  ", emergency = "a burst riser floods the stairwell").decisionId).isNull()
        val decided = commit(emergency = " ", measure = " ")
        assertThat(decided.decisionId).isEqualTo("GA-2026-7")
        assertThat(decided.emergencyJustification).isNull()
        assertThat(decided.passportMeasure).isNull()
        assertThatThrownBy { commit(decision = " ", emergency = "\t") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a decided disbursement is not capped by the available balance, so available can go below zero`() {
        holding(inAccount = 10_000)
        commit(amount = 40_000)
        whenever(disbursements.findByEntranceId(entranceId)).thenReturn(inserted.toList())
        assertThat(service.view(entranceId).availableMinor).isEqualTo(-30_000)
    }

    @Test
    fun `an entrance without a repair fund account is not found, even with an operating account`() {
        assertThatThrownBy { service.view(UUID.randomUUID()) }.isInstanceOf(NoSuchElementException::class.java)
        whenever(accounts.findByEntranceId(entranceId)).thenReturn(listOf(operating))
        assertThatThrownBy { service.view(entranceId) }.isInstanceOf(NoSuchElementException::class.java)
    }
}
