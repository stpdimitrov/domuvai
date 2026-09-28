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

    private fun unit(parts: String) = PropertyUnit(UUID.randomUUID(), entranceId, "об.", "FLAT", null, BigDecimal(parts), false)

    private fun title(unit: PropertyUnit, party: UUID, role: String = "OWN", share: String = "1",
                      from: LocalDate = LocalDate.of(2026, 1, 1), to: LocalDate? = null) =
        Title(UUID.randomUUID(), entranceId, unit.id, party, role, BigDecimal(share), from, to)

    @Test
    fun `PM-ORG-004 a 2-unit owner with 12% outvotes 5 owners holding 10%`() {
        val big = UUID.randomUUID()
        val bigUnits = listOf(unit("7.0000"), unit("5.0000"))                     // two units: 12%
        val small = List(5) { UUID.randomUUID() to unit("2.0000") }               // five owners: 2% each
        whenever(units.findByEntranceId(entranceId)).thenReturn(bigUnits + small.map { it.second } + unit("78.0000"))
        whenever(titles.findByEntranceId(entranceId)).thenReturn(bigUnits.map { title(it, big) } + small.map { (p, u) -> title(u, p) })

        val weights = service.votingWeights(entranceId, LocalDate.of(2026, 5, 1))
        val bigWeight = weights.single { it.partyId == big }.idealPartsPct
        val smallWeight = weights.filter { it.partyId != big }.sumOf { it.idealPartsPct }
        assertThat(bigWeight).isEqualByComparingTo("12")
        assertThat(smallWeight).isEqualByComparingTo("10")
        assertThat(bigWeight).isGreaterThan(smallWeight)                           // two units outweigh five
        assertThat(weights.first().partyId).isEqualTo(big)
    }

    @Test
    fun `PM-ORG-004 PM-ORG-005 a co-owned unit splits its weight by share, and only titles in force count`() {
        val u = unit("40.0000")
        val v = unit("60.0000")
        val (a, b, seller, user) = List(4) { UUID.randomUUID() }
        whenever(units.findByEntranceId(entranceId)).thenReturn(listOf(u, v))
        whenever(titles.findByEntranceId(entranceId)).thenReturn(
            listOf(
                title(u, a, share = "0.5"), title(u, b, share = "0.5"),             // co-owners of 40%
                title(v, seller, to = LocalDate.of(2026, 3, 1)),                    // sold on 1 March
                title(v, a, from = LocalDate.of(2026, 3, 1)),                       // the buyer, from then
                title(v, user, role = "USR"),                                       // a user: reported, not decided
            ),
        )
        val weights = service.votingWeights(entranceId, LocalDate.of(2026, 5, 1)).associateBy { it.partyId to it.titleRole }
        assertThat(weights.getValue(a to "OWN").idealPartsPct).isEqualByComparingTo("80")     // 20 + 60
        assertThat(weights.getValue(b to "OWN").idealPartsPct).isEqualByComparingTo("20")
        assertThat(weights).doesNotContainKey(seller to "OWN")
        assertThat(weights.getValue(user to "USR").idealPartsPct).isEqualByComparingTo("60")
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
