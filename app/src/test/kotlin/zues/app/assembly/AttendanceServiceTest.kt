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
    private var assembly: Assembly? = Assembly(UUID.randomUUID(), entranceId, UUID.randomUUID(), "BM", meets, "фоайето", "HYBRID", "NOTICED", false, null)
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

    private fun proxy(principal: UUID, agent: UUID, signedIn: Boolean = true, scope: String = "WHOLE_AGENDA", at: Instant = meets.minusSeconds(3600)) = Proxy(
        UUID.randomUUID(), entranceId, assembly!!.id, principal, agent, "THIRD_PARTY", scope, if (scope == "WHOLE_AGENDA") null else "1", "WRITTEN", at,
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
        assertThatThrownBy { record(ana, at = Instant.parse("2026-11-20T22:30:00Z")) }.isInstanceOf(IllegalStateException::class.java)   // 00:30 on the 21st in Sofia: the day after
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
        // a real line, asked for through another assembly of the same entrance
        val here = assembly!!
        assembly = here.copy(id = UUID.randomUUID())
        assertThatThrownBy { service().verifyIdentity(entranceId, assembly!!.id, remote.attendance.id, keeper, "камера") }.isInstanceOf(NoSuchElementException::class.java)
        assembly = here
        assertThat(verified).isEmpty()
    }

    @Test
    fun `PM-ORG-011 whom a line brings is as of the day of the sitting, whenever the register is read - a sale the day after changes nothing`() {
        val seller = owner()
        val buyerLater = owner(from = "2026-11-21")                                  // an owner only from the day after
        proxy(principal = buyerLater, agent = seller)
        record(seller)
        owners[seller] = LocalDate.parse("2030-01-01")                              // as if the book now had them owning only from 2030: not on the day
        assertThat(register().single().represented).isEmpty()
        owners[seller] = LocalDate.parse("2026-01-01")
        val readNextYear = AttendanceService(aggregates, assemblies, attendance, verifications, proxies, book, Clock.fixed(Instant.parse("2027-06-01T09:00:00Z"), ZoneOffset.UTC))
        assertThat(readNextYear.register(entranceId, assembly!!.id).single().represented).containsExactly(seller)   // read a year on: the same answer
        assertThat(buyerLater).isNotIn(register().flatMap { it.represented })
    }

    @Test
    fun `PM-ORG-005 nobody is present twice - an owner who gave a proxy is not entered beside their representative`() {
        val (ana, boris) = listOf(owner(), owner())
        proxy(principal = boris, agent = ana)
        record(ana)
        assertThatThrownBy { record(boris) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("PM-ORG-005")
        assertThat(register().flatMap { it.represented }).containsExactlyInAnyOrder(ana, boris)      // Boris once, through Ana
        assertThat(service().isPresent(assembly!!.id, ana)).isTrue()
        assertThat(service().isPresent(assembly!!.id, boris)).isFalse()
        assertThat(service().begun(assembly!!.id)).isTrue()
    }

    @Test
    fun `PM-GA-012 a line brings owners only, by proxies for the whole agenda registered before it was entered`() {
        val ana = owner()
        val holderOfUse = UUID.randomUUID()                                          // holds a right of use, owns nothing
        val soldSince = owner().also { owners.remove(it) }                           // appointed, then sold before the day
        val listedOnly = owner()
        val lateComer = owner()
        proxy(principal = holderOfUse, agent = ana)
        proxy(principal = soldSince, agent = ana)
        proxy(principal = listedOnly, agent = ana, scope = "LISTED_ITEMS")
        proxy(principal = lateComer, agent = ana, at = meets.plusSeconds(600))       // handed over ten minutes after Ana was entered
        assertThat(record(ana).represented).containsExactly(ana)
        assertThat(register().single().represented).containsExactly(ana)
        // an agent whose only principal is not an owner is nobody's presence
        val lawyer = UUID.randomUUID().also { proxy(principal = holderOfUse, agent = it) }
        assertThatThrownBy { record(lawyer) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `PM-GA-016 a person is present in the way the assembly meets, and nobody verifies their own identity`() {
        val ana = owner()
        assembly = assembly!!.copy(mode = "IN_PERSON")
        assertThatThrownBy { record(ana, channel = "VIDEO") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-016")
        assembly = assembly!!.copy(mode = "VIDEO")
        assertThatThrownBy { record(ana, channel = "IN_PERSON") }.isInstanceOf(IllegalArgumentException::class.java)
        val remote = record(ana, channel = "VIDEO")
        val herself = Actor(ana, keeper.loginIssuer, "login-ana")
        assertThatThrownBy { service().verifyIdentity(entranceId, assembly!!.id, remote.attendance.id, herself, "аз съм") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-016")
        // nor on another day
        val dayAfter = AttendanceService(aggregates, assemblies, attendance, verifications, proxies, book, Clock.fixed(meets.plusSeconds(86_400), ZoneOffset.UTC))
        assertThatThrownBy { dayAfter.verifyIdentity(entranceId, assembly!!.id, remote.attendance.id, keeper, "камера") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(verified).isEmpty()
        assertThat(service().verifyIdentity(entranceId, assembly!!.id, remote.attendance.id, keeper, "камера").identityVerifiedAt).isEqualTo(meets)
    }
}
