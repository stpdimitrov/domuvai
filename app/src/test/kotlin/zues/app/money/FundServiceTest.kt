package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The repair fund's disbursements and balance with the repositories mocked — no Spring, no database.
 * Proves the purpose, the signatory and the decision-or-emergency gate (PM-FUND-006…008), and that the
 * fund shows its balance and what is available net of committed disbursements (PM-FUND-009).
 * FundPersistenceIT proves the table refuses a disbursement with neither a decision nor an emergency.
 */
class FundServiceTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val accounts: FundAccountRepository = mock()
    private val disbursements: FundDisbursementRepository = mock()
    private val postings: PostingRepository = mock()
    private val lateNight = Clock.fixed(Instant.parse("2026-09-28T21:30:00Z"), ZoneOffset.UTC)   // 00:30 on 29 September in Sofia
    private val jdbc: JdbcTemplate = mock()
    private val service = FundService(aggregates, accounts, disbursements, postings, jdbc, lateNight)

    private val entranceId = UUID.randomUUID()
    private val chair = UUID.randomUUID()
    private val fund = FundAccountRow(UUID.randomUUID(), entranceId, "BG80BNBG96611020345678", "REPAIR_RENEWAL", "Иван Петров", "MANAGER", chair)
    private val inserted = mutableListOf<FundDisbursementRow>()

    private fun disbursement(amount: Long, status: String = "COMMITTED") = FundDisbursementRow(
        UUID.randomUUID(), entranceId, fund.id, amount, "EUR", "WORKS", "GA-2026-7", null, null, chair, status, LocalDate.parse("2026-09-01"),
    )

    private fun holding(inAccount: Long, rows: List<FundDisbursementRow> = emptyList()) {
        whenever(postings.findByEntranceIdAndAccount(entranceId, "BANK:REPAIR_RENEWAL")).thenReturn(
            listOf(PostingRow(UUID.randomUUID(), entranceId, UUID.randomUUID(), "BANK:REPAIR_RENEWAL", null, inAccount, "EUR", LocalDate.parse("2026-09-01"))),
        )
        whenever(disbursements.findByEntranceId(entranceId)).thenReturn(rows)
    }

    private fun commit(
        amount: Long = 20_000, purpose: String = "WORKS", by: UUID = chair,
        decision: String? = "GA-2026-7", emergency: String? = null, measure: String? = null,
    ) = service.commit(entranceId, CommitDisbursement(amount, purpose, by, decision, emergency, measure))

    @BeforeEach
    fun fundWithMoney() {
        whenever(accounts.findByEntranceId(entranceId)).thenReturn(listOf(fund))
        holding(inAccount = 50_000)
        whenever(aggregates.insert(any<FundDisbursementRow>())).thenAnswer { (it.arguments[0] as FundDisbursementRow).also { row -> inserted += row } }
    }

    @Test
    fun `PM-FUND-006 a disbursement names a lawful purpose, and a passport measure names the measure`() {
        assertThat(commit(purpose = "WORKS").purpose).isEqualTo("WORKS")
        assertThat(commit(purpose = "PASSPORT_MEASURE", measure = "ТП-2024 т. 3.2").passportMeasure).isEqualTo("ТП-2024 т. 3.2")
        assertThatThrownBy { commit(purpose = "PASSPORT_MEASURE") }.isInstanceOf(IllegalArgumentException::class.java)
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
        assertThatThrownBy { commit() }.isInstanceOf(IllegalStateException::class.java)                    // nobody registered can sign
    }

    @Test
    fun `PM-FUND-008 an emergency needs no decision, but its justification and the available balance to cover it`() {
        holding(inAccount = 50_000, rows = listOf(disbursement(20_000)))                        // 30 000 available
        val urgent = commit(amount = 30_000, decision = null, emergency = "a burst riser floods the stairwell")
        assertThat(urgent.emergencyJustification).isEqualTo("a burst riser floods the stairwell")
        assertThat(urgent.decisionId).isNull()
        assertThatThrownBy { commit(amount = 30_001, decision = null, emergency = "the roof leaks") }.isInstanceOf(FundShortfall::class.java)
        assertThatThrownBy { commit(emergency = "both at once") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `PM-FUND-009 the fund shows its balance and, net of committed disbursements, what is available`() {
        holding(inAccount = 50_000, rows = listOf(disbursement(20_000), disbursement(5_000), disbursement(7_000, status = "PAID")))
        val view = service.view(entranceId)
        assertThat(view.balanceMinor).isEqualTo(50_000)
        assertThat(view.committedMinor).isEqualTo(25_000)                                     // a paid one is no longer committed
        assertThat(view.availableMinor).isEqualTo(25_000)
        assertThat(view.disbursements).hasSize(3)
    }

    @Test
    fun `a decided disbursement is not capped by the available balance, so available can go below zero`() {
        holding(inAccount = 10_000)
        commit(amount = 40_000)
        whenever(disbursements.findByEntranceId(entranceId)).thenReturn(inserted.toList())
        assertThat(service.view(entranceId).availableMinor).isEqualTo(-30_000)
    }

    @Test
    fun `an entrance without a repair fund account is not found`() {
        assertThatThrownBy { service.view(UUID.randomUUID()) }.isInstanceOf(NoSuchElementException::class.java)
    }
}
