package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import zues.app.registry.UnitForCharging
import zues.app.registry.Units
import zues.law.numberOn
import java.util.UUID

/**
 * The registry-sourced service with the `registry` port mocked — no Spring, no database.
 * Proves the cross-module read path and the occupancy guard run in the gate pack locally.
 */
class ChargeRunServiceTest {

    private val entranceId = UUID.randomUUID()
    private val u1 = UUID.randomUUID()
    private val u2 = UUID.randomUUID()
    private val units: Units = mock()
    private val service = ChargeRunService(units)

    @BeforeEach
    fun stubUnits() {
        whenever(units.forEntrance(eq(entranceId), any())).thenReturn(
            listOf(
                UnitForCharging(u1, "ап. 1", "60.0000", false),
                UnitForCharging(u2, "ап. 2", "40.0000", false),
            ),
        )
    }

    @Test
    fun `a by-ideal-parts run allocates the pot across the stored units`() {
        val response = service.preview(
            entranceId,
            StoredChargeRunRequest(
                period = "2026-05", legalDate = "2026-05-01",
                lines = listOf(TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
            ),
        )
        assertThat(response.totalMinor).isEqualTo(10_000)
        val byUnit = response.charges.associate { it.unitId to it.totalMinor }
        assertThat(byUnit[u1.toString()]).isEqualTo(6_000)
        assertThat(byUnit[u2.toString()]).isEqualTo(4_000)
    }

    @Test
    fun `PM-FEE-008 a per-person run bills each unit by its chargeable occupants`() {
        whenever(units.forEntrance(eq(entranceId), any())).thenReturn(
            listOf(
                UnitForCharging(u1, "ап. 1", "60.0000", false, occupants = 3),
                UnitForCharging(u2, "ап. 2", "40.0000", false, occupants = 1),
            ),
        )
        val response = service.preview(
            entranceId,
            StoredChargeRunRequest(
                period = "2026-05", legalDate = "2026-05-01",
                lines = listOf(TariffLineRequest("MANAGEMENT", "PER_PERSON", "GA-2026-1", rateMinor = 500)),
            ),
        )
        assertThat(response.totalMinor).isEqualTo(2_000)   // 500 × (3 + 1) persons
        val byUnit = response.charges.associate { it.unitId to it.totalMinor }
        assertThat(byUnit[u1.toString()]).isEqualTo(1_500)
        assertThat(byUnit[u2.toString()]).isEqualTo(500)
    }

    @Test
    fun `a per-person run with no chargeable occupants is refused`() {
        // the default stub registers no occupants
        assertThatThrownBy {
            service.preview(
                entranceId,
                StoredChargeRunRequest(
                    period = "2026-05", legalDate = "2026-05-01",
                    lines = listOf(TariffLineRequest("MANAGEMENT", "PER_PERSON", "GA-2026-1", rateMinor = 500)),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("occupant")
    }

    @Test
    fun `an entrance with no registered units is rejected`() {
        assertThatThrownBy {
            service.preview(
                UUID.randomUUID(),
                StoredChargeRunRequest("2026-05", "2026-05-01", lines = emptyList()),
            )
        }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `PM-FEE-006 a unit absent beyond the window is exempt from a per-person line`() {
        val exemptionDays = numberOn("ABSENCE_EXEMPTION_DAYS", "2026-05-01").toInt()
        whenever(units.forEntrance(eq(entranceId), any())).thenReturn(
            listOf(
                UnitForCharging(u1, "ап. 1", "60.0000", false, occupants = 2, absentDays = exemptionDays + 1),
                UnitForCharging(u2, "ап. 2", "40.0000", false, occupants = 1),
            ),
        )
        val response = service.preview(
            entranceId,
            StoredChargeRunRequest(
                period = "2026-05", legalDate = "2026-05-01",
                lines = listOf(TariffLineRequest("MANAGEMENT", "PER_PERSON", "GA-2026-1", rateMinor = 500)),
            ),
        )
        val byUnit = response.charges.associate { it.unitId to it.totalMinor }
        assertThat(byUnit[u1.toString()]).isEqualTo(0)      // absent beyond the window — not billed per person
        assertThat(byUnit[u2.toString()]).isEqualTo(500)    // billed normally
        assertThat(response.totalMinor).isEqualTo(500)
    }

    @Test
    fun `PM-FEE-009 an animal adds an occupant-equivalent to a per-person charge`() {
        whenever(units.forEntrance(eq(entranceId), any())).thenReturn(
            listOf(UnitForCharging(u1, "ап. 1", "100.0000", false, occupants = 1, animals = 1)),
        )
        val response = service.preview(
            entranceId,
            StoredChargeRunRequest(
                period = "2026-05", legalDate = "2026-05-01",
                lines = listOf(TariffLineRequest("MANAGEMENT", "PER_PERSON", "GA-2026-1", rateMinor = 500)),
            ),
        )
        // one resident plus one animal-equivalent — the animal lifts the chargeable count
        assertThat(response.charges.single().chargeablePersons).isGreaterThan(1)
    }
}
