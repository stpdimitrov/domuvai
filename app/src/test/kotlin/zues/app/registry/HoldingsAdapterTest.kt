package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The holdings the port exposes, with the repositories mocked — no Spring, no database. Proves what a
 * module weighing holders depends on: titles as of the date, ideal parts by share and never by unit
 * count, one role at a time, an over-held unit named, and nothing personal handed over.
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

    private fun held(on: LocalDate = may, role: TitleRole = TitleRole.OWN) = holdings.inForce(entranceId, on, role)

    private fun weight(of: UUID, on: LocalDate = may, role: TitleRole = TitleRole.OWN) =
        held(on, role).holdings.filter { it.partyId == of }.sumOf { BigDecimal(it.idealParts) }

    @Test
    fun `PM-ORG-004 a holding carries ideal parts, not a unit - two units of 12 percent together outweigh five units of 10`() {
        val big = UUID.randomUUID()
        val bigUnits = listOf(unit("7.0000"), unit("5.0000"))
        val small = List(5) { UUID.randomUUID() to unit("2.0000") }
        book(bigUnits + small.map { it.second } + unit("78.0000"), bigUnits.map { title(it, big) } + small.map { (p, u) -> title(u, p) })

        val all = held().holdings
        assertThat(all.filter { it.partyId == big }.map { it.idealParts }).containsExactlyInAnyOrder("7", "5")
        assertThat(all.filter { it.partyId != big }.map { it.idealParts }).containsOnly("2").hasSize(5)
        assertThat(weight(big)).isGreaterThan(small.sumOf { weight(it.first) })                    // 12 against 10
    }

    @Test
    fun `PM-ORG-005 co-owners split the unit's ideal parts by title share, and together hold exactly the unit's parts`() {
        val u = unit("12.5000")
        val (a, b) = List(2) { UUID.randomUUID() }
        book(listOf(u), listOf(title(u, a, share = "0.5"), title(u, b, share = "0.5")))
        val half = held().holdings.single { it.partyId == a }
        assertThat(listOf(half.share, half.unitIdealParts, half.idealParts)).containsExactly("0.5", "12.5", "6.25")   // exact, no float, no padding
        assertThat(held().holdings.sumOf { BigDecimal(it.idealParts) }).isEqualByComparingTo("12.5")
        assertThat(held().overHeldUnitIds).isEmpty()
    }

    @Test
    fun `PM-ORG-005 an owner and a holder of use over one unit are never summed together - one role is answered per call`() {
        val u = unit("100.0000")
        val (owner, holderOfUse) = List(2) { UUID.randomUUID() }
        book(listOf(u), listOf(title(u, owner), title(u, holderOfUse, role = "USR")))
        assertThat(held(role = TitleRole.OWN).holdings.map { it.partyId }).containsExactly(owner)
        assertThat(held(role = TitleRole.USR).holdings.map { it.partyId }).containsExactly(holderOfUse)
        for (role in TitleRole.entries) {
            assertThat(held(role = role).holdings.sumOf { BigDecimal(it.idealParts) }).isEqualByComparingTo("100")   // never 200
            assertThat(held(role = role).overHeldUnitIds).isEmpty()
        }
    }

    @Test
    fun `PM-ORG-005 a unit whose shares of one role exceed the whole is named, so it is not counted twice`() {
        val sound = unit("40.0000")
        val doubled = unit("60.0000")
        val (a, b, c) = List(3) { UUID.randomUUID() }
        val past = title(sound, b, to = "2026-03-01")                                               // a past owner does not count
        book(
            listOf(sound, doubled),
            listOf(title(sound, a), past, title(doubled, a, share = "0.5"), title(doubled, b, share = "0.5"), title(doubled, c, share = "0.25"),   // 125% owned
                title(sound, b, role = "USR"), title(sound, c, role = "USR")),                      // two rights of use over the whole of one unit
        )
        assertThat(held().overHeldUnitIds).containsExactly(doubled.id)
        assertThat(held().holdings.filter { it.unitId == doubled.id }.sumOf { BigDecimal(it.idealParts) }).isGreaterThan(BigDecimal("60"))   // what weighing it would do
        assertThat(held(role = TitleRole.USR).overHeldUnitIds).containsExactly(sound.id)            // judged role by role
        // before the third title began the unit was whole
        book(listOf(sound, doubled), listOf(title(doubled, a, share = "0.5"), title(doubled, b, share = "0.5"), title(doubled, c, share = "0.25", from = "2026-06-01")))
        assertThat(held().overHeldUnitIds).isEmpty()
        assertThat(held(on = LocalDate.of(2026, 6, 1)).overHeldUnitIds).containsExactly(doubled.id)
    }

    @Test
    fun `PM-ORG-005 sixths and thirds as the book stores them are not an excess, and the smallest real one is`() {
        val u = unit("60.0000")
        val six = List(6) { UUID.randomUUID() }
        book(listOf(u), six.map { title(u, it, share = "0.166667") })                               // sums to 1.000002
        assertThat(held().overHeldUnitIds).isEmpty()
        book(listOf(u), List(3) { title(u, UUID.randomUUID(), share = "0.333333") })                // sums to 0.999999
        assertThat(held().overHeldUnitIds).isEmpty()
        assertThat(held().holdings.sumOf { BigDecimal(it.idealParts) }).isLessThanOrEqualTo(BigDecimal("60"))
        book(listOf(u), listOf(title(u, six[0], share = "1"), title(u, six[1], share = "0.000002")))   // past the whole by more than two roundings
        assertThat(held().overHeldUnitIds).containsExactly(u.id)
    }

    @Test
    fun `PM-ORG-011 the holder is resolved as of the date asked - the seller until the day of sale, the buyer from it`() {
        val u = unit("100.0000")
        val (seller, buyer, holderOfUse) = List(3) { UUID.randomUUID() }
        book(listOf(u), listOf(title(u, seller, to = "2026-05-14"), title(u, buyer, from = "2026-05-14"), title(u, holderOfUse, role = "USR", from = "2026-02-01")))

        fun owners(on: String) = held(LocalDate.parse(on)).holdings.map { it.partyId }
        assertThat(owners("2026-05-13")).containsExactly(seller)
        assertThat(owners("2026-05-14")).containsExactly(buyer)                       // half-open: the day of sale is the buyer's
        assertThat(owners("2025-12-31")).isEmpty()
        assertThat(weight(holderOfUse, role = TitleRole.USR)).isEqualByComparingTo("100")
        assertThat(weight(holderOfUse, on = LocalDate.of(2026, 1, 15), role = TitleRole.USR)).isEqualByComparingTo("0")
    }

    @Test
    fun `a title filed under this entrance for another entrance's unit is answered for neither, and breaks nothing`() {
        val mine = unit("100.0000")
        val owner = UUID.randomUUID()
        val stray = Title(UUID.randomUUID(), entranceId, UUID.randomUUID(), UUID.randomUUID(), "OWN", BigDecimal.ONE, LocalDate.of(2026, 1, 1), null)
        book(listOf(mine), listOf(title(mine, owner), stray))
        assertThat(held().holdings.map { it.partyId }).containsExactly(owner)
    }

    @Test
    fun `residents are those in residence on the date who are a known party`() {
        val unitId = UUID.randomUUID()
        val (staying, gone) = List(2) { UUID.randomUUID() }
        fun member(party: UUID?, from: String = "2026-01-01", to: String? = null) =
            HouseholdMember(UUID.randomUUID(), entranceId, unitId, party, false, LocalDate.parse(from), to?.let { LocalDate.parse(it) })
        whenever(household.findByEntranceId(entranceId)).thenReturn(listOf(member(staying), member(gone, to = "2026-04-01"), member(null)))
        assertThat(holdings.residents(entranceId, may)).containsExactly(Resident(staying, unitId))
        assertThat(holdings.residents(entranceId, LocalDate.of(2026, 3, 31)).map { it.partyId }).containsExactlyInAnyOrder(staying, gone)
    }

    @Test
    fun `the port hands over ids, roles and numbers - not a name, an identity number or a designation - and a derived weight as derived`() {
        val u = unit("100.0000", source = "DERIVED")
        book(listOf(u), listOf(title(u, UUID.randomUUID())))
        val holding = held().holdings.single()
        assertThat(holding.idealPartsSource).isEqualTo("DERIVED")
        assertThat(holding.toString()).doesNotContain("об.")
    }
}
