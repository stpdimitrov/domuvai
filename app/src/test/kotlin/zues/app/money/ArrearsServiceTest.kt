package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import zues.law.numberOn
import java.time.LocalDate
import java.util.UUID

/**
 * Arrears ageing with the posting repository mocked — no Spring, no database. Proves outstanding
 * amounts land in the right band by how overdue they are (PM-DEBT-001), reading the payment term
 * from configuration (PM-DEBT-002).
 */
class ArrearsServiceTest {

    private val postings: PostingRepository = mock()
    private val service = ArrearsService(postings)

    private val unitId = UUID.randomUUID()
    private val asOf = LocalDate.of(2026, 6, 1)
    private val term = numberOn("PAYMENT_TERM_DAYS", asOf.toString()).toLong()

    private fun receivable(amount: Long, valueDate: LocalDate) =
        PostingRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "RECEIVABLE", unitId, amount, "EUR", valueDate)

    /** A receivable whose due date (value date + term) is [overdueDays] before the read date. */
    private fun overdueBy(overdueDays: Long, amount: Long) = receivable(amount, asOf.minusDays(term + overdueDays))

    @Test
    fun `PM-DEBT-001 outstanding is aged into buckets by days overdue`() {
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(
            listOf(overdueBy(5, 1000), overdueBy(45, 2000), overdueBy(100, 4000)),
        )
        val report = service.forUnit(unitId, asOf)
        assertThat(report.totalMinor).isEqualTo(7000)
        val byBand = report.buckets.associate { it.band to it.amountMinor }
        assertThat(byBand["0-30"]).isEqualTo(1000)
        assertThat(byBand["31-60"]).isEqualTo(2000)
        assertThat(byBand["61-90"]).isEqualTo(0)
        assertThat(byBand["90+"]).isEqualTo(4000)
    }

    @Test
    fun `a charge not yet due is CURRENT, not an arrear`() {
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(listOf(receivable(500, asOf)))
        val report = service.forUnit(unitId, asOf)
        assertThat(report.buckets.associate { it.band to it.amountMinor }["CURRENT"]).isEqualTo(500)
        assertThat(report.totalMinor).isEqualTo(500)
    }

    @Test
    fun `every band is present, in order, even with nothing owed`() {
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(emptyList())
        val report = service.forUnit(unitId, asOf)
        assertThat(report.buckets.map { it.band }).containsExactly("CURRENT", "0-30", "31-60", "61-90", "90+")
        assertThat(report.totalMinor).isEqualTo(0)
    }
}
