package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The statement with the repositories mocked — no Spring, no database. Proves the balance is the
 * sum of the unit's receivable postings and that every charge line is itemised with its
 * derivation (PM-FEE-018).
 */
class StatementServiceTest {

    private val postings: PostingRepository = mock()
    private val chargeLines: ChargeLineRepository = mock()
    private val chargeRuns: ChargeRunRepository = mock()
    private val service = StatementService(postings, chargeLines, chargeRuns)

    private val unitId = UUID.randomUUID()
    private val runId = UUID.randomUUID()

    private fun posting(amount: Long) =
        PostingRow(UUID.randomUUID(), UUID.randomUUID(), runId, "RECEIVABLE", unitId, amount, "EUR", LocalDate.of(2026, 5, 1))

    private fun line(component: String, amount: Long, derivation: String) =
        ChargeLineRow(UUID.randomUUID(), UUID.randomUUID(), runId, unitId, component, "BY_IDEAL_PARTS",
            BigDecimal("60.000000"), amount, "EUR", derivation)

    private fun run(period: String) =
        ChargeRunRow(runId, UUID.randomUUID(), period, LocalDate.of(2026, 5, 1), JsonbValue("{}"), "hash", "1.3", "0.1.0", "COMPLETE")

    @Test
    fun `PM-FEE-018 the statement itemises each charge with its derivation`() {
        whenever(postings.findByUnitIdAndAccount(unitId, "RECEIVABLE")).thenReturn(listOf(posting(6000), posting(12000)))
        whenever(chargeLines.findByUnitId(unitId)).thenReturn(
            listOf(
                line("MANAGEMENT", 6000, "100.00 € split by by_ideal_parts · 60.0000%"),
                line("MAINTENANCE", 12000, "200.00 € split by by_ideal_parts · 60.0000%"),
            ),
        )
        whenever(chargeRuns.findAllById(any())).thenReturn(listOf(run("2026-05")))

        val statement = service.forUnit(unitId)

        assertThat(statement.balanceMinor).isEqualTo(18000)          // 6000 + 12000, from the postings
        assertThat(statement.lines).hasSize(2)
        assertThat(statement.lines.map { it.period }).containsOnly("2026-05")
        assertThat(statement.lines).allMatch { it.derivation.isNotBlank() }
        assertThat(statement.lines.map { it.component }).containsExactly("MAINTENANCE", "MANAGEMENT")  // sorted
    }

    @Test
    fun `a unit with nothing billed owes nothing`() {
        val statement = service.forUnit(unitId)   // default mocks return empty lists
        assertThat(statement.balanceMinor).isEqualTo(0)
        assertThat(statement.lines).isEmpty()
    }
}
