package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
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
 * Ownership with the repositories mocked — no Spring, no database. Proves the validation and the
 * as-of-date resolution (PM-ORG-011) run in the gate pack locally; the table's own guards (the
 * overlap exclusion, the share range) are proved end to end by the Docker-gated IT.
 */
class OwnershipServiceTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val parties: PartyRepository = mock()
    private val titles: TitleRepository = mock()
    private val units: PropertyUnitRepository = mock()
    private val clock = Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = OwnershipService(aggregates, parties, titles, units, clock)

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()

    private fun stubUnitAndParty() {
        whenever(units.findById(unitId)).thenReturn(
            Optional.of(PropertyUnit(unitId, entranceId, "ап. 1", "FLAT", null, BigDecimal("100.0000"), false)),
        )
        whenever(parties.existsById(partyId)).thenReturn(true)
    }

    @Test
    fun `PM-BOOK-002 a party is registered with its name`() {
        val captor = argumentCaptor<Party>()
        whenever(aggregates.insert(captor.capture())).thenAnswer { it.getArgument<Party>(0) }
        service.registerParty(RegisterParty("Иван Петров", "EGN", "7501010010"))
        assertThat(captor.firstValue.fullName).isEqualTo("Иван Петров")
    }

    @Test
    fun `an unknown id type is rejected`() {
        assertThatThrownBy { service.registerParty(RegisterParty("Иван", "SSN")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `PM-ORG-005 a share outside the unit interval is rejected`() {
        stubUnitAndParty()
        assertThatThrownBy { service.assignTitle(entranceId, unitId, AssignTitle(partyId, "OWN", share = "1.5")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.assignTitle(entranceId, unitId, AssignTitle(partyId, "OWN", share = "0")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a title for an unknown party is not found`() {
        whenever(units.findById(unitId)).thenReturn(
            Optional.of(PropertyUnit(unitId, entranceId, "ап. 1", "FLAT", null, BigDecimal("100.0000"), false)),
        )
        whenever(parties.existsById(partyId)).thenReturn(false)
        assertThatThrownBy { service.assignTitle(entranceId, unitId, AssignTitle(partyId, "OWN")) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `PM-ORG-011 the owner resolves as of the date, across a sale`() {
        val oldOwner = UUID.randomUUID()
        val newOwner = UUID.randomUUID()
        whenever(titles.findByEntranceId(entranceId)).thenReturn(
            listOf(
                Title(UUID.randomUUID(), entranceId, unitId, oldOwner, "OWN", BigDecimal("1.000000"),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1)),   // sold on 1 June
                Title(UUID.randomUUID(), entranceId, unitId, newOwner, "OWN", BigDecimal("1.000000"),
                    LocalDate.of(2026, 6, 1), null),
            ),
        )
        whenever(parties.findAllById(any())).thenReturn(
            listOf(Party(oldOwner, "Old Owner", null, null), Party(newOwner, "New Owner", null, null)),
        )
        assertThat(service.ownersAsOf(entranceId, LocalDate.of(2026, 5, 1)).single().partyName).isEqualTo("Old Owner")
        assertThat(service.ownersAsOf(entranceId, LocalDate.of(2026, 7, 1)).single().partyName).isEqualTo("New Owner")
    }
}
