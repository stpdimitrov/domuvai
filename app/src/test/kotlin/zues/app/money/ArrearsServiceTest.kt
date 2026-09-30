package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    fun `PM-DEBT-001 a payment after the read date has not reduced the arrears, one before it is banded with its debt`() {
        val debt = overdueBy(45, 3000)
        fun paid(on: LocalDate, amount: Long) = PostingRow(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "RECEIVABLE", unitId, -amount, "EUR", on,
            settlesValueDate = debt.valueDate,
        )
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(
            listOf(debt, paid(asOf.minusDays(1), 1000), paid(asOf.plusDays(1), 2000)),
        )
        val report = service.forUnit(unitId, asOf)
        assertThat(report.totalMinor).isEqualTo(2000)
        assertThat(report.buckets.associate { it.band to it.amountMinor }["31-60"]).isEqualTo(2000)
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

    private val entranceId = UUID.randomUUID()

    /** A receivable of [unit]'s whose due date is [overdueDays] before the read date — after it, when negative. */
    private fun owed(unit: UUID, overdueDays: Long, amount: Long) = PostingRow(
        UUID.randomUUID(), entranceId, UUID.randomUUID(), "RECEIVABLE", unit, amount, "EUR", asOf.minusDays(term + overdueDays),
    )

    /** A payment's credit to [debt], made the day before the read date. */
    private fun settles(debt: PostingRow, amount: Long) = PostingRow(
        UUID.randomUUID(), entranceId, UUID.randomUUID(), "RECEIVABLE", debt.unitId, -amount, "EUR", asOf.minusDays(1),
        settlesValueDate = debt.valueDate,
    )

    @Test
    fun `PM-DEBT-001 an entrance's arrears list each unit that owes, aged as its own read, largest first — one owing nothing is left out`() {
        val small = UUID(0, 2)
        val tie = UUID(0, 1)                                              // owes what `small` owes: ordered by id
        val large = UUID(0, 3)
        val paidUp = owed(UUID.randomUUID(), 40, 5_000)
        val rows = listOf(
            owed(small, 5, 1_000), owed(small, -40, 9_000).copy(valueDate = asOf.plusDays(1)),   // raised after the read date
            owed(tie, 10, 1_000), owed(large, 45, 2_000), owed(large, 100, 4_000), paidUp, settles(paidUp, 5_000),
        )
        whenever(postings.findByEntranceIdAndAccount(entranceId, "RECEIVABLE")).thenReturn(rows)
        for (unit in listOf(small, tie, large)) {
            whenever(postings.findByUnitIdAndAccount(unit, "RECEIVABLE")).thenReturn(rows.filter { it.unitId == unit })
        }

        val report = service.forEntrance(entranceId, asOf)

        assertThat(report.units.map { it.unitId }).containsExactly(large, tie, small)
        assertThat(report.units).containsExactly(service.forUnit(large, asOf), service.forUnit(tie, asOf), service.forUnit(small, asOf))
        assertThat(report.totalMinor).isEqualTo(8_000)
        assertThat(report.entranceId).isEqualTo(entranceId)
        assertThat(report.asOf).isEqualTo(asOf.toString())
    }

    @Test
    fun `PM-DEBT-002 the oldest open debt fell due the payment term after its charge — a settled one is not the oldest`() {
        val settled = owed(unitId, 100, 3_000)
        val older = owed(unitId, 60, 1_000)
        val newer = owed(unitId, 45, 2_000)
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE"))
            .thenReturn(listOf(newer, settled, settles(settled, 3_000), older, settles(older, 400)))   // part-paid is still open

        val report = service.forUnit(unitId, asOf)

        assertThat(report.oldestDebt).isEqualTo(OldestDebt(older.valueDate.plusDays(term), 60))
    }

    @Test
    fun `PM-DEBT-002 a debt not yet due is 0 days overdue, and nothing owed has no oldest debt`() {
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(listOf(owed(unitId, -4, 500)))
        assertThat(service.forUnit(unitId, asOf).oldestDebt).isEqualTo(OldestDebt(asOf.plusDays(4), 0))

        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(emptyList())
        assertThat(service.forUnit(unitId, asOf).oldestDebt).isNull()
    }

    @Test
    fun `PM-DEBT-002 a credit dated after the read date has not closed its debt — it is still the oldest`() {
        val debt = owed(unitId, 20, 3_000)
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE"))
            .thenReturn(listOf(debt, settles(debt, 3_000).copy(valueDate = asOf.plusDays(1))))

        val report = service.forUnit(unitId, asOf)

        assertThat(report.totalMinor).isEqualTo(3_000)
        assertThat(report.oldestDebt).isEqualTo(OldestDebt(debt.valueDate.plusDays(term), 20))
    }

    @Test
    fun `PM-DEBT-002 a read date before any payment term was in force is a bad date`() {
        assertThatThrownBy { service.forUnit(unitId, LocalDate.of(1990, 1, 1)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
