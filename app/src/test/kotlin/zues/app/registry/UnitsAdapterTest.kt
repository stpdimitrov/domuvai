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
 * Proves the counts a per-person charge depends on: the current headcount (PM-FEE-008) and
 * children reported separately (PM-FEE-005).
 */
class UnitsAdapterTest {

    private val units: PropertyUnitRepository = mock()
    private val household: HouseholdMemberRepository = mock()
    private val animals: AnimalRepository = mock()
    private val adapter = UnitsAdapter(units, household, animals)

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()

    @BeforeEach
    fun stubUnit() {
        whenever(units.findByEntranceId(entranceId)).thenReturn(
            listOf(PropertyUnit(unitId, entranceId, "ап. 1", "FLAT", null, BigDecimal("100.0000"), false)),
        )
    }

    private fun member(child: Boolean, movedOut: Boolean = false) = HouseholdMember(
        UUID.randomUUID(), entranceId, unitId, null, child,
        LocalDate.of(2026, 1, 1), if (movedOut) LocalDate.of(2026, 6, 1) else null,
    )

    private fun animal(gone: Boolean = false) = Animal(
        UUID.randomUUID(), entranceId, unitId, "cat", "VP-1",
        LocalDate.of(2026, 1, 1), if (gone) LocalDate.of(2026, 6, 1) else null,
    )

    @Test
    fun `PM-FEE-008 the occupant count reflects current household members`() {
        whenever(household.findByUnitId(unitId)).thenReturn(
            listOf(member(child = false), member(child = false), member(child = false, movedOut = true)),
        )
        val unit = adapter.forEntrance(entranceId).single()
        assertThat(unit.occupants).isEqualTo(2)   // the moved-out member is not counted
    }

    @Test
    fun `PM-FEE-005 children under six are reported separately from the count`() {
        whenever(household.findByUnitId(unitId)).thenReturn(
            listOf(member(child = false), member(child = true), member(child = true)),
        )
        val unit = adapter.forEntrance(entranceId).single()
        assertThat(unit.occupants).isEqualTo(3)
        assertThat(unit.childrenUnder6).isEqualTo(2)
    }

    @Test
    fun `PM-BOOK-005 current animals are counted toward the occupant-equivalent headcount`() {
        whenever(animals.findByUnitId(unitId)).thenReturn(
            listOf(animal(), animal(), animal(gone = true)),
        )
        val unit = adapter.forEntrance(entranceId).single()
        assertThat(unit.animals).isEqualTo(2)   // the departed animal is not counted
    }
}
