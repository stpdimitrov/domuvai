package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
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
    private val store = ChargeRunStore(runs, chargeRuns, aggregates, events)
    private fun version(decisionId: String = "GA-2026-1") = TariffVersionView(UUID.randomUUID(), decisionId, "2026-01", null, emptyList(), emptyList())

    @Test
    fun `PM-FEE-017 a run is not issued while a meter is unread — the period would stay unbilled for good`() {
        val entranceId = UUID.randomUUID()
        val unit = UUID.randomUUID()
        val request = StoredChargeRunRequest(period = "2026-05", legalDate = "2026-05-01")
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
        whenever(runs.compute(entranceId, request)).thenReturn(ComputedRun(unread, listOf(UnitForCharging(unit, "ап. 1", "100.0000", false)), version()))
        assertThatThrownBy { store.issue(entranceId, request) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("no reading for WATER of unit $unit")
        verifyNoInteractions(aggregates, events)                                             // nothing stored, the period stays open
    }

    private val entranceId = UUID.randomUUID()
    private val run = StoredChargeRunRequest(period = "2026-05", legalDate = "2026-05-01")

    @Test
    fun `PM-FEE-012 a period with no version in force is refused and nothing is posted`() {
        whenever(runs.compute(entranceId, run)).thenThrow(IllegalArgumentException("no tariff version of this entrance is in force in 2026-05 (PM-FEE-012)"))
        assertThatThrownBy { store.issue(entranceId, run) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-FEE-012")
        verifyNoInteractions(aggregates, events)                                             // nothing stored, nothing billed
    }

    @Test
    fun `PM-FEE-012 the issued run's basis names the version and its decision`() {
        val unit = UUID.randomUUID()
        val adopted = version("GA-2026-7")
        val computed = computeChargeRun(
            entranceId.toString(),
            listOf(PropertyUnit(unit.toString(), "ап. 1", IdealParts.of("100.0000"), occupants = 0)),
            Tariff(entranceId.toString(), "2026-05", "2026-05-01", listOf(TariffLine(CostStream.MAINTENANCE, AllocationKey.BY_IDEAL_PARTS, "GA-2026-7", totalMinor = 10_000))),
        )
        whenever(runs.compute(entranceId, run)).thenReturn(ComputedRun(computed, listOf(UnitForCharging(unit, "ап. 1", "100.0000", false)), adopted))
        store.issue(entranceId, run)
        val inserted = argumentCaptor<Any>()
        verify(aggregates, atLeastOnce()).insert(inserted.capture())
        val basis = inserted.allValues.filterIsInstance<ChargeRunRow>().single().basis.json
        assertThat(basis).contains("\"versionId\":\"${adopted.id}\"").contains("\"decisionId\":\"GA-2026-7\"")
    }

    @Test
    fun `PM-FEE-012 a period already billed answers as billed`() {
        whenever(chargeRuns.existsByEntranceIdAndPeriod(entranceId, "2026-05")).thenReturn(true)
        assertThatThrownBy { store.issue(entranceId, run) }.isInstanceOf(ChargeRunAlreadyIssued::class.java)
        verify(runs, never()).compute(any(), any())
    }
}
