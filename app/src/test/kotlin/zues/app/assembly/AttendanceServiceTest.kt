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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/** The register with the repositories mocked and the registry's port faked: the "tables" are lists. */
class AttendanceServiceTest {

    private val meets = Instant.parse("2026-11-20T16:00:00Z")
    private val entranceId = UUID.randomUUID()
    private var assembly: Assembly? = Assembly(UUID.randomUUID(), entranceId, UUID.randomUUID(), "BM", meets, "фоайето", "IN_PERSON", "NOTICED", false, null)
    private val keeper = Actor(UUID.randomUUID(), "https://id.example.test/realms/domuvai", "login-keeper")

    private val rows = mutableListOf<Attendance>()
    private val verified = mutableListOf<IdentityVerification>()
    private val proxyRows = mutableListOf<Proxy>()

    /** Owners, each from the day their title begins; the book answers for this entrance only. */
    private val owners = mutableMapOf<UUID, LocalDate>()
    private val book = object : Holdings {
        override fun inForce(entranceId: UUID, on: LocalDate, role: TitleRole) = HeldOn(
            if (role == TitleRole.OWN && entranceId == this@AttendanceServiceTest.entranceId) {
                owners.filterValues { !on.isBefore(it) }.keys.map { Holding(it, UUID.randomUUID(), "OWN", "1", "10", "10", "DECLARED") }
            } else {
                emptyList()
            },
            emptyList(),
        )
        override fun residents(entranceId: UUID, on: LocalDate): List<Resident> = emptyList()
    }

    private val aggregates: JdbcAggregateTemplate = mock {
        on { insert(any<Any>()) } doAnswer {
            when (val row = it.arguments[0]) {
                is Attendance -> rows += row
                is IdentityVerification -> verified += row
            }
            it.arguments[0]
        }
    }
    private val assemblies: AssemblyRepository = mock {
        on { lock(any(), any()) } doAnswer { call -> assembly?.takeIf { it.id == call.arguments[0] && it.entranceId == call.arguments[1] } }
        on { findById(any<UUID>()) } doAnswer { call -> Optional.ofNullable(assembly?.takeIf { it.id == call.arguments[0] }) }
    }
    private val attendance: AttendanceRepository = mock {
        on { findByAssemblyIdAndSessionNoOrderByRecordedAt(any(), any()) } doAnswer { call -> rows.filter { it.assemblyId == call.arguments[0] && it.sessionNo == call.arguments[1] } }
        on { findById(any<UUID>()) } doAnswer { call -> Optional.ofNullable(rows.firstOrNull { it.id == call.arguments[0] }) }
    }
    private val verifications: IdentityVerificationRepository = mock {
        on { findByAssemblyId(any()) } doAnswer { verified.toList() }
        on { existsById(any<UUID>()) } doAnswer { call -> verified.any { it.attendanceId == call.arguments[0] } }
    }
    private val proxies: ProxyRepository = mock { on { findByAssemblyId(any()) } doAnswer { proxyRows.toList() } }

    private fun service(at: Instant = meets) = AttendanceService(aggregates, assemblies, attendance, verifications, proxies, book, Clock.fixed(at, ZoneOffset.UTC))

    private fun owner(from: String = "2026-01-01"): UUID = UUID.randomUUID().also { owners[it] = LocalDate.parse(from) }

    private fun proxy(principal: UUID, agent: UUID, signedIn: Boolean = true) = Proxy(
        UUID.randomUUID(), entranceId, assembly!!.id, principal, agent, "THIRD_PARTY", "WHOLE_AGENDA", null, "WRITTEN", meets,
        if (signedIn) keeper.loginIssuer else null, if (signedIn) "login-$principal" else null, principal, null,
    ).also { proxyRows += it }

    private fun record(attendee: UUID, channel: String = "IN_PERSON", at: Instant = meets) = service(at).record(entranceId, assembly!!.id, keeper, attendee, channel)

    private fun register() = service().register(entranceId, assembly!!.id)

    @Test
    fun `PM-GA-012 a person present brings themself if they own, and every principal whose proxy names them - present in person or by proxy`() {
        val (ana, boris, vera) = listOf(owner(), owner(), owner())
        val lawyer = UUID.randomUUID()                                               // owns nothing: present only for others
        proxy(principal = boris, agent = ana)
        proxy(principal = vera, agent = lawyer)

        val line = record(ana)
        assertThat(line.attendance.attendeePartyId).isEqualTo(ana)
        assertThat(line.attendance.sessionNo).isEqualTo(1)
        assertThat(line.attendance.recordedBy).isEqualTo(keeper.party)
        assertThat(line.attendance.loginSubject).isEqualTo("login-keeper")
        assertThat(line.counted).isTrue()
        assertThat(line.represented).containsExactlyInAnyOrder(ana, boris)          // herself and her principal
        assertThat(record(lawyer).represented).containsExactly(vera)                // not the lawyer
        assertThat(register().flatMap { it.represented }).containsExactlyInAnyOrder(ana, boris, vera)
    }

    @Test
    fun `PM-GA-012 the register takes an owner or a registered proxy's representative, once, on the day of a noticed assembly - nobody else's presence is anybody's`() {
        val ana = owner()
        assertThatThrownBy { record(UUID.randomUUID()) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-012")
        assertThatThrownBy { record(owner(from = "2026-11-21")) }.isInstanceOf(IllegalArgumentException::class.java)       // an owner from tomorrow
        val unsigned = UUID.randomUUID().also { proxy(principal = owner(), agent = it, signedIn = false) }           // a proxy nobody signed in for
        assertThatThrownBy { record(unsigned) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { record(ana, channel = "BY_PHONE") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { record(ana, at = Instant.parse("2026-11-19T20:00:00Z")) }.isInstanceOf(IllegalStateException::class.java)   // the evening before
        assertThat(rows).isEmpty()

        record(ana, at = Instant.parse("2026-11-19T22:30:00Z"))                      // 00:30 on the 20th in Sofia: the day has begun
        assertThatThrownBy { record(ana) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(rows).hasSize(1)

        assembly = assembly!!.copy(status = "DRAFT")
        assertThatThrownBy { record(owner()) }.isInstanceOf(IllegalStateException::class.java)
        val id = assembly!!.id
        assembly = null
        assertThatThrownBy { service().record(entranceId, id, keeper, owner(), "IN_PERSON") }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `PM-GA-016 a remote attendee is counted only after identity verification passes - until then they are on the register and bring nobody`() {
        val (ana, boris) = listOf(owner(), owner())
        proxy(principal = boris, agent = ana)
        val remote = record(ana, channel = "VIDEO")
        assertThat(remote.counted).isFalse()
        assertThat(remote.represented).isEmpty()                                     // neither herself nor her principal
        assertThat(register().single().counted).isFalse()

        assertThatThrownBy { service().verifyIdentity(entranceId, assembly!!.id, remote.attendance.id, keeper, "   ") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-016")
        val passed = service().verifyIdentity(entranceId, assembly!!.id, remote.attendance.id, keeper, " лична карта пред камерата ")
        assertThat(passed.counted).isTrue()
        assertThat(passed.represented).containsExactlyInAnyOrder(ana, boris)
        assertThat(verified.single().how).isEqualTo("лична карта пред камерата")
        assertThat(verified.single().verifiedBy).isEqualTo(keeper.party)
        assertThat(register().single().represented).containsExactlyInAnyOrder(ana, boris)

        assertThatThrownBy { service().verifyIdentity(entranceId, assembly!!.id, remote.attendance.id, keeper, "пак") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(verified).hasSize(1)
    }

    @Test
    fun `PM-GA-016 a person present in person needs no verification, and a line of another assembly or entrance is not there to verify`() {
        val inPerson = record(owner())
        assertThat(inPerson.counted).isTrue()
        assertThatThrownBy { service().verifyIdentity(entranceId, assembly!!.id, inPerson.attendance.id, keeper, "на място") }.isInstanceOf(IllegalStateException::class.java)
        val remote = record(owner(), channel = "VIDEO")
        assertThatThrownBy { service().verifyIdentity(UUID.randomUUID(), assembly!!.id, remote.attendance.id, keeper, "камера") }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { service().verifyIdentity(entranceId, assembly!!.id, UUID.randomUUID(), keeper, "камера") }.isInstanceOf(NoSuchElementException::class.java)
        assertThat(verified).isEmpty()
    }

    @Test
    fun `PM-GA-012 an owner who has sold since is on the register and no longer brings themself`() {
        val seller = owner()
        record(seller)
        owners.remove(seller)                                                        // the book no longer has them owning today
        assertThat(register().single().represented).isEmpty()
    }
}
