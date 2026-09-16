package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import zues.charges.PropertyUnit
import zues.charges.Tariff
import zues.charges.TariffLine
import zues.charges.computeChargeRun
import zues.kernel.IdealParts
import zues.law.AllocationKey
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
}
