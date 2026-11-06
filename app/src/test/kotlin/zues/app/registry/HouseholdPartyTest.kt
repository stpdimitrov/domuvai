package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * A household member named at entry by a registered party (#438): the party is stored on the member,
 * an unknown one refuses the whole list; so does one named twice or already a member of the unit (#442).
 * Repositories mocked — no Spring, no database; the stored member read back by name is BookPersistenceIT's, by the proxy check's port HoldingsPersistenceIT's.
 */
class HouseholdPartyTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val units: PropertyUnitRepository = mock()
    private val parties: PartyRepository = mock()
    private val household: HouseholdMemberRepository = mock()
    private val registry = RegistryService(
        aggregates, mock(), units, household, mock(), parties, mock(),
        Clock.fixed(Instant.parse("2026-06-01T09:00:00Z"), ZoneOffset.UTC),
    )

    private val entranceId = UUID.randomUUID()
    private val unit = PropertyUnit(UUID.randomUUID(), entranceId, "ап. 1", "FLAT", null, BigDecimal("100.0000"), false)
    private val maria = Party(UUID.randomUUID(), "Мария Георгиева", "EGN", "7501010010")

    @BeforeEach
    fun stub() {
        whenever(units.findById(unit.id)).thenReturn(Optional.of(unit))
        whenever(aggregates.insert(any<Any>())).thenAnswer { it.arguments[0] }
        whenever(parties.findAllById(any<Iterable<UUID>>())).thenAnswer { call ->
            (call.arguments[0] as Iterable<*>).filter { it == maria.id }.map { maria }
        }
    }

    private fun stay(partyId: UUID?, from: String, to: String?) =
        HouseholdMember(UUID.randomUUID(), entranceId, unit.id, partyId, false, LocalDate.parse(from), to?.let { LocalDate.parse(it) })

    @Test
    fun `PM-BOOK-002 a member entered with a registered party stores it, one entered with none stays unnamed`() {
        registry.registerHousehold(entranceId, unit.id, listOf(RegisterMember(partyId = maria.id), RegisterMember(isChildUnder6 = true)))

        val stored = argumentCaptor<HouseholdMember>().apply { verify(aggregates, times(2)).insert(capture()) }.allValues
        assertThat(stored.map { it.partyId }).containsExactly(maria.id, null)
        assertThat(stored.map { it.isChildUnder6 }).containsExactly(false, true)
    }

    @Test
    fun `PM-BOOK-002 an unknown party refuses the whole list, naming the rule, and stores nothing`() {
        val stranger = UUID.randomUUID()

        assertThatThrownBy {
            registry.registerHousehold(entranceId, unit.id, listOf(RegisterMember(partyId = maria.id), RegisterMember(partyId = stranger)))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PM-BOOK-002")
            .hasMessageContaining(stranger.toString())
            .hasMessageNotContaining(maria.id.toString())
        verify(aggregates, never()).insert(any<Any>())
    }

    @Test
    fun `PM-BOOK-002 a party named twice in one list refuses the whole list, naming the rule and the party, and stores nothing`() {
        assertThatThrownBy {
            registry.registerHousehold(entranceId, unit.id, listOf(RegisterMember(partyId = maria.id), RegisterMember(), RegisterMember(partyId = maria.id)))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PM-BOOK-002")
            .hasMessageContaining(maria.id.toString())
        verify(aggregates, never()).insert(any<Any>())
    }

    @Test
    fun `PM-BOOK-002 a party already a member of the unit for an overlapping stay refuses the whole list and stores nothing`() {
        for (existing in listOf(stay(maria.id, "2025-01-01", null), stay(maria.id, "2025-01-01", "2026-03-02"))) {
            whenever(household.findByUnitId(unit.id)).thenReturn(listOf(existing))

            assertThatThrownBy {
                registry.registerHousehold(entranceId, unit.id, listOf(RegisterMember(), RegisterMember(validFrom = "2026-03-01", partyId = maria.id)))
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("PM-BOOK-002")
                .hasMessageContaining(maria.id.toString())
            verify(aggregates, never()).insert(any<Any>())
        }
    }

    @Test
    fun `PM-BOOK-002 a party whose stay in the unit ended on or before the new validFrom is entered again`() {
        whenever(household.findByUnitId(unit.id)).thenReturn(listOf(stay(maria.id, "2025-01-01", "2026-03-01")))

        registry.registerHousehold(entranceId, unit.id, listOf(RegisterMember(validFrom = "2026-03-01", partyId = maria.id)))

        val stored = argumentCaptor<HouseholdMember>().apply { verify(aggregates).insert(capture()) }.allValues
        assertThat(stored.map { it.partyId to it.validFrom }).containsExactly(maria.id to LocalDate.parse("2026-03-01"))
    }

    @Test
    fun `PM-BOOK-002 unnamed members are never refused, however many and whoever already lives there`() {
        whenever(household.findByUnitId(unit.id)).thenReturn(listOf(stay(null, "2025-01-01", null), stay(null, "2025-01-01", null)))

        registry.registerHousehold(entranceId, unit.id, listOf(RegisterMember(), RegisterMember(isChildUnder6 = true)))

        verify(aggregates, times(2)).insert(any<HouseholdMember>())
    }
}
