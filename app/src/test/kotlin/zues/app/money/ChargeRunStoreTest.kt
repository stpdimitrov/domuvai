package zues.app.money

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.app.assembly.GaDecisions
import zues.app.registry.UnitForCharging
import zues.charges.ConsumptionLine
import zues.charges.PropertyUnit
import zues.charges.Tariff
import zues.charges.TariffLine
import zues.charges.computeChargeRun
import zues.kernel.IdealParts
import zues.law.AllocationKey
import zues.law.CostItem
import zues.law.CostStream
import java.util.UUID

/** Issuing with the collaborators mocked — no Spring, no database. ChargeRunPersistenceIT proves the stored rows. */
class ChargeRunStoreTest {

    private val runs: ChargeRunService = mock()
    private val chargeRuns: ChargeRunRepository = mock()
    private val aggregates: JdbcAggregateTemplate = mock()
    private val events: ApplicationEventPublisher = mock()
    private val decisions: GaDecisions = mock { on { passed(any(), any()) } doReturn true }
    private val store = ChargeRunStore(runs, chargeRuns, aggregates, events, decisions)

    @Test
    fun `PM-FEE-017 a run is not issued while a meter is unread — the period would stay unbilled for good`() {
        val entranceId = UUID.randomUUID()
        val unit = UUID.randomUUID()
        val request = StoredChargeRunRequest(
            period = "2026-05", legalDate = "2026-05-01",
            lines = listOf(TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
            consumption = listOf(ConsumptionLineRequest("WATER", 230, "GA-2026-9")),
        )
        val unread = computeChargeRun(
            entranceId.toString(),
            listOf(PropertyUnit(unit.toString(), "ап. 1", IdealParts.of("100.0000"), occupants = 0)),
            Tariff(
                entranceId.toString(), "2026-05", "2026-05-01",
                listOf(TariffLine(CostStream.MAINTENANCE, AllocationKey.BY_IDEAL_PARTS, "GA-2026-1", totalMinor = 10_000)),
                consumption = listOf(ConsumptionLine(CostItem.WATER, 230, "GA-2026-9")),
            ),
        )
        whenever(chargeRuns.existsByEntranceIdAndPeriod(entranceId, "2026-05")).thenReturn(false)
        whenever(runs.compute(entranceId, request)).thenReturn(ComputedRun(unread, listOf(UnitForCharging(unit, "ап. 1", "100.0000", false))))
        assertThatThrownBy { store.issue(entranceId, request) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("no reading for WATER of unit $unit")
        verifyNoInteractions(aggregates, events)                                             // nothing stored, the period stays open
    }

    private val entranceId = UUID.randomUUID()
    private val run = StoredChargeRunRequest(
        period = "2026-05", legalDate = "2026-05-01",
        lines = listOf(
            TariffLineRequest("MANAGEMENT", "PER_UNIT", "decided", rateMinor = 500),
            TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "decided", totalMinor = 10_000),
        ),
        consumption = listOf(ConsumptionLineRequest("WATER", 230, "metered")),
    )

    @Test
    fun `PM-FEE-012 one line without a passed decision of the entrance refuses the whole issue, naming the id`() {
        whenever(decisions.passed(entranceId, "metered")).thenReturn(false)
        assertThatThrownBy { store.issue(entranceId, run) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("metered").hasMessageContaining("PM-FEE-012")
            .hasMessageNotContaining("decided")
        verify(runs, never()).compute(any(), any())
        verifyNoInteractions(aggregates, events)                                             // nothing stored, nothing billed
    }

    @Test
    fun `PM-FEE-012 a period already billed answers as billed, whatever its lines cite`() {
        whenever(chargeRuns.existsByEntranceIdAndPeriod(entranceId, "2026-05")).thenReturn(true)
        whenever(decisions.passed(any(), any())).thenReturn(false)
        assertThatThrownBy { store.issue(entranceId, run) }.isInstanceOf(ChargeRunAlreadyIssued::class.java)
    }
}
