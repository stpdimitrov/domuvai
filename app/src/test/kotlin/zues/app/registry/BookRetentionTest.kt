package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import java.time.LocalDate
import java.util.UUID

/**
 * The book's retention with the repositories mocked — no Spring, no database. Proves each field
 * group is anonymised only once its own window has passed since the stay ended — and since the end
 * was recorded, so a backdated move-out cannot bring it forward — that anonymising removes the
 * identifier and keeps the facts a charge was computed from, and that a current stay is never
 * touched (PM-BOOK-010). The windows are the owner's 3 months, read from `:law`.
 */
class BookRetentionTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val household: HouseholdMemberRepository = mock()
    private val animals: AnimalRepository = mock()
    private val retention = BookRetentionService(aggregates, household, animals)

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val movedOut = LocalDate.parse("2026-06-30")               // recorded the same day; the window closes 30 September
    private val updates = mutableListOf<Any>()

    private fun resident(partyId: UUID?, validTo: LocalDate?, recorded: LocalDate? = validTo) =
        HouseholdMember(UUID.randomUUID(), entranceId, unitId, partyId, true, LocalDate.parse("2024-03-01"), validTo, endRecordedOn = recorded)

    private fun animal(passport: String?, validTo: LocalDate?, recorded: LocalDate? = validTo) =
        Animal(UUID.randomUUID(), entranceId, unitId, "cat", passport, LocalDate.parse("2024-01-10"), validTo, recorded)

    private fun holding(residents: List<HouseholdMember> = emptyList(), pets: List<Animal> = emptyList()) {
        whenever(household.findByEntranceId(entranceId)).thenReturn(residents)
        whenever(animals.findByEntranceId(entranceId)).thenReturn(pets)
    }

    private fun on(day: String, by: BookRetentionService = retention) = by.anonymiseDue(entranceId, LocalDate.parse(day))

    @BeforeEach
    fun entranceAndUpdates() {
        whenever(aggregates.existsById(entranceId, Entrance::class.java)).thenReturn(true)
        whenever(aggregates.update(any<Any>())).thenAnswer { it.arguments[0].also { row -> updates += row } }
    }

    @Test
    fun `PM-BOOK-010 a former occupant's household record is anonymised after the retention window`() {
        val former = resident(partyId = UUID.randomUUID(), validTo = movedOut)
        holding(residents = listOf(former))

        assertThat(on("2026-09-29").householdUnlinked).isZero()            // a day short of three months
        assertThat(updates).isEmpty()

        assertThat(on("2026-09-30").householdUnlinked).isEqualTo(1)
        assertThat(updates.single()).isEqualTo(former.copy(partyId = null))  // the person goes; unit, dates, child flag stay
    }

    @Test
    fun `PM-BOOK-010 a departed animal loses its passport number after the window, and keeps its species and dates`() {
        val gone = animal("VP-2231", validTo = movedOut)
        holding(pets = listOf(gone))

        assertThat(on("2026-09-30").animalPassportsCleared).isEqualTo(1)
        assertThat(updates.single()).isEqualTo(gone.copy(vetPassportNo = null))
    }

    @Test
    fun `PM-BOOK-010 a backdated move-out does not bring anonymisation forward`() {
        holding(pets = listOf(animal("VP-9", validTo = LocalDate.parse("2026-01-02"), recorded = LocalDate.parse("2026-09-29"))))

        assertThat(on("2026-10-01").animalPassportsCleared).isZero()      // nine months after the declared day, two after the record
        assertThat(on("2026-12-29").animalPassportsCleared).isEqualTo(1)   // three months after it was recorded
    }

    @Test
    fun `PM-BOOK-010 each field group runs on its own window`() {
        val longerForAnimals = object : BookRetentionService(aggregates, household, animals) {
            override fun windowMonths(constant: String, on: LocalDate) = if (constant == "BOOK_RETENTION_ANIMAL_MONTHS") 6L else 3L
        }
        holding(residents = listOf(resident(UUID.randomUUID(), validTo = movedOut)), pets = listOf(animal("VP-3", validTo = movedOut)))

        val applied = on("2026-09-30", by = longerForAnimals)

        assertThat(applied.householdUnlinked).isEqualTo(1)
        assertThat(applied.animalPassportsCleared).isZero()                // the animals' own window has not passed
    }

    @Test
    fun `PM-BOOK-010 a window from the end of a long month closes on the last day of a short one`() {
        holding(pets = listOf(animal("VP-4", validTo = LocalDate.parse("2026-11-30"))))

        assertThat(on("2027-02-27").animalPassportsCleared).isZero()
        assertThat(on("2027-02-28").animalPassportsCleared).isEqualTo(1)
    }

    @Test
    fun `PM-BOOK-010 a current stay is never anonymised, however long it has lasted`() {
        holding(
            residents = listOf(resident(UUID.randomUUID(), validTo = null), resident(UUID.randomUUID(), validTo = LocalDate.parse("2031-01-01"))),
            pets = listOf(animal("VP-1", validTo = null)),
        )

        val applied = on("2030-01-01")

        assertThat(applied.householdUnlinked + applied.animalPassportsCleared).isZero()
        assertThat(updates).isEmpty()
    }

    @Test
    fun `a record already anonymised is left alone, so a second pass changes nothing`() {
        holding(residents = listOf(resident(partyId = null, validTo = movedOut)), pets = listOf(animal(null, validTo = movedOut)))
        on("2026-12-01")
        assertThat(updates).isEmpty()
    }

    @Test
    fun `an unknown entrance is not found`() {
        assertThatThrownBy { retention.anonymiseDue(UUID.randomUUID(), LocalDate.parse("2026-10-01")) }
            .isInstanceOf(NoSuchElementException::class.java)
    }
}
