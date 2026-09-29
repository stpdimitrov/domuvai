package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import zues.charges.PropertyUnit
import zues.charges.Tariff
import zues.charges.TariffLine
import zues.charges.computeChargeRun
import zues.charges.ConsumptionLine
import zues.kernel.IdealParts
import zues.law.AllocationKey
import zues.law.CostItem
import zues.law.CostStream
import java.time.LocalDate
import java.util.UUID

/**
 * Pure tests of the double-entry derivation — no Spring, no database. They prove the journal
 * balances (ADR-006) and that income lands on the condominium's ledger, split by stream
 * (PM-FEE-020), before the database ever sees a posting.
 */
class PostingsTest {

    private val entranceId = UUID.randomUUID()
    private val journalId = UUID.randomUUID()
    private val u1 = UUID.randomUUID()
    private val u2 = UUID.randomUUID()

    private fun run() = computeChargeRun(
        entranceId.toString(),
        listOf(
            PropertyUnit(u1.toString(), "ап. 1", IdealParts.of("60.0000"), occupants = 0),
            PropertyUnit(u2.toString(), "ап. 2", IdealParts.of("40.0000"), occupants = 0),
        ),
        Tariff(
            entranceId.toString(), "2026-05", "2026-05-01",
            listOf(
                TariffLine(CostStream.MANAGEMENT, AllocationKey.BY_IDEAL_PARTS, "GA-2026-1", totalMinor = 10_000),
                TariffLine(CostStream.MAINTENANCE, AllocationKey.BY_IDEAL_PARTS, "GA-2026-1", totalMinor = 20_000),
            ),
        ),
    )

    private fun postings() = Ledger.forRun(run(), entranceId, journalId, LocalDate.parse("2026-05-01"))

    @Test
    fun `PM-FEE-001 concierge income posts to the maintenance root, so the ledger keeps three income roots`() {
        val withConcierge = run().basis.tariff.let { t ->
            t.copy(lines = t.lines + TariffLine(CostStream.MAINTENANCE, AllocationKey.BY_IDEAL_PARTS, "GA-2026-2", totalMinor = 5_000, item = CostItem.CONCIERGE))
        }
        val run = computeChargeRun(entranceId.toString(), run().basis.units, withConcierge)
        val income = Ledger.forRun(run, entranceId, journalId, LocalDate.parse("2026-05-01")).filter { it.unitId == null }
        assertThat(income.map { it.account }).containsExactlyInAnyOrder("INCOME:MANAGEMENT", "INCOME:MAINTENANCE")
        assertThat(income.single { it.account == "INCOME:MAINTENANCE" }.amountMinor).isEqualTo(-25_000)   // 20 000 + the concierge's 5 000
    }

    @Test
    fun `the double-entry journal balances to zero (ADR-006)`() {
        assertThat(postings().sumOf { it.amountMinor }).isZero()
    }

    @Test
    fun `PM-FEE-020 income is credited to the condominium ledger, split by stream`() {
        val income = postings().filter { it.amountMinor < 0 }
        assertThat(income).allSatisfy {
            assertThat(it.account).startsWith("INCOME:")
            assertThat(it.entranceId).isEqualTo(entranceId)   // the condominium's ledger, not a manager's
            assertThat(it.unitId).isNull()
        }
        assertThat(income.map { it.account }).containsExactlyInAnyOrder("INCOME:MANAGEMENT", "INCOME:MAINTENANCE")
        assertThat(-income.sumOf { it.amountMinor }).isEqualTo(30_000)   // the whole run is credited
    }

    @Test
    fun `PM-FUND-009 a payout's journal credits the fund's bank account, debits its spending and balances to zero`() {
        val works = FundDisbursementRow(
            UUID.randomUUID(), entranceId, UUID.randomUUID(), 12_345, "EUR", "WORKS", "GA-2026-7", null, null,
            UUID.randomUUID(), "PAID", LocalDate.parse("2026-09-01"),
        )
        val payout = Ledger.forPayout(works, LocalDate.parse("2026-09-15"))
        assertThat(payout.map { it.account to it.amountMinor }).containsExactly("BANK:REPAIR_RENEWAL" to -12_345L, "EXPENSE:REPAIR_FUND" to 12_345L)
        assertThat(payout.sumOf { it.amountMinor }).isZero()
        assertThat(payout).allSatisfy {
            assertThat(it.journalId).isEqualTo(works.id)                     // the payout is found by its disbursement
            assertThat(it.entranceId).isEqualTo(entranceId)
            assertThat(it.valueDate).isEqualTo(LocalDate.parse("2026-09-15"))  // the bank's value date, not the sign-off's
            assertThat(it.unitId).isNull()
        }
    }

    @Test
    fun `PM-FEE-017 a metered line is the unit's receivable and maintenance income, and the journal still balances`() {
        val metered = computeChargeRun(
            entranceId.toString(),
            listOf(PropertyUnit(u1.toString(), "ап. 1", IdealParts.of("100.0000"), occupants = 0, readings = mapOf(CostItem.WATER to 2_000L))),
            Tariff(
                entranceId.toString(), "2026-05", "2026-05-01",
                listOf(TariffLine(CostStream.MAINTENANCE, AllocationKey.BY_IDEAL_PARTS, "GA-2026-1", totalMinor = 10_000)),
                consumption = listOf(ConsumptionLine(CostItem.WATER, 150, "GA-2026-9")),
            ),
        )
        val journal = Ledger.forRun(metered, entranceId, journalId, LocalDate.parse("2026-05-01"))
        assertThat(journal.sumOf { it.amountMinor }).isZero()
        assertThat(journal.filter { it.account == "RECEIVABLE" }.sumOf { it.amountMinor }).isEqualTo(10_300)          // 100.00 + 2 m³ × 1.50
        assertThat(journal.single { it.account == "INCOME:MAINTENANCE" }.amountMinor).isEqualTo(-10_300)
    }
}
