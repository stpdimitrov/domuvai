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

    /** A mandate of the test's entrance as the table would hold it, open unless [ended]. */
    private fun held(body: String, begins: String, runsTo: String, ended: String? = null, at: UUID = entrance) =
        Mandate(UUID.randomUUID(), at, body, UUID.randomUUID(), LocalDate.parse(begins), LocalDate.parse(runsTo), ended?.let { LocalDate.parse(it) })

    @Test
    fun `PM-GOV-004 a new manager ends every open executive mandate before it, on its first day — each end on the record as the successor's doing`() {
        val oldManager = held("BM", "2024-05-10", "2026-05-10")
        val sittingMember = held("MB", "2025-05-10", "2027-05-10")                                // within its own dates: a sole manager replaces the board all the same
        val endedMeanwhile = held("MB", "2025-06-01", "2027-06-01")
        val alreadyEnded = held("BM", "2022-05-10", "2024-05-10", ended = "2024-05-10")
        val controller = held("CTL", "2025-05-10", "2027-05-10")
        val nextDoor = held("BM", "2024-05-10", "2026-05-10", at = UUID.randomUUID())
        whenever(store.ofEntrance(entrance)).thenReturn(listOf(alreadyEnded, oldManager, sittingMember, endedMeanwhile, controller, nextDoor))
        whenever(store.end(oldManager.id, from)).thenReturn(true)
        whenever(store.end(sittingMember.id, from)).thenReturn(true)
        whenever(store.end(endedMeanwhile.id, from)).thenReturn(false)                             // somebody ended it first: not ended twice
        val done = (administration.record(operator, entrance, aMandate("BM")) as MandateAnswer.Done).value
        assertThat(done.succeeded).containsExactly(oldManager.id, sittingMember.id)
        inOrder(store, acts) {
            verify(store).add(eq(done.mandateId), eq(entrance), eq("BM"), eq(maria), eq(from), eq(to), any())
            verify(acts).record(argThat { act == "RECORDED" && mandateId == done.mandateId })
            verify(store).ofEntrance(entrance)
        }
        verify(acts).record(MandateAct("ENDED", entrance, operator.login!!, "PM-GOV-004", oldManager.id, onDay = from, succeededBy = done.mandateId))
        verify(acts).record(MandateAct("ENDED", entrance, operator.login!!, "PM-GOV-004", sittingMember.id, onDay = from, succeededBy = done.mandateId))
        verify(acts, never()).record(argThat { mandateId == endedMeanwhile.id })
        for (untouched in listOf(alreadyEnded, controller, nextDoor)) verify(store, never()).end(eq(untouched.id), any())
    }

    @Test
    fun `PM-GOV-004 recording a manager ends no firm's mandate — that is not this api's to end, in term or out of it`() {
        val firmInTerm = held("PMC", "2025-05-10", "2027-05-10")
        val firmRanOut = held("PMC", "2023-01-01", "2025-01-01")
        whenever(store.ofEntrance(entrance)).thenReturn(listOf(firmRanOut, firmInTerm))
        for (office in listOf("BM", "MB")) assertThat((administration.record(operator, entrance, aMandate(office)) as MandateAnswer.Done).value.succeeded).isEmpty()
        verify(store, never()).end(any(), any())
    }

    @Test
    fun `PM-GOV-004 a new board member ends an earlier manager, and board members whose dates have run out — never a fellow member still in term`() {
        // the new member's first day is 2026-05-10
        val oldManager = held("BM", "2024-01-01", "2026-01-01")
        val ranOut = held("MB", "2024-05-10", "2026-05-10")                                       // runs up to the new member's first day, and not into it
        val inTerm = held("MB", "2025-03-01", "2027-03-01")
        val aDayLeft = held("MB", "2024-05-11", "2026-05-11")
        whenever(store.ofEntrance(entrance)).thenReturn(listOf(oldManager, ranOut, aDayLeft, inTerm))
        whenever(store.end(any(), eq(from))).thenReturn(true)
        val done = (administration.record(operator, entrance, aMandate("MB")) as MandateAnswer.Done).value
        assertThat(done.succeeded).containsExactly(oldManager.id, ranOut.id)
        for (fellow in listOf(inTerm, aDayLeft)) verify(store, never()).end(eq(fellow.id), any())
        for (office in listOf("CTL", "CSH")) assertThat((administration.record(operator, entrance, aMandate(office)) as MandateAnswer.Done).value.succeeded).isEmpty()
    }

    @Test
    fun `PM-GOV-004 the recording side and the reading side read 'successor' alike — on every day, a mandate the recorder ended and one it left alone hold exactly when the reader says`() {
        // An earlier mandate E (2024-01-01 → 2026-01-01) and a later one L recorded after it, for every pair of offices
        // and a spread of first days around E's last. Two rows of the table are then read on every day that matters:
        //  · as recorded — E stamped with L's first day where the recorder ended it;
        //  · as if nobody had stamped anything — the reader finding the successor by itself.
        // Past E's own dates the two agree on every day: the recorder ends exactly what the reader would. Within E's
        // dates only the stamp ends it — that is the one difference, and it is the recorder's alone.
        val begins = LocalDate.parse("2024-01-01")
        val runsTo = LocalDate.parse("2026-01-01")
        var endedInTerm = 0; var endedPastTerm = 0; var leftAlone = 0
        for (earlierBody in listOf("BM", "MB", "CTL", "PMC")) for (laterBody in listOf("BM", "MB", "CTL")) for (gap in listOf(-300L, -1L, 0L, 1L, 90L)) {
            val unstamped = Mandate(UUID.randomUUID(), entrance, earlierBody, UUID.randomUUID(), begins, runsTo, null)
            val laterFrom = runsTo.plusDays(gap)
            org.mockito.Mockito.reset(store)
            whenever(store.ofEntrance(entrance)).thenReturn(listOf(unstamped))
            whenever(store.end(unstamped.id, laterFrom)).thenReturn(true)
            val done = (administration.record(operator, entrance, RecordMandate(laterBody, maria, laterFrom, laterFrom.plusYears(1), "Протокол")) as MandateAnswer.Done).value
            val later = Mandate(done.mandateId, entrance, laterBody, maria, laterFrom, laterFrom.plusYears(1), null)
            val ended = unstamped.id in done.succeeded
            val stamped = if (ended) unstamped.copy(succeededAt = laterFrom) else unstamped
            val case = "$earlierBody then $laterBody, ${gap}d from the earlier one's last day"
            // the recorder ends only a manager or a board member, and exactly the ones the one reading names
            assertThat(ended).describedAs(case).isEqualTo(earlierBody in listOf("BM", "MB") && later.succeeds(unstamped))
            for (day in listOf(laterFrom.minusDays(1), laterFrom, runsTo.minusDays(1), runsTo, maxOf(runsTo, laterFrom).plusDays(1), runsTo.plusYears(3))) {
                val asRecorded = stamped.inForceOn(day, listOf(stamped, later))
                val byTheReaderAlone = unstamped.inForceOn(day, listOf(unstamped, later))
                if (!day.isBefore(runsTo)) assertThat(asRecorded).describedAs("$case, on $day").isEqualTo(byTheReaderAlone)
                else assertThat(byTheReaderAlone).describedAs("$case, on $day: within its dates only a recorded end ends it").isTrue()
                if (ended && !day.isBefore(laterFrom)) assertThat(asRecorded).describedAs("$case, on $day").isFalse()
            }
            if (ended && laterFrom.isBefore(runsTo)) endedInTerm++ else if (ended) endedPastTerm++ else leftAlone++
        }
        assertThat(listOf(endedInTerm, endedPastTerm, leftAlone)).allMatch { it > 0 }             // every kind of case was drawn
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

    /** The term the law module has in force on a day, in years — asked of the law, so the test names no figure of its own. */
    private fun term(code: String, on: LocalDate) = zues.law.numberOn(code, on.toString()).toLong()

    @Test
    fun `PM-GOV-004 a board's or a manager's mandate runs no longer than the term in force on its first day — to the day`() {
        for (office in listOf("BM", "MB")) {
            val last = from.plusYears(term("MANDATE_TERM_YEARS", from))
            assertThat(administration.record(operator, entrance, aMandate(office).copy(validTo = last))).describedAs("$office to the day").isInstanceOf(MandateAnswer.Done::class.java)
            assertThatThrownBy { administration.record(operator, entrance, aMandate(office).copy(validTo = last.plusDays(1))) }.describedAs("$office a day over")
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GOV-004")
        }
        verify(store, org.mockito.kotlin.times(2)).add(any(), any(), any(), any(), any(), any(), any())   // the two that fit, and neither that did not
    }

    @Test
    fun `PM-GOV-007 a controller's mandate runs no longer than the controller's term in force on its first day`() {
        val last = from.plusYears(term("CONTROL_TERM_YEARS", from))
        assertThat(administration.record(operator, entrance, aMandate("CTL").copy(validTo = last))).isInstanceOf(MandateAnswer.Done::class.java)
        assertThatThrownBy { administration.record(operator, entrance, aMandate("CTL").copy(validTo = last.plusDays(1))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GOV-007")
    }

    @Test
    fun `the cashier keeps the cap every mandate had — from a constant of its own, not yet confirmed, and a refusal cites no rule`() {
        val last = from.plusYears(term("CASHIER_TERM_YEARS", from))
        assertThat(administration.record(operator, entrance, aMandate("CSH").copy(validTo = last))).isInstanceOf(MandateAnswer.Done::class.java)
        assertThatThrownBy { administration.record(operator, entrance, aMandate("CSH").copy(validTo = last.plusDays(1))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("not yet confirmed").hasMessageNotContaining("PM-")
        assertThat(zues.law.unverified().map { it.code }).contains("CASHIER_TERM_YEARS").doesNotContain("MANDATE_TERM_YEARS", "CONTROL_TERM_YEARS")
    }

    @Test
    fun `PM-SYS-002 the term is the one in force on the mandate's first day — a day the law has no term for is refused, whatever today is`() {
        // The law module's term is in force from a day; a mandate dated before it has no term to be judged by, and is
        // not recorded on today's. (Today, by the test's clock, is 2026 — a term is in force.)
        val beforeTheStatute = LocalDate.parse("2008-12-31")
        assertThatThrownBy { administration.record(operator, entrance, aMandate("BM").copy(validFrom = beforeTheStatute, validTo = beforeTheStatute.plusMonths(6))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("no term").hasMessageContaining("2008-12-31")
        verify(store, never()).add(any(), any(), any(), any(), any(), any(), any())
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
