package zues.app.money

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.app.assembly.GaDecisions
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** The pure [inForce], and recording with the collaborators mocked. `TariffVersionIT` proves the table and the real port. */
class TariffVersionTest {

    private val entrance = UUID.randomUUID()
    private val versions: TariffVersionRepository = mock()
    private val aggregates: JdbcAggregateTemplate = mock()
    private val decisions: GaDecisions = mock { on { passed(any(), any()) } doReturn true }
    private val tariffs = TariffVersions(versions, aggregates, decisions, jacksonObjectMapper())

    private fun row(from: String, to: String? = null) = TariffVersionRow(
        UUID.randomUUID(), entrance, "GA-$from", YearMonth.parse(from).atDay(1), to?.let { YearMonth.parse(it).atEndOfMonth() }, JsonbValue("{}"),
    )

    private val maintenance = listOf(AdoptedLine("MAINTENANCE", "BY_IDEAL_PARTS", totalMinor = 10_000))

    @Test
    fun `PM-FEE-012 the version in force for a month is the one begun latest by then, and a later one ends the earlier`() {
        val first = row("2026-01")
        val second = row("2026-06")
        val all = listOf(second, first)
        assertThat(inForce(all, YearMonth.parse("2025-12"))).isNull()
        assertThat(inForce(all, YearMonth.parse("2026-01"))).isEqualTo(first)
        assertThat(inForce(all, YearMonth.parse("2026-05"))).isEqualTo(first)
        assertThat(inForce(all, YearMonth.parse("2026-06"))).isEqualTo(second)
        assertThat(inForce(all, YearMonth.parse("2030-01"))).isEqualTo(second)
    }

    @Test
    fun `PM-FEE-012 a version is in force through its last month and not after, nor does the earlier one come back`() {
        val first = row("2026-01")
        val second = row("2026-06", to = "2026-08")
        val all = listOf(first, second)
        assertThat(inForce(all, YearMonth.parse("2026-08"))).isEqualTo(second)
        assertThat(inForce(all, YearMonth.parse("2026-09"))).isNull()
    }

    @Test
    fun `PM-FEE-012 every tariff version carries protocol_id and validity dates`() {
        val view = tariffs.record(entrance, RecordTariffVersion("GA-2027-1", "2027-01", "2027-06", maintenance, listOf(MeteredPrice("WATER", 230))))
        val stored = argumentCaptor<TariffVersionRow>().also { verify(aggregates).insert(it.capture()) }.firstValue
        assertThat(stored.decisionId).isEqualTo("GA-2027-1")
        assertThat(stored.validFrom).isEqualTo(LocalDate.of(2027, 1, 1))
        assertThat(stored.validTo).isEqualTo(LocalDate.of(2027, 6, 30))
        assertThat(view.decisionId).isEqualTo("GA-2027-1")
        assertThat(view.validFrom).isEqualTo("2027-01")
        assertThat(view.validTo).isEqualTo("2027-06")
        assertThat(view.lines).isEqualTo(maintenance)
        assertThat(view.consumption).containsExactly(MeteredPrice("WATER", 230))
        verify(decisions).passed(entrance, "GA-2027-1")
    }

    @Test
    fun `PM-FEE-012 a version on no passed decision of the entrance is refused naming the rule, and nothing is stored`() {
        whenever(decisions.passed(entrance, "GA-none")).thenReturn(false)
        assertThatThrownBy { tariffs.record(entrance, RecordTariffVersion("GA-none", "2027-01", lines = maintenance)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("GA-none").hasMessageContaining("PM-FEE-012")
        verify(aggregates, never()).insert(any<TariffVersionRow>())
    }

    @Test
    fun `PM-FEE-012 two versions of an entrance beginning in the same month are refused`() {
        whenever(versions.findByEntranceId(entrance)).thenReturn(listOf(row("2027-01")))
        assertThatThrownBy { tariffs.record(entrance, RecordTariffVersion("GA-2027-2", "2027-01", lines = maintenance)) }
            .isInstanceOf(TariffVersionConflict::class.java)
        verify(aggregates, never()).insert(any<TariffVersionRow>())
    }

    @Test
    fun `PM-FEE-012 a version ending before it begins, on a malformed month, or with a malformed line is refused`() {
        val bad = listOf(
            RecordTariffVersion("GA-1", "2027-03", "2027-02", maintenance),
            RecordTariffVersion("GA-1", "2027-1-01", lines = maintenance),
            RecordTariffVersion("GA-1", "2027-01"),
            RecordTariffVersion("GA-1", "2027-01", lines = listOf(AdoptedLine("MAINTENANCE", "METERED", rateMinor = 1))),
            RecordTariffVersion("GA-1", "2027-01", lines = listOf(AdoptedLine("MAINTENANCE", "PER_UNIT", rateMinor = 1, totalMinor = 1))),
            RecordTariffVersion("GA-1", "2027-01", lines = listOf(AdoptedLine("MAINTENANCE", "PER_UNIT", rateMinor = -1))),
            RecordTariffVersion("GA-1", "2027-01", lines = listOf(AdoptedLine("REPAIR_FUND", "PER_UNIT", rateMinor = 1, item = "CONCIERGE"))),
            RecordTariffVersion("GA-1", "2027-01", consumption = listOf(MeteredPrice("CONCIERGE", 1))),
        )
        for (request in bad) {
            assertThatThrownBy { tariffs.record(entrance, request) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        verify(aggregates, never()).insert(any<TariffVersionRow>())
    }
}
