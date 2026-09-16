package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import zues.law.numberOn
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The occupancy the port exposes, with the repositories mocked — no Spring, no database.
 * Proves the counts a per-person charge depends on, as of the billing period: the headcount
 * on that date (PM-FEE-008), children separated (PM-FEE-005), animals (PM-BOOK-005), and the
 * qualifying absence a filed declaration puts on record for the year (PM-FEE-006/007).
 */
class UnitsAdapterTest {

    private val units: PropertyUnitRepository = mock()
    private val household: HouseholdMemberRepository = mock()
    private val animals: AnimalRepository = mock()
    private val absences: AbsenceDeclarationRepository = mock()
    private val adapter = UnitsAdapter(units, household, animals, absences)

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val period = LocalDate.of(2026, 5, 1)

    @BeforeEach
    fun stubUnit() {
        whenever(units.findByEntranceId(entranceId)).thenReturn(
            listOf(PropertyUnit(unitId, entranceId, "ап. 1", "FLAT", null, BigDecimal("100.0000"), false)),
        )
    }

    private fun member(child: Boolean, from: LocalDate = LocalDate.of(2026, 1, 1), to: LocalDate? = null) =
        HouseholdMember(UUID.randomUUID(), entranceId, unitId, null, child, from, to)

    private fun animal(from: LocalDate = LocalDate.of(2026, 1, 1), to: LocalDate? = null) =
        Animal(UUID.randomUUID(), entranceId, unitId, "cat", "VP-1", from, to)

    private fun absence(from: LocalDate, to: LocalDate, filedOn: LocalDate) =
        AbsenceDeclaration(UUID.randomUUID(), entranceId, unitId, from, to, filedOn)

    private val exemptionDays = numberOn("ABSENCE_EXEMPTION_DAYS", period.toString()).toInt()
    private val graceDays = numberOn("ABSENCE_DECLARATION_GRACE_DAYS", period.toString()).toLong()

    @Test
    fun `PM-FEE-008 occupants are counted as of the billing period, not today`() {
        whenever(household.findByUnitId(unitId)).thenReturn(
            listOf(
                member(child = false),                                   // resident through the period
                member(child = false),
                member(child = false, to = LocalDate.of(2026, 3, 1)),    // left before the period
                member(child = false, from = LocalDate.of(2026, 8, 1)),  // arrived after the period
            ),
        )
        val unit = adapter.forEntrance(entranceId, period).single()
        assertThat(unit.occupants).isEqualTo(2)   // only those in residence on 2026-05-01
    }

    @Test
    fun `PM-FEE-005 children under six are reported separately from the count`() {
        whenever(household.findByUnitId(unitId)).thenReturn(
            listOf(member(child = false), member(child = true), member(child = true)),
        )
        val unit = adapter.forEntrance(entranceId, period).single()
        assertThat(unit.occupants).isEqualTo(3)
        assertThat(unit.childrenUnder6).isEqualTo(2)
    }

    @Test
    fun `PM-BOOK-005 animals in residence for the period are counted`() {
        whenever(animals.findByUnitId(unitId)).thenReturn(
            listOf(animal(), animal(), animal(to = LocalDate.of(2026, 3, 1))),
        )
        val unit = adapter.forEntrance(entranceId, period).single()
        assertThat(unit.animals).isEqualTo(2)   // the departed animal is not counted for this period
    }

    @Test
    fun `PM-FEE-006 a filed absence is surfaced as its days, enough to exceed the statutory window`() {
        val from = LocalDate.of(2026, 2, 1)
        val to = LocalDate.of(2026, 4, 1)               // 59 days, all within 2026
        whenever(absences.findByUnitId(unitId)).thenReturn(listOf(absence(from, to, filedOn = from)))
        val unit = adapter.forEntrance(entranceId, period).single()
        assertThat(unit.absentDays).isEqualTo(ChronoUnit.DAYS.between(from, to).toInt())
        assertThat(unit.absentDays).isGreaterThan(exemptionDays)   // enough to exempt — the engine decides
    }

    @Test
    fun `PM-FEE-006 absence is clipped to the run's calendar year`() {
        // A span straddling New Year contributes only the days that fall in 2026.
        val from = LocalDate.of(2025, 12, 1)
        val to = LocalDate.of(2026, 1, 16)
        whenever(absences.findByUnitId(unitId)).thenReturn(listOf(absence(from, to, filedOn = from)))
        val unit = adapter.forEntrance(entranceId, period).single()
        val daysIn2026 = ChronoUnit.DAYS.between(LocalDate.of(2026, 1, 1), to).toInt()
        assertThat(unit.absentDays).isEqualTo(daysIn2026)   // the December 2025 days do not count for 2026
    }

    @Test
    fun `PM-FEE-007 an unfiled absence is not exempt — no declaration, no absent days`() {
        // No declaration on record: the guard is that the exemption requires a filed declaration.
        val unit = adapter.forEntrance(entranceId, period).single()
        assertThat(unit.absentDays).isEqualTo(0)
    }

    @Test
    fun `PM-FEE-007 a declaration filed beyond the grace window is not applied`() {
        val from = LocalDate.of(2026, 2, 1)
        val to = LocalDate.of(2026, 3, 1)               // 28 days, first day back 1 Mar
        val cutoff = to.plusDays(graceDays)             // the last day a filing is still timely

        // filed one day past the window: dropped, as if never filed
        whenever(absences.findByUnitId(unitId)).thenReturn(listOf(absence(from, to, filedOn = cutoff.plusDays(1))))
        assertThat(adapter.forEntrance(entranceId, period).single().absentDays).isEqualTo(0)

        // filed exactly on the cutoff: still applied
        whenever(absences.findByUnitId(unitId)).thenReturn(listOf(absence(from, to, filedOn = cutoff)))
        assertThat(adapter.forEntrance(entranceId, period).single().absentDays)
            .isEqualTo(ChronoUnit.DAYS.between(from, to).toInt())
    }
}
