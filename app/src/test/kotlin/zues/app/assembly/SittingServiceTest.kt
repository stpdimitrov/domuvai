package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.app.registry.HeldOn
import zues.app.registry.Holding
import zues.app.registry.Holdings
import zues.app.registry.Resident
import zues.app.registry.TitleRole
import zues.law.CATALOGUE_VERSION
import zues.law.ENGINE_VERSION
import zues.law.constantOn
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * Quorum with the repositories mocked, the register given line by line and the registry's port faked: the book is
 * whatever the test says is held on the day of the sitting, and nowhere else.
 */
class SittingServiceTest {

    private val meets = Instant.parse("2026-11-20T16:00:00Z")                   // 18:00 on 20 November in Sofia
    private val sittingDay = LocalDate.parse("2026-11-20")
    private val entranceId = UUID.randomUUID()
    private val noticeDay = LocalDate.parse("2026-11-10")
    private var assembly = Assembly(
        UUID.randomUUID(), entranceId, UUID.randomUUID(), "BM", meets, "фоайето", "HYBRID", "NOTICED", false, null,
        noticePostedAt = Instant.parse("2026-11-09T22:30:00Z"),                          // 00:30 on 10 November in Sofia
    )
    private val keeper = Actor(UUID.randomUUID(), "https://id.example.test/realms/domuvai", "login-keeper")

    private data class Titled(val party: UUID, val unit: UUID, val role: TitleRole, val parts: String, val share: String, val derived: Boolean, val from: LocalDate? = null, val until: LocalDate? = null)
    private val book = mutableListOf<Titled>()
    private val overHeld = mutableSetOf<UUID>()
    private val holdings = object : Holdings {
        override fun inForce(entranceId: UUID, on: LocalDate, role: TitleRole): HeldOn {
            if (entranceId != this@SittingServiceTest.entranceId || on !in setOf(noticeDay, sittingDay, sittingDay.plusDays(1))) return HeldOn(emptyList(), emptyList())   // this entrance: the notice date, the days of its sittings
            val held = book.filter { it.role == role && (it.from == null || !on.isBefore(it.from)) && (it.until == null || on.isBefore(it.until)) }.map {
                Holding(it.party, it.unit, role.name, it.share, it.parts, (BigDecimal(it.parts) * BigDecimal(it.share)).stripTrailingZeros().toPlainString(), if (it.derived) "DERIVED" else "DECLARED")
            }
            return HeldOn(held, if (role == TitleRole.OWN) overHeld.toList() else emptyList())
        }
        override fun residents(entranceId: UUID, on: LocalDate): List<Resident> = emptyList()
    }

    private val lines = mutableListOf<Present>()
    private val stored = mutableListOf<Sitting>()
    private val aggregates: JdbcAggregateTemplate = mock { on { insert(any<Any>()) } doAnswer { (it.arguments[0] as Sitting).also { s -> stored += s } } }
    private val assemblies: AssemblyRepository = mock {
        on { lock(any(), any()) } doAnswer { call -> assembly.takeIf { it.id == call.arguments[0] && it.entranceId == call.arguments[1] } }
        on { findById(any<UUID>()) } doAnswer { call -> Optional.ofNullable(assembly.takeIf { it.id == call.arguments[0] }) }
        on { save(any<Assembly>()) } doAnswer { (it.arguments[0] as Assembly).also { saved -> assembly = saved } }
    }
    private val sittings: SittingRepository = mock { on { findByAssemblyIdOrderBySessionNo(any()) } doAnswer { stored.toList() } }
    /** The register as the register service gives it for sitting n: the lines of that day's sittings up to n. */
    private val attendance: AttendanceService = mock {
        on { register(any(), any(), any()) } doAnswer { call ->
            val n = call.arguments[2] as Int
            lines.filter { if (n == 3) it.attendance.sessionNo == 3 else it.attendance.sessionNo <= n }
        }
    }

    private fun service(at: Instant = meets) = SittingService(aggregates, assemblies, sittings, attendance, holdings, Clock.fixed(at, ZoneOffset.UTC))

    private fun owner(parts: String, share: String = "1", unit: UUID = UUID.randomUUID(), derived: Boolean = false, from: String? = null, until: String? = null, party: UUID = UUID.randomUUID()): UUID =
        party.also { book += Titled(it, unit, TitleRole.OWN, parts, share, derived, from?.let(LocalDate::parse), until?.let(LocalDate::parse)) }

    /** On the register, in person: brings these owners. */
    private fun present(vararg brings: UUID, channel: String = "IN_PERSON", verified: Boolean = false, sitting: Int = 1) {
        lines += Present(
            Attendance(UUID.randomUUID(), entranceId, assembly.id, sitting, brings.first(), channel, keeper.party, keeper.loginIssuer, keeper.loginSubject, meets),
            meets.takeIf { verified }, brings.toSet(),
        )
    }

    private fun meter() = service().read(entranceId, assembly.id).quorum

    private val threshold = constantOn("GA_QUORUM_PCT", "2026-11-20")

    @Test
    fun `PM-GA-012 at 50_99 percent the meeting cannot open, and at 51 it opens`() {
        assertThat(threshold.verified).isTrue()
        assertThat(threshold.value).isEqualTo("51")
        val (a, b, late) = listOf(owner("30.99"), owner("20"), owner("0.01"))
        owner("49")                                                                  // the rest of the entrance, absent
        present(a)
        present(b)
        val short = meter()
        assertThat(short.representedPct).isEqualByComparingTo("50.99")
        assertThat(short.thresholdPct).isEqualByComparingTo(threshold.value)
        assertThat(short.thresholdSource).isEqualTo("чл. 15 ал. 1 ЗУЕС")
        assertThat(short.on).isEqualTo(sittingDay)
        assertThat(short.met).isFalse()

        present(late)                                                                // one hundredth more: exactly 51
        assertThat(meter().representedPct).isEqualByComparingTo("51")
        assertThat(meter().met).isTrue()                                             // "at least"

        val sitting = service().open(entranceId, assembly.id, keeper)
        assertThat(sitting.quorumMet).isTrue()
        assertThat(sitting.sessionNo).isEqualTo(1)
        assertThat(sitting.openedAt).isEqualTo(meets)
        assertThat(sitting.represented).isEqualByComparingTo("51")
        assertThat(sitting.thresholdPct).isEqualByComparingTo("51")
        assertThat(sitting.thresholdConstant).isEqualTo("GA_QUORUM_PCT@${threshold.inForceFrom}")
        assertThat(sitting.weighedOn).isEqualTo(sittingDay)
        assertThat(listOf(sitting.lawVersion, sitting.engineVersion)).containsExactly(CATALOGUE_VERSION, ENGINE_VERSION)
        assertThat(listOf(sitting.weighedBy, sitting.loginSubject)).containsExactly(keeper.party, "login-keeper")
        assertThat(assembly.status).isEqualTo("OPEN")
    }

    @Test
    fun `PM-GA-012 a weighing that falls short is recorded and opens nothing - and the first sitting is weighed once`() {
        val a = owner("50.99")
        owner("49.01")
        present(a)
        val sitting = service().open(entranceId, assembly.id, keeper)
        assertThat(sitting.quorumMet).isFalse()
        assertThat(sitting.openedAt).isNull()
        assertThat(sitting.represented).isEqualByComparingTo("50.99")
        assertThat(assembly.status).isEqualTo("NOTICED")
        present(owner("0"))                                                          // whoever arrives after the weighing does not reopen it
        assertThatThrownBy { service().open(entranceId, assembly.id, keeper) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("sitting 2 does not open before")
        assertThat(stored).hasSize(1)
        assertThat(service().read(entranceId, assembly.id).sittings).containsExactly(sitting)
    }

    @Test
    fun `PM-GA-012 the weight is ideal parts by title share - each owner once however they are present, a co-owner for their share, never a count of units`() {
        val shared = UUID.randomUUID()
        val (half, otherHalf) = listOf(owner("40", share = "0.5", unit = shared), owner("40", share = "0.5", unit = shared))
        val big = owner("31")
        repeat(5) { owner("1") }                                                     // five small units, absent: units are not what is counted
        present(half)
        assertThat(meter().representedPct).isEqualByComparingTo("20")                // half of 40, never 40
        present(big, half)                                                           // big attends, and also holds the half-owner's proxy: half is not counted again
        assertThat(meter().representedPct).isEqualByComparingTo("51")
        assertThat(meter().basis.lines()).hasSize(3)                                 // what against what, then the two holdings
        present(otherHalf)
        assertThat(meter().representedPct).isEqualByComparingTo("71")
        // read a week later, it is still the day of the sitting whose book is weighed (PM-ORG-011)
        assertThat(service(meets.plusSeconds(7 * 86400)).read(entranceId, assembly.id).quorum.representedPct).isEqualByComparingTo("71")
    }

    @Test
    fun `PM-ORG-005 a unit never weighs more than its own ideal parts, however its co-owners' rounded shares add up`() {
        val unit = UUID.randomUUID()
        val six = List(6) { owner("50.9999", share = "0.166667", unit = unit) }     // six sixths, each rounded up: 51.0000019998 if simply added
        owner("49.0001")
        present(*six.toTypedArray())
        assertThat(meter().representedPct).isEqualByComparingTo("50.9999")
        assertThat(meter().met).isFalse()
    }

    @Test
    fun `PM-LAW-002 a weighing stores the catalogue version, the engine version and the dated constant that produced it`() {
        present(owner("50"), owner("30"))                                            // nobody over half: the ordinary quorum
        val sitting = service().open(entranceId, assembly.id, keeper)
        assertThat(sitting.lawVersion).isEqualTo(CATALOGUE_VERSION)
        assertThat(sitting.engineVersion).isEqualTo(ENGINE_VERSION)
        assertThat(sitting.thresholdConstant).isEqualTo("GA_QUORUM_PCT@${threshold.inForceFrom}")
        assertThat(sitting.thresholdVerified).isEqualTo(threshold.verified)
        assertThat(sitting.basis.lines().first()).isEqualTo("sitting 1 on $sittingDay against GA_QUORUM_PCT@${threshold.inForceFrom} ${threshold.value} (no owner over 51% at the notice on 2026-11-10): 80")
    }

    @Test
    fun `PM-GA-016 a remote participant not yet identified weighs nothing, nor do those they would bring`() {
        val (a, b) = listOf(owner("40"), owner("20"))
        present(a, b, channel = "VIDEO")
        assertThat(meter().representedPct).isEqualByComparingTo("0")
        lines.clear()
        present(a, b, channel = "VIDEO", verified = true)
        assertThat(meter().representedPct).isEqualByComparingTo("60")
    }

    @Test
    fun `PM-GA-012 a unit with both an owner and a holder of use makes the quorum unweighable - the meter says why and no sitting is opened or recorded`() {
        val a = owner("51")
        val other = owner("49")
        present(a)
        assertThat(meter().met).isTrue()                                             // owners only: weighed in full
        val unit = book.first { it.party == other }.unit
        book += Titled(UUID.randomUUID(), unit, TitleRole.USR, "49", "1", false)     // a right of use over a unit nobody present owns
        val weighed = meter()
        assertThat(weighed.met).isFalse()
        assertThat(weighed.representedPct).isEqualByComparingTo("51")                // present, and still
        assertThat(weighed.cannotWeigh).singleElement().asString().contains(unit.toString()).contains("holder of a right of use").contains("TODO(legal): PM-GA-012")
        assertThatThrownBy { service().open(entranceId, assembly.id, keeper) }
            .isInstanceOf(QuorumNotWeighable::class.java).hasMessageContaining("cannot be weighed").hasMessageContaining(unit.toString())
        assertThat(stored).isEmpty()
        assertThat(assembly.status).isEqualTo("NOTICED")
        book += Titled(UUID.randomUUID(), book.first { it.party == a }.unit, TitleRole.USR, "51", "1", false)   // and over the unit of the owner who is present
        assertThat(meter().representedPct).isEqualByComparingTo("0")                 // such a unit is left out of the figure
        assertThat(meter().cannotWeigh).hasSize(2)
    }

    @Test
    fun `PM-ORG-005 a represented owner's unit owned for more than the whole is not weighed, and a derived weight is flagged`() {
        val doubled = owner("50")
        overHeld += book.first { it.party == doubled }.unit
        present(doubled)
        val weighed = meter()
        assertThat(weighed.met).isFalse()
        assertThat(weighed.representedPct).isEqualByComparingTo("0")                 // left out of the figure
        assertThat(weighed.cannotWeigh).singleElement().asString().contains("PM-ORG-005")
        overHeld.clear()
        present(owner("5", derived = true))
        assertThat(meter().derivedParts).isTrue()
        assertThat(meter().met).isTrue()
    }

    @Test
    fun `PM-GA-012 a sitting opens on the day and not before the hour its notice stated, for a noticed assembly, with what was weighed kept and hashed`() {
        val a = owner("51")
        present(a)
        assertThatThrownBy { service(meets.minusSeconds(1)).open(entranceId, assembly.id, keeper) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("does not open before")
        assertThatThrownBy { service(meets.plusSeconds(8 * 3600)).open(entranceId, assembly.id, keeper) }      // 02:00 on the 21st in Sofia
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("is held on 2026-11-20")
        assembly = assembly.copy(status = "DRAFT")
        assertThatThrownBy { service().open(entranceId, assembly.id, keeper) }.isInstanceOf(IllegalStateException::class.java)
        assembly = assembly.copy(status = "NOTICED")
        assertThatThrownBy { service().open(UUID.randomUUID(), assembly.id, keeper) }.isInstanceOf(NoSuchElementException::class.java)
        assertThat(stored).isEmpty()

        val sitting = service(meets.plusSeconds(600)).open(entranceId, assembly.id, keeper)
        val unit = book.first { it.party == a }.unit
        assertThat(sitting.basis).isEqualTo("sitting 1 on 2026-11-20 against GA_QUORUM_PCT@${threshold.inForceFrom} 51 (no owner over 51% at the notice on 2026-11-10): 51\n$a $unit 51 of 51 by ${lines.single().attendance.id} IN_PERSON -")
        assertThat(service().weighed(assembly.id)).isTrue()
        assertThat(sitting.basisHash).matches("[0-9a-f]{64}").isEqualTo(service().read(entranceId, assembly.id).quorum.basisHash)
        assertThatThrownBy { service(meets.plusSeconds(900)).open(entranceId, assembly.id, keeper) }.isInstanceOf(IllegalStateException::class.java)   // it is open
    }

    private val reduced = constantOn("GA_REDUCED_QUORUM_PCT", "2026-11-20")
    private val postponed = constantOn("GA_POSTPONE_MINUTES", "2026-11-20").value.toLong() * 60

    @Test
    fun `PM-GA-013 reconvened assembly is valid at 26 percent of ideal parts - an hour after the first sitting fell short, not before`() {
        assertThat(listOf(reduced.value, reduced.verified, reduced.source)).containsExactly("26", true, "чл. 15 ал. 2 ЗУЕС")
        assertThat(postponed).isEqualTo(3600)
        val (a, b, c) = listOf(owner("25.99"), owner("0.01"), owner("10"))
        owner("31.99")
        owner("32")
        present(a)
        assertThat(service().open(entranceId, assembly.id, keeper).quorumMet).isFalse()
        val read = service().read(entranceId, assembly.id)
        assertThat(read.sessionNo).isEqualTo(2)
        assertThat(read.opensFrom).isEqualTo(meets.plusSeconds(postponed))         // an hour after the first was weighed
        assertThat(read.quorum.thresholdPct).isEqualByComparingTo("26")
        assertThat(read.quorum.representedPct).isEqualByComparingTo("25.99")
        assertThat(read.quorum.met).isFalse()
        assertThatThrownBy { service(meets.plusSeconds(postponed - 1)).open(entranceId, assembly.id, keeper) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("does not open before")

        present(b, sitting = 2)                                                      // one hundredth more, an hour on: exactly 26
        val second = service(meets.plusSeconds(postponed)).open(entranceId, assembly.id, keeper)
        assertThat(second.sessionNo).isEqualTo(2)
        assertThat(second.quorumMet).isTrue()
        assertThat(second.represented).isEqualByComparingTo("26")
        assertThat(second.thresholdConstant).isEqualTo("GA_REDUCED_QUORUM_PCT@${reduced.inForceFrom}")
        assertThat(second.basis.lines().first()).startsWith("sitting 2 on 2026-11-20 against GA_REDUCED_QUORUM_PCT")
        assertThat(assembly.status).isEqualTo("OPEN")
        assertThat(stored.map { it.sessionNo }).containsExactly(1, 2)
        present(c, sitting = 2)
        assertThatThrownBy { service(meets.plusSeconds(postponed + 60)).open(entranceId, assembly.id, keeper) }.isInstanceOf(IllegalStateException::class.java)   // open: nothing more to weigh
        assertThat(service().read(entranceId, assembly.id).sessionNo).isEqualTo(2)   // the meter is the open sitting's
    }

    @Test
    fun `PM-GA-013 at 25_99 percent the postponed sitting falls short, and is kept`() {
        val a = owner("25.99")
        owner("74.01")
        present(a)
        service().open(entranceId, assembly.id, keeper)
        val second = service(meets.plusSeconds(postponed)).open(entranceId, assembly.id, keeper)
        assertThat(second.quorumMet).isFalse()
        assertThat(second.openedAt).isNull()
        assertThat(assembly.status).isEqualTo("NOTICED")
    }

    @Test
    fun `PM-GA-014 the following day the assembly is held whatever the share represented - its own register, its agenda as noticed`() {
        val last = constantOn("GA_LAST_SITTING_QUORUM_PCT", "2026-11-21")
        assertThat(listOf(last.value, last.verified, last.source)).containsExactly("0", true, "чл. 15 ал. 3 ЗУЕС")
        assertThat(constantOn("GA_SITTINGS", "2026-11-20").value).isEqualTo("3")
        val (a, b) = listOf(owner("20"), owner("0.5", from = "2026-11-21"))         // b owns from the following day: weighed on it
        owner("39.5")
        owner("40")
        present(a)
        val noticed = assembly
        service().open(entranceId, assembly.id, keeper)
        service(meets.plusSeconds(postponed)).open(entranceId, assembly.id, keeper)
        val read = service(meets.plusSeconds(postponed)).read(entranceId, assembly.id)
        assertThat(read.sessionNo).isEqualTo(3)
        assertThat(read.opensFrom).isEqualTo(meets.plusSeconds(86_400))
        assertThat(read.quorum.on).isEqualTo(LocalDate.parse("2026-11-21"))
        assertThat(read.quorum.representedPct).isEqualByComparingTo("0")              // the day before's register is not this one's
        assertThat(read.quorum.met).isTrue()                                         // any share — none at all
        for (early in listOf(postponed + 600, 86_399L)) {                             // the same evening; a second before the hour, the day after
            assertThatThrownBy { service(meets.plusSeconds(early)).open(entranceId, assembly.id, keeper) }
                .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("does not open before")
        }
        assertThatThrownBy { service(meets.plusSeconds(2 * 86_400)).open(entranceId, assembly.id, keeper) }      // the day after the following day
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("is held on 2026-11-21")
        present(b, sitting = 3)
        val third = service(meets.plusSeconds(86_400)).open(entranceId, assembly.id, keeper)
        assertThat(third.sessionNo).isEqualTo(3)
        assertThat(third.quorumMet).isTrue()
        assertThat(third.represented).isEqualByComparingTo("0.5")
        assertThat(third.weighedOn).isEqualTo(LocalDate.parse("2026-11-21"))
        assertThat(third.thresholdConstant).isEqualTo("GA_LAST_SITTING_QUORUM_PCT@${last.inForceFrom}")
        assertThat(third.thresholdPct).isEqualByComparingTo("0")                     // "quorum_pct = 0"
        assertThat(assembly).isEqualTo(noticed.copy(status = "OPEN"))                // the same assembly, its agenda and hour as noticed: only its status moved
    }

    @Test
    fun `PM-GA-014 the following day's sitting is the last - with it weighed nothing is left to weigh`() {
        present(owner("10"))
        owner("90")
        service().open(entranceId, assembly.id, keeper)
        service(meets.plusSeconds(postponed)).open(entranceId, assembly.id, keeper)
        stored += stored.last().copy(id = UUID.randomUUID(), sessionNo = 3)          // a third weighed short, as no schema allows: the code is not led past it
        assertThatThrownBy { service(meets.plusSeconds(86_400)).open(entranceId, assembly.id, keeper) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("every sitting the law allows")
    }

    @Test
    fun `PM-GA-014 the last sitting is not held with nobody present - pending counsel`() {
        present(owner("10"))
        owner("90")
        service().open(entranceId, assembly.id, keeper)
        service(meets.plusSeconds(postponed)).open(entranceId, assembly.id, keeper)
        assertThatThrownBy { service(meets.plusSeconds(86_400)).open(entranceId, assembly.id, keeper) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("not with nobody present")
        assertThat(stored).hasSize(2)
    }

    @Test
    fun `PM-GA-014 the following day's sitting is at the hour the notice stated on the clock in Sofia - across the end of summer time too`() {
        val saturday = assembly.copy(scheduledAt = Instant.parse("2026-10-24T15:00:00Z"))                      // 18:00 summer time
        fun short(n: Int, at: Instant) = Sitting(UUID.randomUUID(), entranceId, assembly.id, n, null, BigDecimal.ZERO, false, at, LocalDate.parse("2026-10-24"),
            BigDecimal.ONE, "c", true, "b", "ab".repeat(32), "v", "e", keeper.party, keeper.loginIssuer, keeper.loginSubject)
        val ladder = Ladder(saturday, listOf(short(1, saturday.scheduledAt), short(2, saturday.scheduledAt.plusSeconds(postponed))))
        assertThat(ladder.opensFrom(3)).isEqualTo(Instant.parse("2026-10-25T16:00:00Z"))                       // 18:00 winter time, not 17:00
        assertThat(ladder.day(3)).isEqualTo(LocalDate.parse("2026-10-25"))
    }

    @Test
    fun `PM-GA-013 a postponed sitting whose hour falls past midnight is refused and says why - pending counsel`() {
        assembly = assembly.copy(scheduledAt = Instant.parse("2026-11-20T21:30:00Z"))                         // 23:30 in Sofia
        val late = assembly.scheduledAt
        present(owner("10"), sitting = 1)
        owner("90")
        assertThat(service(late).open(entranceId, assembly.id, keeper).quorumMet).isFalse()
        assertThatThrownBy { service(late.plusSeconds(postponed)).open(entranceId, assembly.id, keeper) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("TODO(legal): PM-GA-013")
    }

    private val concentrated = constantOn("GA_CONCENTRATED_QUORUM_PCT", "2026-11-20")
    private val concentration = constantOn("GA_CONCENTRATION_PCT", "2026-11-10")

    @Test
    fun `PM-GA-015 where one person owns more than 51 percent the quorum is 75 - at 74_99 the meeting cannot open, at 75 it does`() {
        assertThat(listOf(concentration.value, concentrated.value, concentrated.verified, concentrated.source)).containsExactly("51", "75", true, "чл. 15 ал. 4 ЗУЕС")
        val big = owner("51.0001")                                                   // one ten-thousandth more than 51
        val (a, b) = listOf(owner("23.9899"), owner("0.0100"))
        owner("24.9900")
        present(big)
        present(a)
        val short = meter()
        assertThat(short.thresholdPct).isEqualByComparingTo("75")
        assertThat(short.thresholdConstant).isEqualTo("GA_CONCENTRATED_QUORUM_PCT@${concentrated.inForceFrom}")
        assertThat(short.thresholdSource).isEqualTo("чл. 15 ал. 4 ЗУЕС")
        assertThat(short.concentratedBy).isEqualTo(big)
        assertThat(short.concentratedPct).isEqualByComparingTo("51.0001")
        assertThat(short.representedPct).isEqualByComparingTo("74.99")
        assertThat(short.met).isFalse()
        present(b)
        val sitting = service().open(entranceId, assembly.id, keeper)
        assertThat(sitting.quorumMet).isTrue()
        assertThat(sitting.thresholdPct).isEqualByComparingTo("75")
        assertThat(sitting.basis.lines().first()).contains("one owner, $big, held 51.0001 at the notice on 2026-11-10")
    }

    @Test
    fun `PM-GA-015 an owner of exactly 51 percent does not raise the quorum - more than, not at least`() {
        val big = owner("51")
        owner("49")
        present(big)
        assertThat(meter().thresholdPct).isEqualByComparingTo("51")
        assertThat(meter().concentratedBy).isNull()
        assertThat(meter().met).isTrue()
    }

    @Test
    fun `PM-GA-015 the concentration is the notice date's - a sale before the sitting keeps 75, a purchase after the notice does not raise it`() {
        val seller = owner("60", until = "2026-11-15")                              // sold between the notice and the sitting
        owner("60", from = "2026-11-15")                                             // the buyer
        owner("40")
        assertThat(meter().thresholdPct).isEqualByComparingTo("75")
        assertThat(meter().concentratedBy).isEqualTo(seller)
        book.clear()
        owner("40")
        owner("60", from = "2026-11-15")                                             // bought after the notice
        assertThat(meter().thresholdPct).isEqualByComparingTo("51")
    }

    @Test
    fun `PM-GA-015 a person's ideal parts are summed over their units by title share - co-owners are each their own person`() {
        val unit = UUID.randomUUID()
        owner("100", share = "0.5", unit = unit)
        owner("100", share = "0.5", unit = unit)
        assertThat(meter().thresholdPct).isEqualByComparingTo("51")                  // two halves: fifty each
        book.clear()
        val one = UUID.randomUUID()
        owner("30", party = one)
        owner("21.0001", party = one)                                                // two flats of one person: 51.0001
        owner("48.9999")
        assertThat(meter().concentratedBy).isEqualTo(one)
        assertThat(meter().thresholdPct).isEqualByComparingTo("75")
    }

    @Test
    fun `PM-GA-015 the higher quorum holds at the first and the postponed sittings - the following day's opens at any share`() {
        val big = owner("60")
        owner("40")
        present(big)
        assertThat(service().open(entranceId, assembly.id, keeper).quorumMet).isFalse()        // 60 of 75
        val second = service(meets.plusSeconds(postponed)).open(entranceId, assembly.id, keeper)
        assertThat(second.thresholdPct).isEqualByComparingTo("75")
        assertThat(second.quorumMet).isFalse()
        present(big, sitting = 3)
        val third = service(meets.plusSeconds(86_400)).open(entranceId, assembly.id, keeper)
        assertThat(third.thresholdPct).isEqualByComparingTo("0")                     // the owner's decision (Q4c): PM-GA-014 as it reads
        assertThat(third.thresholdConstant).startsWith("GA_LAST_SITTING_QUORUM_PCT@")
        assertThat(third.quorumMet).isTrue()                                         // the owner of more than half cannot keep it from being held
    }

    @Test
    fun `PM-GA-015 without the date its notice was posted the quorum of an assembly cannot be chosen - a draft's meter says it is not yet chosen`() {
        present(owner("100"))
        assembly = assembly.copy(noticePostedAt = null)
        assertThatThrownBy { service().open(entranceId, assembly.id, keeper) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("PM-GA-015")
        assertThatThrownBy { meter() }.isInstanceOf(IllegalStateException::class.java)
        assertThat(stored).isEmpty()
        assembly = assembly.copy(status = "DRAFT")
        assertThat(meter().basis.lines().first()).contains("not yet noticed")
    }

    @Test
    fun `PM-GA-015 a unit owned for more than the whole of it at the notice date leaves the concentration unknowable - not weighed, whoever is present`() {
        val unit = UUID.randomUUID()
        owner("60", unit = unit)
        owner("60", unit = unit)                                                     // booked twice over, both absent
        val present = owner("40")
        overHeld += unit
        present(present)
        val weighed = meter()
        assertThat(weighed.concentratedBy).isNull()
        assertThat(weighed.cannotWeigh).anyMatch { it.contains("$unit") && it.contains("notice date") && it.contains("PM-GA-015") }
        assertThat(weighed.met).isFalse()
        assertThatThrownBy { service().open(entranceId, assembly.id, keeper) }.isInstanceOf(QuorumNotWeighable::class.java)
    }

    @Test
    fun `PM-GA-015 with nobody over 51 percent the weighing says so, and of which notice date`() {
        present(owner("51"))
        owner("49")
        assertThat(service().open(entranceId, assembly.id, keeper).basis.lines().first()).contains("no owner over 51% at the notice on 2026-11-10")
    }
}
