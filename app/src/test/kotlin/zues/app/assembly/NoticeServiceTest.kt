package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.law.constantOn
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The notice: the period, the posting act, and what voids it — repositories mocked, the clock fixed.
 * The assembly "table" is one variable, so a sequence of calls sees what the last one saved.
 */
class NoticeServiceTest {

    private val now = Instant.parse("2026-10-09T09:00:00Z")                 // Friday 9 October 2026, noon in Sofia
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val entranceId = UUID.randomUUID()
    private val convenor = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val photo = "3f".repeat(32)

    private var stored: Assembly? = null
    private val items = mutableListOf<AgendaItem>()
    private val acts = mutableListOf<NoticePosting>()

    private val aggregates: JdbcAggregateTemplate = mock {
        on { insert(any<Any>()) } doAnswer {
            when (val row = it.arguments[0]) {
                is Assembly -> stored = row
                is AgendaItem -> items += row
                is NoticePosting -> acts += row
            }
            it.arguments[0]
        }
    }
    private val assemblies: AssemblyRepository = mock {
        on { lock(any(), any()) } doAnswer { stored }
        on { save(any<Assembly>()) } doAnswer { (it.arguments[0] as Assembly).also { saved -> stored = saved } }
    }
    private val agenda: AgendaItemRepository = mock { on { findByAssemblyIdOrderByOrdinal(any()) } doAnswer { items.toList() } }
    private val convening = AssemblyService(aggregates, assemblies, agenda, LawMajorities(), clock)
    private val notices = NoticeService(aggregates, assemblies, agenda, clock)

    private fun convened(meets: String, urgent: Boolean = false, withItem: Boolean = true): Assembly {
        val assembly = convening.convene(
            entranceId, Convene(convenor, "BM", Instant.parse(meets), "фоайето на вх. А", "IN_PERSON", urgent, if (urgent) "спукана тръба" else null),
        )
        if (withItem) convening.addAgendaItem(entranceId, assembly.id, "Отчет на управителя", "GENERAL")
        return assembly
    }

    private fun post(assembly: Assembly, postedAt: String, witness: UUID = owner, hash: String = photo) =
        notices.recordPosting(entranceId, assembly.id, Instant.parse(postedAt), witness, hash)

    @Test
    fun `PM-GA-004 scheduling an assembly 6 days out is blocked, and 7 days out is allowed`() {
        assertThatThrownBy { convened("2026-10-15T16:00:00Z") }                      // Thursday 15th: 6 days after the 9th
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-004").hasMessageContaining("2026-10-16")
        assertThat(stored).isNull()
        assertThat(convened("2026-10-16T05:00:00Z").status).isEqualTo("DRAFT")       // the 16th, at any hour: whole days, not hours
    }

    @Test
    fun `PM-GA-004 the days are Sofia days - a meeting late on the 15th in UTC is on the 16th in Sofia`() {
        assertThat(convened("2026-10-15T21:30:00Z").status).isEqualTo("DRAFT")       // 00:30 on the 16th in Sofia
    }

    @Test
    fun `PM-GA-004 a posting act 6 days before the meeting is refused, and 7 days before records the act with its photograph`() {
        val assembly = convened("2026-10-20T16:00:00Z")
        assertThatThrownBy { notices.recordPosting(entranceId, assembly.id, now.plusSeconds(1), owner, photo) }
            .isInstanceOf(IllegalArgumentException::class.java)                      // a posting still to come is not an act
        stored = stored!!.copy(scheduledAt = Instant.parse("2026-10-15T16:00:00Z")) // as if the meeting were 6 days from the posting
        assertThatThrownBy { post(assembly, "2026-10-09T08:00:00Z") }
            .isInstanceOf(NoticeTooLate::class.java).hasMessageContaining("PM-GA-004")
        assertThat(acts).isEmpty()
        assertThat(stored!!.status).isEqualTo("DRAFT")

        stored = stored!!.copy(scheduledAt = Instant.parse("2026-10-16T16:00:00Z"))
        val act = post(assembly, "2026-10-09T08:00:00Z")
        assertThat(act.photoHash).isEqualTo(photo)
        assertThat(act.postedAt).isEqualTo(Instant.parse("2026-10-09T08:00:00Z"))
        assertThat(act.recordedAt).isEqualTo(now)
        assertThat(stored!!.status).isEqualTo("NOTICED")
        assertThat(stored!!.noticePostedAt).isEqualTo(act.postedAt)
    }

    @Test
    fun `PM-GA-004 the photograph is required as a SHA-256`() {
        val assembly = convened("2026-10-20T16:00:00Z")
        for (bad in listOf("", "photo.jpg", "3F".repeat(32), "3f".repeat(31))) {
            assertThatThrownBy { post(assembly, "2026-10-09T08:00:00Z", hash = bad) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(stored!!.status).isEqualTo("DRAFT")
    }

    @Test
    fun `PM-GA-005 an urgent assembly may meet the configured hours after posting, and not a minute sooner`() {
        val hours = constantOn("GA_URGENT_NOTICE_HOURS", "2026-10-09")
        assertThat(hours.verified).isFalse()                                         // TODO(legal): PM-GA-005
        val exactly = now.plusSeconds(hours.value.toLong() * 3600)                   // not-legal: seconds in an hour
        assertThatThrownBy { convened(exactly.minusSeconds(60).toString(), urgent = true) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-005")
        val assembly = convened(exactly.toString(), urgent = true)

        stored = stored!!.copy(scheduledAt = exactly.minusSeconds(60))                // a minute short of the hours, from the posting
        assertThatThrownBy { post(assembly, now.toString()) }.isInstanceOf(NoticeTooLate::class.java).hasMessageContaining("PM-GA-005")
        assertThat(stored!!.status).isEqualTo("DRAFT")
        stored = stored!!.copy(scheduledAt = exactly)
        post(assembly, now.toString())
        assertThat(stored!!.status).isEqualTo("NOTICED")
    }

    @Test
    fun `PM-GA-005 the shorter period is for an urgent assembly only`() {
        val hours = constantOn("GA_URGENT_NOTICE_HOURS", "2026-10-09").value.toLong()
        assertThatThrownBy { convened(now.plusSeconds(hours * 3600).toString(), urgent = false) }   // not-legal: seconds in an hour
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-004")
    }

    @Test
    fun `PM-GA-007 an assembly is a draft until the posting act is recorded, signed by the convenor and one other person`() {
        val assembly = convened("2026-10-20T16:00:00Z")
        assertThat(stored!!.status).isEqualTo("DRAFT")
        assertThat(stored!!.noticePostedAt).isNull()

        assertThatThrownBy { post(assembly, "2026-10-09T08:00:00Z", witness = convenor) }     // the convenor alone is one signature
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-007")
        assertThat(stored!!.status).isEqualTo("DRAFT")

        val act = post(assembly, "2026-10-09T08:00:00Z")
        assertThat(act.convenorPartyId).isEqualTo(convenor)                          // from the assembly, not from the caller
        assertThat(act.witnessPartyId).isEqualTo(owner)
        assertThat(stored!!.status).isEqualTo("NOTICED")

        assertThatThrownBy { post(assembly, "2026-10-09T08:30:00Z") }.isInstanceOf(IllegalStateException::class.java)   // posted once
        assertThat(acts).hasSize(1)
    }

    @Test
    fun `PM-GA-006 the act keeps the date, hour, place and full agenda the notice stated, and an empty agenda cannot be noticed`() {
        val empty = convened("2026-10-20T16:00:00Z", withItem = false)
        assertThatThrownBy { post(empty, "2026-10-09T08:00:00Z") }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("PM-GA-006")
        verify(assemblies, never()).save(any<Assembly>())

        convening.addAgendaItem(entranceId, empty.id, "Отчет на управителя", "GENERAL")
        convening.addAgendaItem(entranceId, empty.id, "Избор на управител", "GENERAL")
        val act = post(empty, "2026-10-09T08:00:00Z")
        assertThat(act.statedScheduledAt).isEqualTo(Instant.parse("2026-10-20T16:00:00Z"))
        assertThat(act.statedPlace).isEqualTo("фоайето на вх. А")
        assertThat(act.statedAgenda).isEqualTo("1. Отчет на управителя\n2. Избор на управител")
    }

    @Test
    fun `PM-GA-006 adding an agenda item after the notice is posted forces a new notice with a new 7-day clock`() {
        val assembly = convened("2026-10-20T16:00:00Z")
        post(assembly, "2026-10-09T08:00:00Z")

        val added = convening.addAgendaItem(entranceId, assembly.id, "Ремонт на покрива", "GENERAL")
        assertThat(added.noticeVoided).isTrue()
        assertThat(stored!!.status).isEqualTo("DRAFT")
        assertThat(stored!!.noticePostedAt).isNull()

        // the new clock: the meeting is on the 20th, so a notice posted on the 14th is 6 days — too late; the first act does not count
        val laterClock = Clock.fixed(Instant.parse("2026-10-14T09:00:00Z"), ZoneOffset.UTC)
        val later = NoticeService(aggregates, assemblies, agenda, laterClock)
        assertThatThrownBy { later.recordPosting(entranceId, assembly.id, Instant.parse("2026-10-14T08:00:00Z"), owner, photo) }
            .isInstanceOf(NoticeTooLate::class.java)
        assertThat(stored!!.status).isEqualTo("DRAFT")

        // moved to the 21st, the new notice is in time, and its act states the agenda with the added item
        AssemblyService(aggregates, assemblies, agenda, LawMajorities(), laterClock)
            .reschedule(entranceId, assembly.id, Instant.parse("2026-10-21T16:00:00Z"), "фоайето на вх. А")
        val second = later.recordPosting(entranceId, assembly.id, Instant.parse("2026-10-14T08:00:00Z"), owner, photo)
        assertThat(second.statedAgenda).isEqualTo("1. Отчет на управителя\n2. Ремонт на покрива")
        assertThat(stored!!.status).isEqualTo("NOTICED")
        assertThat(acts).hasSize(2)                                                  // the voided notice keeps its act
    }

    @Test
    fun `PM-GA-006 moving the date, the hour or the place voids a posted notice, and an item added to a draft voids nothing`() {
        val assembly = convened("2026-10-20T16:00:00Z")
        assertThat(convening.addAgendaItem(entranceId, assembly.id, "Разни", "GENERAL").noticeVoided).isFalse()
        post(assembly, "2026-10-09T08:00:00Z")

        val moved = convening.reschedule(entranceId, assembly.id, Instant.parse("2026-10-20T17:00:00Z"), "двора")
        assertThat(moved.status).isEqualTo("DRAFT")
        assertThat(moved.noticePostedAt).isNull()
        assertThat(moved.place).isEqualTo("двора")

        assertThatThrownBy { convening.reschedule(entranceId, assembly.id, Instant.parse("2026-10-15T16:00:00Z"), "двора") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-004")
    }
}
