package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.full.memberProperties

/**
 * The holdings the port exposes, with the repositories mocked — no Spring, no database. Proves what a
 * module weighing holders depends on: titles as of the date, ideal parts by share and never by unit
 * count, an over-owned unit named, and nothing personal handed over.
 */
class HoldingsAdapterTest {

    private val titles: TitleRepository = mock()
    private val units: PropertyUnitRepository = mock()
    private val household: HouseholdMemberRepository = mock()
    private val holdings: Holdings = HoldingsAdapter(titles, units, household)

    private val entranceId = UUID.randomUUID()
    private val may = LocalDate.of(2026, 5, 1)

    private fun unit(parts: String, source: String = "DECLARED") =
        PropertyUnit(UUID.randomUUID(), entranceId, "об.", "FLAT", null, BigDecimal(parts), false, idealPartsSource = source)

    private fun title(unit: PropertyUnit, party: UUID, role: String = "OWN", share: String = "1",
                      from: String = "2026-01-01", to: String? = null) =
        Title(UUID.randomUUID(), entranceId, unit.id, party, role, BigDecimal(share), LocalDate.parse(from), to?.let { LocalDate.parse(it) })

    private fun book(units: List<PropertyUnit>, titles: List<Title>) {
        whenever(this.units.findByEntranceId(entranceId)).thenReturn(units)
        whenever(this.titles.findByEntranceId(entranceId)).thenReturn(titles)
    }

    private fun weight(of: UUID, on: LocalDate = may, role: String = "OWN") =
        holdings.inForce(entranceId, on).filter { it.partyId == of && it.titleRole == role }.sumOf { BigDecimal(it.idealParts) }

    @Test
    fun `PM-ORG-004 a 2-unit owner with 12 percent outweighs 5 owners holding 10 percent - ideal parts, never a count of units`() {
        val big = UUID.randomUUID()
        val bigUnits = listOf(unit("7.0000"), unit("5.0000"))
        val small = List(5) { UUID.randomUUID() to unit("2.0000") }
        book(bigUnits + small.map { it.second } + unit("78.0000"), bigUnits.map { title(it, big) } + small.map { (p, u) -> title(u, p) })

        assertThat(weight(big)).isEqualByComparingTo("12")
        assertThat(small.sumOf { weight(it.first) }).isEqualByComparingTo("10")
        assertThat(holdings.inForce(entranceId, may).count { it.partyId == big }).isEqualTo(2)      // fewer units, more weight
    }

    @Test
    fun `PM-ORG-005 co-owners split the unit's ideal parts by title share, and together hold exactly the unit's parts`() {
        val u = unit("12.5000")
        val third = unit("10.0000")
        val (a, b, c) = List(3) { UUID.randomUUID() }
        book(
            listOf(u, third),
            listOf(title(u, a, share = "0.5"), title(u, b, share = "0.5")) +
                listOf(a, b, c).map { title(third, it, share = "0.333333") },                       // thirds as the book can hold them
        )
        val held = holdings.inForce(entranceId, may)
        val half = held.single { it.partyId == a && it.unitId == u.id }
        assertThat(half.share).isEqualTo("0.5")
        assertThat(half.unitIdealParts).isEqualTo("12.5000")
        assertThat(half.idealParts).isEqualTo("6.25")                                               // exact, no float, no padding
        assertThat(held.filter { it.unitId == u.id }.sumOf { BigDecimal(it.idealParts) }).isEqualByComparingTo("12.5")
        // shares that do not reach the whole never exceed the unit: nothing is counted twice
        assertThat(held.filter { it.unitId == third.id }.sumOf { BigDecimal(it.idealParts) }).isEqualByComparingTo("9.99999")
        assertThat(holdings.overOwnedUnits(entranceId, may)).isEmpty()
    }

    @Test
    fun `PM-ORG-005 a unit whose ownership shares exceed the whole is named, so it is not counted twice`() {
        val sound = unit("40.0000")
        val doubled = unit("60.0000")
        val (a, b, c) = List(3) { UUID.randomUUID() }
        book(
            listOf(sound, doubled),
            listOf(
                title(sound, a),
                title(doubled, a, share = "0.5"), title(doubled, b, share = "0.5"), title(doubled, c, share = "0.25"),   // 125% owned
                title(sound, c, role = "USR"),                                                                           // a user beside the owner is not ownership
                title(sound, b, to = "2026-03-01"),                                                                      // a past owner does not count
            ),
        )
        assertThat(holdings.overOwnedUnits(entranceId, may)).containsExactly(doubled.id)
        assertThat(holdings.inForce(entranceId, may).filter { it.unitId == doubled.id && it.titleRole == "OWN" }.sumOf { BigDecimal(it.idealParts) })
            .isGreaterThan(BigDecimal("60"))                                         // what counting it would do
        // before the third title began the unit was whole
        book(listOf(sound, doubled), listOf(title(doubled, a, share = "0.5"), title(doubled, b, share = "0.5"), title(doubled, c, share = "0.25", from = "2026-06-01")))
        assertThat(holdings.overOwnedUnits(entranceId, may)).isEmpty()
        assertThat(holdings.overOwnedUnits(entranceId, LocalDate.of(2026, 6, 1))).containsExactly(doubled.id)
    }

    @Test
    fun `PM-ORG-011 the holder is resolved as of the date asked - the seller until the day of sale, the buyer from it`() {
        val u = unit("100.0000")
        val (seller, buyer, user) = List(3) { UUID.randomUUID() }
        book(listOf(u), listOf(title(u, seller, to = "2026-05-14"), title(u, buyer, from = "2026-05-14"), title(u, user, role = "USR", from = "2026-02-01")))

        fun owners(on: String) = holdings.inForce(entranceId, LocalDate.parse(on)).filter { it.titleRole == "OWN" }.map { it.partyId }
        assertThat(owners("2026-05-13")).containsExactly(seller)
        assertThat(owners("2026-05-14")).containsExactly(buyer)                       // half-open: the day of sale is the buyer's
        assertThat(owners("2025-12-31")).isEmpty()
        assertThat(weight(user, role = "USR")).isEqualByComparingTo("100")            // a user is reported apart, not as an owner
        assertThat(weight(user, on = LocalDate.of(2026, 1, 15), role = "USR")).isEqualByComparingTo("0")
    }

    @Test
    fun `PM-ORG-011 residents are those in residence on the date who are a known party, with the book's one age fact`() {
        val unitId = UUID.randomUUID()
        val (adult, child, gone) = List(3) { UUID.randomUUID() }
        fun member(party: UUID?, child: Boolean = false, from: String = "2026-01-01", to: String? = null) =
            HouseholdMember(UUID.randomUUID(), entranceId, unitId, party, child, LocalDate.parse(from), to?.let { LocalDate.parse(it) })
        whenever(household.findByEntranceId(entranceId)).thenReturn(
            listOf(member(adult), member(child, child = true), member(gone, to = "2026-04-01"), member(null)),
        )
        assertThat(holdings.residents(entranceId, may)).containsExactlyInAnyOrder(Resident(adult, unitId, false), Resident(child, unitId, true))
        assertThat(holdings.residents(entranceId, LocalDate.of(2026, 3, 31)).map { it.partyId }).contains(gone)
    }

    @Test
    fun `PM-BOOK-011 the port hands over ids, roles and numbers only - no name, no identity number, no address`() {
        assertThat(Holding::class.memberProperties.map { it.name })
            .containsExactlyInAnyOrder("partyId", "unitId", "titleRole", "share", "unitIdealParts", "idealParts", "idealPartsSource")
        assertThat(Resident::class.memberProperties.map { it.name }).containsExactlyInAnyOrder("partyId", "unitId", "childUnder6")

        val u = unit("100.0000", source = "DERIVED")
        val owner = UUID.randomUUID()
        book(listOf(u), listOf(title(u, owner)))
        val holding = holdings.inForce(entranceId, may).single()
        assertThat(holding.idealPartsSource).isEqualTo("DERIVED")                      // a derived weight is passed on as derived
        assertThat(holding.toString()).doesNotContain("об.")                           // not even the unit's designation
    }
}
