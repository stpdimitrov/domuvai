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
    private var assembly = Assembly(UUID.randomUUID(), entranceId, UUID.randomUUID(), "BM", meets, "фоайето", "HYBRID", "NOTICED", false, null)
    private val keeper = Actor(UUID.randomUUID(), "https://id.example.test/realms/domuvai", "login-keeper")

    private data class Titled(val party: UUID, val unit: UUID, val role: TitleRole, val parts: String, val share: String, val derived: Boolean)
    private val book = mutableListOf<Titled>()
    private val overHeld = mutableSetOf<UUID>()
    private val holdings = object : Holdings {
        override fun inForce(entranceId: UUID, on: LocalDate, role: TitleRole): HeldOn {
            if (entranceId != this@SittingServiceTest.entranceId || on != sittingDay) return HeldOn(emptyList(), emptyList())   // this entrance, the day of the sitting
            val held = book.filter { it.role == role }.map {
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
    private val attendance: AttendanceService = mock { on { register(any(), any(), any()) } doAnswer { lines.toList() } }

    private fun service(at: Instant = meets) = SittingService(aggregates, assemblies, sittings, attendance, holdings, Clock.fixed(at, ZoneOffset.UTC))

    private fun owner(parts: String, share: String = "1", unit: UUID = UUID.randomUUID(), derived: Boolean = false): UUID =
        UUID.randomUUID().also { book += Titled(it, unit, TitleRole.OWN, parts, share, derived) }

    /** On the register, in person: brings these owners. */
    private fun present(vararg brings: UUID, channel: String = "IN_PERSON", verified: Boolean = false) {
        lines += Present(
            Attendance(UUID.randomUUID(), entranceId, assembly.id, 1, brings.first(), channel, keeper.party, keeper.loginIssuer, keeper.loginSubject, meets),
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
        assertThatThrownBy { service().open(entranceId, assembly.id, keeper) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("already weighed")
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
        present(owner("80"))
        val sitting = service().open(entranceId, assembly.id, keeper)
        assertThat(sitting.lawVersion).isEqualTo(CATALOGUE_VERSION)
        assertThat(sitting.engineVersion).isEqualTo(ENGINE_VERSION)
        assertThat(sitting.thresholdConstant).isEqualTo("GA_QUORUM_PCT@${threshold.inForceFrom}")
        assertThat(sitting.thresholdVerified).isEqualTo(threshold.verified)
        assertThat(sitting.basis).startsWith("on $sittingDay against GA_QUORUM_PCT@${threshold.inForceFrom} ${threshold.value}: 80")
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
        val a = owner("60")
        val other = owner("40")
        present(a)
        assertThat(meter().met).isTrue()                                             // owners only: weighed in full
        val unit = book.first { it.party == other }.unit
        book += Titled(UUID.randomUUID(), unit, TitleRole.USR, "40", "1", false)     // a right of use over a unit nobody present owns
        val weighed = meter()
        assertThat(weighed.met).isFalse()
        assertThat(weighed.representedPct).isEqualByComparingTo("60")                // present, and still
        assertThat(weighed.cannotWeigh).singleElement().asString().contains(unit.toString()).contains("holder of a right of use").contains("TODO(legal): PM-GA-012")
        assertThatThrownBy { service().open(entranceId, assembly.id, keeper) }
            .isInstanceOf(QuorumNotWeighable::class.java).hasMessageContaining("cannot be weighed").hasMessageContaining(unit.toString())
        assertThat(stored).isEmpty()
        assertThat(assembly.status).isEqualTo("NOTICED")
        book += Titled(UUID.randomUUID(), book.first { it.party == a }.unit, TitleRole.USR, "60", "1", false)   // and over the unit of the owner who is present
        assertThat(meter().representedPct).isEqualByComparingTo("0")                 // such a unit is left out of the figure
        assertThat(meter().cannotWeigh).hasSize(2)
    }

    @Test
    fun `PM-ORG-005 a represented owner's unit owned for more than the whole is not weighed, and a derived weight is flagged`() {
        val doubled = owner("60")
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
        val a = owner("70")
        present(a)
        assertThatThrownBy { service(meets.minusSeconds(1)).open(entranceId, assembly.id, keeper) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("before the hour")
        assertThatThrownBy { service(meets.plusSeconds(8 * 3600)).open(entranceId, assembly.id, keeper) }      // 02:00 on the 21st in Sofia
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("on the day")
        assembly = assembly.copy(status = "DRAFT")
        assertThatThrownBy { service().open(entranceId, assembly.id, keeper) }.isInstanceOf(IllegalStateException::class.java)
        assembly = assembly.copy(status = "NOTICED")
        assertThatThrownBy { service().open(UUID.randomUUID(), assembly.id, keeper) }.isInstanceOf(NoSuchElementException::class.java)
        assertThat(stored).isEmpty()

        val sitting = service(meets.plusSeconds(600)).open(entranceId, assembly.id, keeper)
        val unit = book.first { it.party == a }.unit
        assertThat(sitting.basis).isEqualTo("on 2026-11-20 against GA_QUORUM_PCT@${threshold.inForceFrom} 51: 70\n$a $unit 70 of 70 by ${lines.single().attendance.id} IN_PERSON -")
        assertThat(service().weighed(assembly.id)).isTrue()
        assertThat(sitting.basisHash).matches("[0-9a-f]{64}").isEqualTo(service().read(entranceId, assembly.id).quorum.basisHash)
        assertThatThrownBy { service(meets.plusSeconds(900)).open(entranceId, assembly.id, keeper) }.isInstanceOf(IllegalStateException::class.java)   // it is open
    }
}
