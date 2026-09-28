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
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Recording a move-out with the repositories mocked — no Spring, no database. Proves a stay's range
 * closes on the declared day (PM-BOOK-008); that the fee engine then stops counting it is proved by
 * UnitsAdapterTest, and the closed range against the schema by BookRetentionPersistenceIT.
 */
class MoveOutTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val household: HouseholdMemberRepository = mock()
    private val registry = RegistryService(aggregates, mock(), mock(), household, mock(), mock(), mock(), Clock.systemUTC())

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val movedIn = LocalDate.parse("2026-01-10")
    private val movedOut = LocalDate.parse("2026-05-01")
    private val resident = HouseholdMember(UUID.randomUUID(), entranceId, unitId, null, false, movedIn, null)
    private val cat = Animal(UUID.randomUUID(), entranceId, unitId, "cat", "VP-1", movedIn, null)

    @BeforeEach
    fun stubReadsAndUpdates() {
        whenever(household.findById(resident.id)).thenReturn(Optional.of(resident))
        whenever(aggregates.findById(cat.id, Animal::class.java)).thenReturn(cat)
        whenever(aggregates.update(any<Any>())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `PM-BOOK-008 a resident's move-out closes their occupancy range on the declared day`() {
        assertThat(registry.endHouseholdStay(entranceId, unitId, resident.id, movedOut))
            .isEqualTo(resident.copy(validTo = movedOut))                  // counted through 30 April, not after
    }

    @Test
    fun `PM-BOOK-008 an animal's move-out closes its range the same way`() {
        assertThat(registry.endAnimalStay(entranceId, unitId, cat.id, movedOut)).isEqualTo(cat.copy(validTo = movedOut))
    }

    @Test
    fun `a move-out not after the move-in is refused, and a stay already ended is not ended again`() {
        assertThatThrownBy { registry.endHouseholdStay(entranceId, unitId, resident.id, movedIn) }
            .isInstanceOf(IllegalArgumentException::class.java)
        whenever(household.findById(resident.id)).thenReturn(Optional.of(resident.copy(validTo = LocalDate.parse("2026-03-01"))))
        assertThatThrownBy { registry.endHouseholdStay(entranceId, unitId, resident.id, movedOut) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a resident or an animal of another unit or entrance is not found`() {
        assertThatThrownBy { registry.endHouseholdStay(entranceId, UUID.randomUUID(), resident.id, movedOut) }
            .isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { registry.endAnimalStay(UUID.randomUUID(), unitId, cat.id, movedOut) }
            .isInstanceOf(NoSuchElementException::class.java)
    }
}
