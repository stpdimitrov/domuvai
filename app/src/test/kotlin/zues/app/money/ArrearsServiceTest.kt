package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import zues.law.numberOn
import java.time.LocalDate
import java.util.UUID
import java.util.function.Supplier

/**
 * Arrears ageing with the posting repository mocked — no database, and no Spring but for the one test
 * that has Spring build the service. Proves outstanding
 * amounts land in the right band by how overdue they are (PM-DEBT-001), reading the payment term
 * from configuration (PM-DEBT-002) — the one in force on each debt's own date (PM-SYS-002), which
 * takes a lookup with two terms to show: the law has one.
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

    /** A test's own two terms, neither the law's: [before] days for a debt dated before [change], [from] days from it on. */
    private fun twoTerms(before: Long, from: Long): (LocalDate) -> Long = { if (it.isBefore(change)) before else from }
    private val change = LocalDate.of(2026, 6, 1)

    private fun charged(on: LocalDate, amount: Long) =
        PostingRow(UUID.randomUUID(), entranceId, UUID.randomUUID(), "RECEIVABLE", unitId, amount, "EUR", on)

    @Test
    fun `PM-SYS-002 a debt falls due by the payment term in force on its own date, not on the read date`() {
        val may = charged(LocalDate.of(2026, 5, 10), 3_000)     // 10 days: due 20.05
        val june = charged(LocalDate.of(2026, 6, 5), 5_000)     // 40 days: due 15.07
        val partPaid = settles(may, 1_000).copy(valueDate = LocalDate.of(2026, 6, 20))   // dated under the new term, banded with its debt
        val rows = listOf(may, june, partPaid)
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(rows)
        whenever(postings.findByEntranceIdAndAccount(entranceId, "RECEIVABLE")).thenReturn(rows)
        val service = ArrearsService(postings, twoTerms(before = 10, from = 40))
        val read = LocalDate.of(2026, 7, 1)                     // the term on the read date is 40 days — May's debt keeps its 10

        val report = service.forUnit(unitId, read)

        assertThat(report.buckets.associate { it.band to it.amountMinor })
            .isEqualTo(mapOf("CURRENT" to 5_000L, "0-30" to 0L, "31-60" to 2_000L, "61-90" to 0L, "90+" to 0L))
        assertThat(report.oldestDebt).isEqualTo(OldestDebt(LocalDate.of(2026, 5, 20), 42))
        assertThat(service.forEntrance(entranceId, read).units).containsExactly(report)
    }

    @Test
    fun `PM-DEBT-002 the oldest debt is the open one that fell due first, not the first charged`() {
        val service = ArrearsService(postings, twoTerms(before = 40, from = 10))
        val first = charged(LocalDate.of(2026, 5, 25), 1_000)   // 40 days: due 04.07
        val second = charged(LocalDate.of(2026, 6, 5), 2_000)   // 10 days: due 15.06 — charged later, due sooner
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(listOf(first, second))

        assertThat(service.forUnit(unitId, LocalDate.of(2026, 7, 10)).oldestDebt).isEqualTo(OldestDebt(LocalDate.of(2026, 6, 15), 25))

        // Two debts that fall due on one day: both are owed, both in that day's band.
        val sameDay = charged(LocalDate.of(2026, 5, 6), 4_000)  // 40 days: due 15.06, as `second`
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(listOf(sameDay, second))
        val report = service.forUnit(unitId, LocalDate.of(2026, 6, 30))
        assertThat(report.buckets.associate { it.band to it.amountMinor }["0-30"]).isEqualTo(6_000)
        assertThat(report.totalMinor).isEqualTo(6_000)
        assertThat(report.oldestDebt).isEqualTo(OldestDebt(LocalDate.of(2026, 6, 15), 15))
    }

    @Test
    fun `PM-SYS-002 a read date before any payment term reads as nothing owed`() {
        // Neither debt was raised by the read date, so neither is looked up — the 1995 one has no term to find.
        val rows = listOf(owed(unitId, 20, 3_000), charged(LocalDate.of(1995, 1, 1), 500))
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(rows)
        whenever(postings.findByEntranceIdAndAccount(entranceId, "RECEIVABLE")).thenReturn(rows)
        val early = LocalDate.of(1990, 1, 1)

        val report = service.forUnit(unitId, early)

        assertThat(report.totalMinor).isEqualTo(0)
        assertThat(report.oldestDebt).isNull()
        assertThat(service.forEntrance(entranceId, early)).isEqualTo(EntranceArrears(entranceId, early.toString(), 0, emptyList()))
    }

    @Test
    fun `PM-SYS-002 a debt dated before any payment term stops the read, naming the missing constant`() {
        val rows = listOf(owed(unitId, 20, 3_000), charged(LocalDate.of(1990, 1, 1), 500))
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(rows)
        whenever(postings.findByEntranceIdAndAccount(entranceId, "RECEIVABLE")).thenReturn(rows)

        assertThatThrownBy { service.forUnit(unitId, asOf) }
            .isInstanceOf(NoSuchElementException::class.java).hasMessageContaining("PAYMENT_TERM_DAYS")
        assertThatThrownBy { service.forEntrance(entranceId, asOf) }
            .isInstanceOf(NoSuchElementException::class.java).hasMessageContaining("PAYMENT_TERM_DAYS")
    }

    @Test
    fun `PM-DEBT-002 the service Spring builds takes the payment term from the law, by the debt's date`() {
        val debt = owed(unitId, 20, 3_000)
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(listOf(debt))
        AnnotationConfigApplicationContext().use { context ->
            context.registerBean(PostingRepository::class.java, Supplier { postings })
            context.register(ArrearsService::class.java)
            context.refresh()                                   // fails here if Spring cannot choose a constructor

            val dueOn = debt.valueDate.plusDays(numberOn("PAYMENT_TERM_DAYS", debt.valueDate.toString()).toLong())
            assertThat(context.getBean(ArrearsService::class.java).forUnit(unitId, asOf).oldestDebt).isEqualTo(OldestDebt(dueOn, 20))
        }
    }

    private fun advance(unit: UUID, amount: Long, on: LocalDate) =
        PostingRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ADVANCE", unit, -amount, "EUR", on)

    @Test
    fun `PM-DEBT-001 a unit's advance is netted against what it owes and shown, the debt and its bands stay gross`() {
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(listOf(overdueBy(45, 10_000)))
        whenever(postings.findByUnitIdAndAccount(unitId, "ADVANCE")).thenReturn(
            listOf(advance(unitId, 4_000, asOf.minusDays(60)), advance(unitId, 1_000, asOf.plusDays(1))),   // the second came later
        )
        val report = service.forUnit(unitId, asOf)
        assertThat(report.totalMinor).isEqualTo(10_000)
        assertThat(report.advanceMinor).isEqualTo(4_000)
        assertThat(report.netMinor).isEqualTo(6_000)
        assertThat(report.buckets.associate { it.band to it.amountMinor }["31-60"]).isEqualTo(10_000)
    }

    @Test
    fun `PM-DEBT-001 an advance larger than the debt leaves nothing owed, never a negative`() {
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(listOf(overdueBy(5, 3_000)))
        whenever(postings.findByUnitIdAndAccount(unitId, "ADVANCE")).thenReturn(listOf(advance(unitId, 5_000, asOf.minusDays(1))))
        val report = service.forUnit(unitId, asOf)
        assertThat(report.advanceMinor).isEqualTo(5_000)
        assertThat(report.netMinor).isEqualTo(0)
    }

    @Test
    fun `PM-DEBT-001 an entrance's arrears net each unit's advance, list a covered unit, and leave out a unit holding only an advance`() {
        val entranceId = UUID.randomUUID()
        val covered = UUID.randomUUID()
        val creditOnly = UUID.randomUUID()
        fun owed(unit: UUID, amount: Long) =
            PostingRow(UUID.randomUUID(), entranceId, UUID.randomUUID(), "RECEIVABLE", unit, amount, "EUR", asOf.minusDays(term + 5))
        whenever(postings.findByEntranceIdAndAccount(entranceId, "RECEIVABLE")).thenReturn(listOf(owed(unitId, 8_000), owed(covered, 2_000)))
        whenever(postings.findByEntranceIdAndAccount(entranceId, "ADVANCE")).thenReturn(
            listOf(advance(unitId, 3_000, asOf.minusDays(2)), advance(covered, 2_000, asOf.minusDays(2)), advance(creditOnly, 9_000, asOf.minusDays(2))),
        )
        val report = service.forEntrance(entranceId, asOf)
        assertThat(report.units.map { it.unitId to it.netMinor }).containsExactly(unitId to 5_000L, covered to 0L)
        assertThat(report.totalMinor).isEqualTo(10_000)
        assertThat(report.advanceMinor).isEqualTo(5_000)
        assertThat(report.netMinor).isEqualTo(5_000)
    }

    @Test
    fun `PM-DEBT-001 an entrance counts only the credit that covers each unit's own debt, so total less advance is net`() {
        val entranceId = UUID.randomUUID()
        val overCovered = UUID.randomUUID()
        fun owed(unit: UUID, amount: Long) =
            PostingRow(UUID.randomUUID(), entranceId, UUID.randomUUID(), "RECEIVABLE", unit, amount, "EUR", asOf.minusDays(term + 5))
        whenever(postings.findByEntranceIdAndAccount(entranceId, "RECEIVABLE")).thenReturn(listOf(owed(overCovered, 3_000), owed(unitId, 8_000)))
        whenever(postings.findByEntranceIdAndAccount(entranceId, "ADVANCE")).thenReturn(listOf(advance(overCovered, 5_000, asOf.minusDays(2))))
        val report = service.forEntrance(entranceId, asOf)
        assertThat(report.units.single { it.unitId == overCovered }.advanceMinor).isEqualTo(5_000)   // the unit keeps its whole credit
        assertThat(report.totalMinor).isEqualTo(11_000)
        assertThat(report.advanceMinor).isEqualTo(3_000)                                             // its surplus 2,000 pays no other unit
        assertThat(report.netMinor).isEqualTo(8_000)
    }
}
