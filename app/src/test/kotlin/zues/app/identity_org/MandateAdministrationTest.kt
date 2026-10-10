package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Login
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private const val THE_ISSUER = "https://id.example.test/realms/domuvai"

/**
 * Recording and ending a mandate, with the real policy and the real administrators over a configuration the test
 * gives and the tables mocked — no Spring, no database. Proves only a login the deployment names does either, that
 * everybody else is refused before anything is looked at or changed, that a new executive mandate ends the ones it
 * succeeds, and that every act is recorded (PM-SEC-001, PM-GOV-004, PM-SEC-004).
 */
class MandateAdministrationTest {

    private val store: MandateStore = mock()
    private val acts: MandateActLog = mock()
    private val entrance = UUID.randomUUID()
    private val manager = Asking(Login(THE_ISSUER, "manager"), UUID.randomUUID())
    private val owner = Asking(Login(THE_ISSUER, "owner"), UUID.randomUUID())
    private val inTheEntrance = RoleSource { who, at, _ ->
        when (who) { manager -> setOf(Held(Role.BM, at)); owner -> setOf(Held(Role.OWN, at, setOf(UUID.randomUUID()))); else -> emptySet() }
    }
    private val policy = Policy(listOf(inTheEntrance, Administrators(THE_ISSUER, "operator")))
    // 00:30 on 1 June in Sofia; still 31 May in UTC
    private val clock = Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC)
    private val administration = MandateAdministration(store, policy, acts, clock)

    private val operator = Asking(Login(THE_ISSUER, "operator"), null)
    private val maria = UUID.randomUUID()
    private val from = LocalDate.parse("2026-05-10")
    private val to = LocalDate.parse("2028-05-10")
    private fun aMandate(body: String = "BM") = RecordMandate(body, maria, from, to, "  Протокол № 3 от 10.05.2026 ")

    @Test
    fun `PM-SEC-001 the administrator records a mandate from its protocol, and the act is on the record`() {
        val done = (administration.record(operator, entrance, aMandate("CTL")) as MandateAnswer.Done).value
        verify(store).add(done.mandateId, entrance, "CTL", maria, from, to, "Протокол № 3 от 10.05.2026")
        verify(acts).record(MandateAct("RECORDED", entrance, operator.login!!, "PM-SEC-001", done.mandateId, "CTL", maria, from, to, protocolRef = "Протокол № 3 от 10.05.2026"))
        assertThat(done.succeeded).isEmpty()
        verify(store, never()).open(any(), any(), any(), any())                                    // a controller succeeds nobody
        verify(store, never()).end(any(), any())
    }

    @Test
    fun `PM-SEC-001 nobody else records or ends a mandate — refused before anything is looked at or changed, a signed-in attempt recorded`() {
        val others = listOf(
            manager, owner, Asking(Login(THE_ISSUER, "stranger"), UUID.randomUUID()), Asking(Login(THE_ISSUER, "untied"), null),
            Asking(Login("$THE_ISSUER-other", "operator"), null), Asking(null, UUID.randomUUID()), Asking(null, null),
        )
        val someMandate = UUID.randomUUID()
        for (who in others) {
            assertThat(administration.record(who, entrance, aMandate())).describedAs("$who").isEqualTo(MandateAnswer.Refused("PM-SEC-001"))
            assertThat(administration.record(who, entrance, RecordMandate())).describedAs("$who").isEqualTo(MandateAnswer.Refused("PM-SEC-001"))   // not told what it lacks
            assertThat(administration.end(who, entrance, someMandate, from, "Протокол")).describedAs("$who").isEqualTo(MandateAnswer.Refused("PM-SEC-001"))
            assertThat(administration.end(who, entrance, someMandate, null, null)).describedAs("$who").isEqualTo(MandateAnswer.Refused("PM-SEC-001"))
        }
        verifyNoInteractions(store)
        // the manager making himself a mandate: entered as his attempt, with what he asked for
        verify(acts).record(MandateAct("RECORD_REFUSED", entrance, manager.login!!, "PM-SEC-001", body = "BM", partyId = maria))
        verify(acts).record(MandateAct("END_REFUSED", entrance, owner.login!!, "PM-SEC-001", someMandate, onDay = from))
        verify(acts, never()).record(argThat { act == "RECORDED" || act == "ENDED" })
        verify(acts, never()).record(argThat { by.subject.isEmpty() })                              // nobody signed in: nobody to enter
        administration.record(owner, entrance, RecordMandate("B\u0000M\n" + "x".repeat(200), maria))
        verify(acts).record(argThat { act == "RECORD_REFUSED" && body == "BM" + "x".repeat(62) })   // kept as a short line of ordinary characters
    }

    @Test
    fun `PM-GOV-004 a new manager ends every open executive mandate before it, on its first day — each end on the record as the successor's doing`() {
        val oldManager = UUID.randomUUID()
        val oldBoardMember = UUID.randomUUID()
        val endedMeanwhile = UUID.randomUUID()
        whenever(store.open(entrance, listOf("BM", "MB"), from, false)).thenReturn(listOf(oldManager, oldBoardMember, endedMeanwhile))
        whenever(store.end(oldManager, from)).thenReturn(true)
        whenever(store.end(oldBoardMember, from)).thenReturn(true)
        whenever(store.end(endedMeanwhile, from)).thenReturn(false)                                // somebody ended it first: not ended twice
        val done = (administration.record(operator, entrance, aMandate("BM")) as MandateAnswer.Done).value
        assertThat(done.succeeded).containsExactly(oldManager, oldBoardMember)
        inOrder(store, acts) {
            verify(store).add(eq(done.mandateId), eq(entrance), eq("BM"), eq(maria), eq(from), eq(to), any())
            verify(acts).record(argThat { act == "RECORDED" && mandateId == done.mandateId })
            verify(store).open(entrance, listOf("BM", "MB"), from, false)
        }
        verify(acts).record(MandateAct("ENDED", entrance, operator.login!!, "PM-GOV-004", oldManager, onDay = from, succeededBy = done.mandateId))
        verify(acts).record(MandateAct("ENDED", entrance, operator.login!!, "PM-GOV-004", oldBoardMember, onDay = from, succeededBy = done.mandateId))
        verify(acts, never()).record(argThat { mandateId == endedMeanwhile })
    }

    @Test
    fun `PM-GOV-004 a new board member ends an earlier manager, and board members whose dates have run out — never a fellow member still in term`() {
        val oldManager = UUID.randomUUID()
        val ranOut = UUID.randomUUID()
        whenever(store.open(entrance, listOf("BM"), from, false)).thenReturn(listOf(oldManager))
        whenever(store.open(entrance, listOf("MB"), from, true)).thenReturn(listOf(ranOut))
        whenever(store.end(any(), eq(from))).thenReturn(true)
        val done = (administration.record(operator, entrance, aMandate("MB")) as MandateAnswer.Done).value
        assertThat(done.succeeded).containsExactly(oldManager, ranOut)
        verify(store, never()).open(entrance, listOf("MB"), from, false)                           // board members in term are not asked for
        verify(store, never()).open(eq(entrance), eq(listOf("BM", "MB")), any(), any())
        for (office in listOf("CTL", "CSH")) assertThat((administration.record(operator, entrance, aMandate(office)) as MandateAnswer.Done).value.succeeded).isEmpty()
    }

    @Test
    fun `a mandate that is not recorded is not on the record as recorded — whatever it lacks, the administrator is told`() {
        val lacking = listOf(
            aMandate().copy(body = null), aMandate().copy(body = "PMC"), aMandate().copy(body = "KING"), aMandate().copy(partyId = null),
            aMandate().copy(validFrom = null), aMandate().copy(validTo = null), aMandate().copy(validTo = from), aMandate().copy(validTo = from.minusDays(1)),
            aMandate().copy(protocolRef = null), aMandate().copy(protocolRef = "  "), aMandate().copy(protocolRef = "\u0000\n"), aMandate().copy(protocolRef = "п".repeat(PROTOCOL_REF_MAX + 1)),
        )
        for (request in lacking) assertThatThrownBy { administration.record(operator, entrance, request) }.describedAs("$request").isInstanceOf(IllegalArgumentException::class.java)
        verifyNoInteractions(store, acts)
    }

    @Test
    fun `PM-SEC-011 an end is recorded once, with its basis, on a day the mandate had begun and not in the future — a past day's answer is not moved afterwards`() {
        val mandate = UUID.randomUUID()
        val began = LocalDate.parse("2024-05-10")
        val today = LocalDate.parse("2026-06-01")                                                  // in Sofia; the clock's own day is 31 May
        whenever(store.find(entrance, mandate)).thenReturn(began to null)
        whenever(store.end(mandate, today)).thenReturn(true)
        assertThat(administration.end(operator, entrance, mandate, today, " Протокол № 4, оставка ")).isEqualTo(MandateAnswer.Done(MandateEnded(mandate, today)))
        verify(acts).record(MandateAct("ENDED", entrance, operator.login!!, "PM-SEC-001", mandate, onDay = today, protocolRef = "Протокол № 4, оставка"))

        whenever(store.find(entrance, mandate)).thenReturn(began to today)                         // now it has its end
        assertThatThrownBy { administration.end(operator, entrance, mandate, from, "Протокол") }.isInstanceOf(MandateAlreadyEnded::class.java)
        val another = UUID.randomUUID()
        whenever(store.find(entrance, another)).thenReturn(began to null)
        assertThatThrownBy { administration.end(operator, entrance, another, began.minusDays(1), "Протокол") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { administration.end(operator, entrance, another, today.plusDays(1), "Протокол") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("future")
        assertThatThrownBy { administration.end(operator, entrance, another, null, "Протокол") }.isInstanceOf(IllegalArgumentException::class.java)
        for (noBasis in listOf(null, " ", "\u0000", "п".repeat(PROTOCOL_REF_MAX + 1)))
            assertThatThrownBy { administration.end(operator, entrance, another, from, noBasis) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { administration.end(operator, entrance, UUID.randomUUID(), from, "Протокол") }.isInstanceOf(NoSuchElementException::class.java)   // not this entrance's
        whenever(store.end(another, from)).thenReturn(false)                                       // ended by somebody else between the look and the write
        assertThatThrownBy { administration.end(operator, entrance, another, from, "Протокол") }.isInstanceOf(MandateAlreadyEnded::class.java)
        verify(store, never()).end(eq(mandate), eq(from))
        verify(store, never()).end(eq(another), eq(today.plusDays(1)))
        verify(acts, never()).record(argThat { mandateId == another })
    }

    @Test
    fun `PM-GOV-004 a mandate is recorded after what it succeeds — a manager beside or behind another executive mandate, or a board member beside or behind a manager, is refused`() {
        whenever(store.beginsNoEarlier(entrance, listOf("BM", "MB"), from)).thenReturn(true)
        assertThatThrownBy { administration.record(operator, entrance, aMandate("BM")) }.isInstanceOf(MandateOutOfOrder::class.java)
        whenever(store.beginsNoEarlier(entrance, listOf("BM"), from)).thenReturn(true)
        assertThatThrownBy { administration.record(operator, entrance, aMandate("MB")) }.isInstanceOf(MandateOutOfOrder::class.java)
        verify(store, never()).add(any(), any(), any(), any(), any(), any(), any())
        verifyNoInteractions(acts)
        // a board member beside other members, and a controller or a cashier beside anybody, are not: the question is not asked of them
        whenever(store.beginsNoEarlier(entrance, listOf("BM"), from)).thenReturn(false)
        assertThat(administration.record(operator, entrance, aMandate("MB"))).isInstanceOf(MandateAnswer.Done::class.java)
        for (office in listOf("CTL", "CSH")) assertThat(administration.record(operator, entrance, aMandate(office))).isInstanceOf(MandateAnswer.Done::class.java)
        verify(store, never()).beginsNoEarlier(eq(entrance), eq(listOf("MB")), any())
    }

    @Test
    fun `who may record is decided as at today in Sofia, whatever the mandate's own dates`() {
        val asked = mutableListOf<LocalDate>()
        val dated = object : RoleSource {
            override fun held(who: Asking, entranceId: UUID, asAt: LocalDate): Set<Held> = emptySet()
            override fun administers(who: Asking, asAt: LocalDate) = (asAt == LocalDate.parse("2026-06-01")).also { asked += asAt }
        }
        val sofia = MandateAdministration(store, Policy(listOf(dated)), acts, clock)
        assertThat(sofia.record(operator, entrance, aMandate("CTL").copy(validFrom = LocalDate.parse("2020-01-01"), validTo = LocalDate.parse("2021-01-01")))).isInstanceOf(MandateAnswer.Done::class.java)
        assertThat(asked).containsOnly(LocalDate.parse("2026-06-01"))
        // an allowance with no login to put on the record is a refusal
        val anyone = object : RoleSource {
            override fun held(who: Asking, entranceId: UUID, asAt: LocalDate): Set<Held> = emptySet()
            override fun administers(who: Asking, asAt: LocalDate) = true
        }
        assertThat(MandateAdministration(store, Policy(listOf(anyone)), acts, clock).record(Asking(null, UUID.randomUUID()), entrance, aMandate())).isEqualTo(MandateAnswer.Refused("PM-SEC-001"))
    }
}
