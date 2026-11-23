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
    private var rows: List<TariffVersionRow> = emptyList()
    private val service = ChargeRunService(units, tariffVersionsOver { rows })

    /** A run of [period] after the assembly adopted [lines] and [consumption] in a version in force since January. */
    private fun adopted(
        period: String,
        legalDate: String,
        businessMultiplier: Int? = null,
        lines: List<AdoptedLine>,
        consumption: List<MeteredPrice> = emptyList(),
        readings: List<ReadingRequest>? = null,
    ): StoredChargeRunRequest {
        rows = listOf(versionRow(entranceId, "2026-01", lines = lines, consumption = consumption))
        return StoredChargeRunRequest(period, legalDate, businessMultiplier, readings)
    }

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
    fun `PM-FEE-011 a concierge line reaches the engine named, so its key is checked against maintenance's`() {
        val request = adopted(
            period = "2026-05", legalDate = "2026-05-01",
            lines = listOf(
                AdoptedLine("MAINTENANCE", "BY_IDEAL_PARTS", totalMinor = 10_000),
                AdoptedLine("MAINTENANCE", "PER_UNIT", rateMinor = 500, item = "CONCIERGE"),
            ),
        )
        assertThatThrownBy { service.preview(entranceId, request) }.hasMessageContaining("PM-FEE-011")
    }

    @Test
    fun `a by-ideal-parts run allocates the pot across the stored units`() {
        val response = service.preview(
            entranceId,
            adopted(
                period = "2026-05", legalDate = "2026-05-01",
                lines = listOf(AdoptedLine("MAINTENANCE", "BY_IDEAL_PARTS", totalMinor = 10_000)),
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
            adopted(
                period = "2026-05", legalDate = "2026-05-01",
                lines = listOf(AdoptedLine("MANAGEMENT", "PER_PERSON", rateMinor = 500)),
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
                adopted(
                    period = "2026-05", legalDate = "2026-05-01",
                    lines = listOf(AdoptedLine("MANAGEMENT", "PER_PERSON", rateMinor = 500)),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("occupant")
    }

    @Test
    fun `an entrance with no registered units is rejected`() {
        assertThatThrownBy {
            service.preview(
                UUID.randomUUID(),
                adopted("2026-05", "2026-05-01", lines = emptyList()),
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
            adopted(
                period = "2026-05", legalDate = "2026-05-01",
                lines = listOf(AdoptedLine("MANAGEMENT", "PER_PERSON", rateMinor = 500)),
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
            adopted(
                period = "2026-05", legalDate = "2026-05-01",
                lines = listOf(AdoptedLine("MANAGEMENT", "PER_PERSON", rateMinor = 500)),
            ),
        )
        // one resident plus one animal-equivalent — the animal lifts the chargeable count
        assertThat(response.charges.single().chargeablePersons).isGreaterThan(1)
    }

    @Test
    fun `PM-FEE-017 a stored run bills a registered unit's reading, and refuses one for a unit it does not hold`() {
        val request = adopted(
            period = "2026-05", legalDate = "2026-05-01",
            lines = listOf(AdoptedLine("MAINTENANCE", "BY_IDEAL_PARTS", totalMinor = 10_000)),
            consumption = listOf(MeteredPrice("HEATING", 25)),
            readings = listOf(ReadingRequest(u2.toString(), "HEATING", "2")),
        )
        val response = service.preview(entranceId, request)
        assertThat(response.charges.single { it.unitId == u2.toString() }.lines.single { it.item == "HEATING" }.amountMinor).isEqualTo(50)
        assertThat(response.missingReadings).containsExactly(MissingReadingResponse(u1.toString(), "HEATING"))
        assertThatThrownBy { service.preview(entranceId, request.copy(readings = listOf(ReadingRequest(UUID.randomUUID().toString(), "HEATING", "2")))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- PM-FEE-010: who pays the assembly's multiple ---

    private val multiple = numberOn("BUSINESS_USE_MULTIPLIER_MAX", "2026-05-01").toInt()   // read, never typed
    private val shop = UUID.randomUUID()      // business, reached through the common parts
    private val corner = UUID.randomUUID()    // business, with its own street entrance
    private val door = UUID.randomUUID()      // its own street entrance, no business

    private fun business(vararg units: UnitForCharging) {
        whenever(this.units.forEntrance(eq(entranceId), any())).thenReturn(units.toList())
    }
    private fun shopUnit() = UnitForCharging(shop, "магазин 1", "40.0000", separateEntrance = false, businessUse = true)
    private fun cornerUnit() = UnitForCharging(corner, "магазин 2", "30.0000", separateEntrance = true, businessUse = true)
    private fun doorUnit() = UnitForCharging(door, "ап. 1", "20.0000", separateEntrance = true, businessUse = false)
    private fun flatUnit() = UnitForCharging(u2, "ап. 2", "10.0000", separateEntrance = false, businessUse = false)

    private fun perUnit(multiplier: Int?, stream: String = "MAINTENANCE") = adopted(
        period = "2026-05", legalDate = "2026-05-01", businessMultiplier = multiplier,
        lines = listOf(AdoptedLine(stream, "PER_UNIT", rateMinor = 1_000)),
    )

    @Test
    fun `PM-FEE-010 business through the common parts pays the assembly's multiple — with its own street entrance, or with no business, a unit pays the standard rate`() {
        business(shopUnit(), cornerUnit(), doorUnit(), flatUnit())
        val byUnit = service.preview(entranceId, perUnit(multiple)).charges.associate { it.unitId to it.totalMinor }
        assertThat(byUnit).isEqualTo(
            mapOf(shop.toString() to 1_000L * multiple, corner.toString() to 1_000L, door.toString() to 1_000L, u2.toString() to 1_000L),
        )
    }

    @Test
    fun `PM-FEE-010 a run that would charge the multiple with none given is refused, naming the units it would reach`() {
        business(shopUnit(), cornerUnit(), doorUnit(), flatUnit())
        assertThatThrownBy { service.preview(entranceId, perUnit(null)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PM-FEE-010").hasMessageContaining("магазин 1")
            .satisfies({ assertThat(it.message).doesNotContain("магазин 2", "ап. 1", "ап. 2") })
    }

    @Test
    fun `PM-FEE-010 a run that charges the multiple to no one needs none`() {
        business(cornerUnit(), doorUnit(), flatUnit().copy(idealParts = "50.0000"))        // no unit pays it
        assertThat(service.preview(entranceId, perUnit(null)).charges.map { it.totalMinor }).containsOnly(1_000L)

        business(shopUnit().copy(idealParts = "100.0000"))                                 // the repair fund is never multiplied
        val fund = adopted(
            period = "2026-05", legalDate = "2026-05-01",
            lines = listOf(AdoptedLine("REPAIR_FUND", "BY_IDEAL_PARTS", totalMinor = 5_000)),
        )
        assertThat(service.preview(entranceId, fund).totalMinor).isEqualTo(5_000)

        val water = adopted(                                                // nor is a metered cost
            period = "2026-05", legalDate = "2026-05-01", lines = emptyList(),
            consumption = listOf(MeteredPrice("WATER", 230)),
            readings = listOf(ReadingRequest(shop.toString(), "WATER", "10.000")),
        )
        assertThat(service.preview(entranceId, water).totalMinor).isEqualTo(2_300)
    }

    // --- PM-FEE-012: the amounts are the version in force's ---

    private fun maintenance(totalMinor: Long) = AdoptedLine("MAINTENANCE", "BY_IDEAL_PARTS", totalMinor = totalMinor)
    private fun previewIn(period: String) = service.preview(entranceId, StoredChargeRunRequest(period, "$period-01"))

    @Test
    fun `PM-FEE-012 a run bills the amounts of the version in force, every line under its decision`() {
        rows = listOf(versionRow(entranceId, "2026-01", decisionId = "GA-2026-7", lines = listOf(maintenance(10_000)), consumption = listOf(MeteredPrice("WATER", 230))))
        val response = service.preview(entranceId, StoredChargeRunRequest("2026-05", "2026-05-01", readings = listOf(ReadingRequest(u1.toString(), "WATER", "1"), ReadingRequest(u2.toString(), "WATER", "2"))))
        assertThat(response.totalMinor).isEqualTo(10_000 + 230 * 3)
        assertThat(response.charges.flatMap { it.lines }.map { it.decisionId }).containsOnly("GA-2026-7")
    }

    @Test
    fun `PM-FEE-012 a period with no version in force is refused`() {
        assertThatThrownBy { previewIn("2026-05") }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("2026-05").hasMessageContaining("PM-FEE-012")
        rows = listOf(versionRow(entranceId, "2026-06", lines = listOf(maintenance(10_000))))   // adopted, but not yet in force
        assertThatThrownBy { previewIn("2026-05") }.hasMessageContaining("PM-FEE-012")
    }

    @Test
    fun `PM-FEE-012 a run in a month after a newer version begins bills the newer one`() {
        rows = listOf(
            versionRow(entranceId, "2026-01", lines = listOf(maintenance(10_000))),
            versionRow(entranceId, "2026-04", lines = listOf(maintenance(12_000))),
        )
        assertThat(previewIn("2026-03").totalMinor).isEqualTo(10_000)
        assertThat(previewIn("2026-04").totalMinor).isEqualTo(12_000)
        assertThat(previewIn("2026-09").totalMinor).isEqualTo(12_000)
    }

    @Test
    fun `PM-FEE-012 a version whose validTo has passed is not used`() {
        rows = listOf(versionRow(entranceId, "2026-01", to = "2026-03", lines = listOf(maintenance(10_000))))
        assertThat(previewIn("2026-03").totalMinor).isEqualTo(10_000)                             // its last month
        assertThatThrownBy { previewIn("2026-04") }.hasMessageContaining("PM-FEE-012")
    }
}
