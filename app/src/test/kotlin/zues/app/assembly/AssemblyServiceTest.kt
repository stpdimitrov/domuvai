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
import zues.law.Comparison
import zues.law.Denominator
import zues.law.MajorityRule
import zues.law.majorityRuleOn
import java.time.Instant
import java.util.UUID

/**
 * Convening and the agenda with the repositories mocked — no Spring, no database. The tables and
 * their constraints are proved by AssemblyPersistenceIT.
 */
class AssemblyServiceTest {

    private val aggregates: JdbcAggregateTemplate = mock { on { insert(any<Any>()) } doAnswer { it.arguments[0] } }
    private val assemblies: AssemblyRepository = mock()
    private val agenda: AgendaItemRepository = mock()
    private fun service(majorities: Majorities = LawMajorities()) = AssemblyService(aggregates, assemblies, agenda, majorities)

    private val entranceId = UUID.randomUUID()
    private val convenor = UUID.randomUUID()

    private fun convening(convenedAs: String = "BM", urgent: Boolean = false, reason: String? = null) =
        Convene(convenor, convenedAs, Instant.parse("2026-11-20T17:00:00Z"), "фоайето на вх. А", "IN_PERSON", urgent, reason)

    private fun draft(): Assembly = service().convene(entranceId, convening()).also {
        whenever(assemblies.lock(it.id, entranceId)).thenReturn(it)
    }

    @Test
    fun `PM-GA-002 the management board, the manager and the control board may convene, and the capacity is stored`() {
        for (office in listOf("MB", "BM", "CTL")) {
            val assembly = service().convene(entranceId, convening(convenedAs = office))
            assertThat(assembly.convenedAs).isEqualTo(office)
            assertThat(assembly.convenedBy).isEqualTo(convenor)
            assertThat(assembly.status).isEqualTo("DRAFT")
        }
    }

    @Test
    fun `PM-GA-002 no other capacity may convene - a cashier, a management company, an owner alone`() {
        for (other in listOf("CSH", "PMC", "OWN", "")) {
            assertThatThrownBy { service().convene(entranceId, convening(convenedAs = other)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        verify(aggregates, never()).insert(any<Any>())
    }

    @Test
    fun `PM-GA-005 an urgent assembly stores its justification`() {
        val assembly = service().convene(entranceId, convening(urgent = true, reason = "  спукана тръба в мазето  "))
        assertThat(assembly.urgent).isTrue()
        assertThat(assembly.urgencyReason).isEqualTo("спукана тръба в мазето")
    }

    @Test
    fun `PM-GA-005 the urgent flag without a justification is refused, and so is a justification without the flag`() {
        for (reason in listOf(null, "", "   ")) {
            assertThatThrownBy { service().convene(entranceId, convening(urgent = true, reason = reason)) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-005")
        }
        assertThatThrownBy { service().convene(entranceId, convening(urgent = false, reason = "бързаме")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-005")
        verify(aggregates, never()).insert(any<Any>())
    }

    @Test
    fun `PM-VOTE-004 an item typed COMMON_PART_USE_RIGHT is bound to the configured threshold with its source citation`() {
        // The number is the test's own: the catalogue has none yet. What is proved is that the configured one is used.
        val configured = MajorityRule(
            "COMMON_PART_USE_RIGHT", "88.5", Comparison.AT_LEAST, Denominator.TOTAL, "2026-01-01", "чл. 17 ЗУЕС", false, "PM-VOTE-004",
        )
        val assembly = draft()
        val (item, majority) = service { type, on -> majorityRuleOn(type, on, listOf(configured)) }
            .addAgendaItem(entranceId, assembly.id, "Отдаване на покрива под наем", "COMMON_PART_USE_RIGHT")

        assertThat(majority).isEqualTo(configured)
        assertThat(majority.source).isEqualTo("чл. 17 ЗУЕС")
        assertThat(majority.rule).isEqualTo("PM-VOTE-004")
        assertThat(item.itemType).isEqualTo("COMMON_PART_USE_RIGHT")
        assertThat(item.majorityRuleId).isEqualTo("COMMON_PART_USE_RIGHT@2026-01-01")
        assertThat(item.ordinal).isEqualTo(1)
    }

    @Test
    fun `PM-VOTE-004 a threshold not yet in force on the meeting's day is not used`() {
        val later = MajorityRule(
            "COMMON_PART_USE_RIGHT", "88.5", Comparison.AT_LEAST, Denominator.TOTAL, "2026-11-21", "чл. 17 ЗУЕС", false, "PM-VOTE-004",
        )
        val assembly = draft()                                   // meets 20 November 2026 in Sofia
        assertThatThrownBy {
            service { type, on -> majorityRuleOn(type, on, listOf(later)) }
                .addAgendaItem(entranceId, assembly.id, "Отдаване на покрива под наем", "COMMON_PART_USE_RIGHT")
        }.isInstanceOf(MajorityPending::class.java)
    }

    @Test
    fun `PM-VOTE-004 with no threshold confirmed in law the item is refused, naming the rule and its source`() {
        val assembly = draft()
        assertThatThrownBy { service().addAgendaItem(entranceId, assembly.id, "Отдаване на покрива под наем", "COMMON_PART_USE_RIGHT") }
            .isInstanceOf(MajorityPending::class.java)
            .hasMessageContaining("PM-VOTE-004").hasMessageContaining("чл. 17 ЗУЕС").hasMessageContaining("TODO(legal)")
        verify(agenda, never()).findByAssemblyIdOrderByOrdinal(any())
    }

    @Test
    fun `an item of a type the law does not know is the caller's error, and an ordinary item takes the next ordinal`() {
        val assembly = draft()
        assertThatThrownBy { service().addAgendaItem(entranceId, assembly.id, "Разни", "WHIM") }
            .isInstanceOf(IllegalArgumentException::class.java)

        whenever(agenda.findByAssemblyIdOrderByOrdinal(assembly.id))
            .thenReturn(listOf(AgendaItem(UUID.randomUUID(), entranceId, assembly.id, 1, "Отчет", "GENERAL", "GENERAL@2009-01-01")))
        val (item, majority) = service().addAgendaItem(entranceId, assembly.id, "Избор на управител", "GENERAL")
        assertThat(item.ordinal).isEqualTo(2)
        assertThat(majority.denominator).isEqualTo(Denominator.REPRESENTED)
    }

    @Test
    fun `an assembly of another entrance is not found`() {
        val assembly = draft()
        assertThatThrownBy { service().addAgendaItem(UUID.randomUUID(), assembly.id, "Отчет", "GENERAL") }
            .isInstanceOf(NoSuchElementException::class.java)
    }
}
