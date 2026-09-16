package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The occupancy the port exposes, with the repositories mocked — no Spring, no database.
 * Proves the counts a per-person charge depends on, as of the billing period: the headcount
 * on that date (PM-FEE-008), children separated (PM-FEE-005), and animals (PM-BOOK-005).
 */
class UnitsAdapterTest {

    private val units: PropertyUnitRepository = mock()
    private val household: HouseholdMemberRepository = mock()
    private val animals: AnimalRepository = mock()
    private val adapter = UnitsAdapter(units, household, animals)

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
}
