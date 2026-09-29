package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * Recording a move-out with the repositories mocked — no Spring, no database. Proves a stay's range
 * closes on the declared day and is stored with the Sofia day it was recorded (PM-BOOK-008); that the
 * fee engine then stops counting it is proved by UnitsAdapterTest, the stored row by
 * BookRetentionPersistenceIT.
 */
class MoveOutTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val household: HouseholdMemberRepository = mock()
    private val lateNight = Clock.fixed(Instant.parse("2026-09-28T21:30:00Z"), ZoneOffset.UTC)   // 00:30 on 29 September in Sofia
    private val registry = RegistryService(aggregates, mock(), mock(), household, mock(), mock(), mock(), lateNight)

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val movedIn = LocalDate.parse("2026-01-10")
    private val movedOut = LocalDate.parse("2026-05-01")
    private val recordedOn = LocalDate.parse("2026-09-29")
    private val resident = HouseholdMember(UUID.randomUUID(), entranceId, unitId, null, false, movedIn, null)
    private val cat = Animal(UUID.randomUUID(), entranceId, unitId, "cat", "VP-1", movedIn, null)
    private val updates = mutableListOf<Any>()

    @BeforeEach
    fun stubReadsAndUpdates() {
        whenever(household.findById(resident.id)).thenReturn(Optional.of(resident))
        whenever(aggregates.findById(cat.id, Animal::class.java)).thenReturn(cat)
        whenever(aggregates.update(any<Any>())).thenAnswer { it.arguments[0].also { row -> updates += row } }
    }

    @Test
    fun `PM-BOOK-008 a resident's move-out closes their occupancy range on the declared day`() {
        registry.endHouseholdStay(entranceId, unitId, resident.id, movedOut)
        assertThat(updates.single()).isEqualTo(resident.copy(validTo = movedOut, endRecordedOn = recordedOn))  // counted through 30 April
    }

    @Test
    fun `PM-BOOK-008 an animal's move-out closes its range the same way`() {
        registry.endAnimalStay(entranceId, unitId, cat.id, movedOut)
        assertThat(updates.single()).isEqualTo(cat.copy(validTo = movedOut, endRecordedOn = recordedOn))
    }

    @Test
    fun `a move-out is corrected by ending the stay again, and the correction is what is recorded`() {
        whenever(household.findById(resident.id))
            .thenReturn(Optional.of(resident.copy(validTo = movedOut, endRecordedOn = LocalDate.parse("2026-05-02"))))
        registry.endHouseholdStay(entranceId, unitId, resident.id, LocalDate.parse("2026-06-01"))
        assertThat(updates.single()).isEqualTo(resident.copy(validTo = LocalDate.parse("2026-06-01"), endRecordedOn = recordedOn))
    }

    @Test
    fun `a move-out not after the move-in is refused, for a resident and for an animal`() {
        assertThatThrownBy { registry.endHouseholdStay(entranceId, unitId, resident.id, movedIn) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { registry.endAnimalStay(entranceId, unitId, cat.id, movedIn) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(updates).isEmpty()
    }

    @Test
    fun `a resident or an animal of another unit or entrance is not found`() {
        val elsewhere = UUID.randomUUID()
        for ((entrance, unit) in listOf(elsewhere to unitId, entranceId to elsewhere)) {
            assertThatThrownBy { registry.endHouseholdStay(entrance, unit, resident.id, movedOut) }
                .isInstanceOf(NoSuchElementException::class.java)
            assertThatThrownBy { registry.endAnimalStay(entrance, unit, cat.id, movedOut) }
                .isInstanceOf(NoSuchElementException::class.java)
        }
        assertThat(updates).isEmpty()
    }
}
