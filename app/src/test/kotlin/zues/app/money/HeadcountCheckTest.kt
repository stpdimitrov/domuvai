package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.registry.UnitForCharging
import zues.app.registry.Units
import java.time.LocalDate
import java.util.UUID

/**
 * The headcount exception report with the run and the book mocked — no Spring, no database. Proves it reads the
 * billed persons from the basis the run keeps and the declared ones as of the run's own legal date, and lists
 * exactly the units that differ (PM-BOOK-012).
 */
class HeadcountCheckTest {

    private val runs: ChargeRunRepository = mock()
    private val units: Units = mock()
    private val service = HeadcountCheckService(runs, units, ObjectMapper())

    private val entranceId = UUID.randomUUID()
    private val on = LocalDate.parse("2026-05-01")
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()
    private val gone = UUID.randomUUID()
    private val added = UUID.randomUUID()

    private fun billed(unitId: UUID, designation: String, occupants: Int, children: Int) =
        """{"unitId":"$unitId","designation":"$designation","occupants":$occupants,"childrenUnder6":$children,"animals":0,"absentDays":0}"""

    private fun declared(unitId: UUID, designation: String, occupants: Int, children: Int) =
        UnitForCharging(unitId, designation, "20.0000", separateEntrance = false, occupants = occupants, childrenUnder6 = children)

    private val run = ChargeRunRow(
        UUID.randomUUID(), entranceId, "2026-05", on,
        JsonbValue("""{"legalDate":"2026-05-01","units":[${billed(a, "ап. 1", 2, 0)},${billed(b, "ап. 2", 3, 1)},${billed(c, "ап. 3", 1, 0)},${billed(gone, "ап. 9", 2, 0)}]}"""),
        "hash", "1.3", "0.3.0", "COMPLETE",
    )

    @Test
    fun `PM-BOOK-012 the report lists the units whose billed persons are not the persons the book declares for the run's date`() {
        whenever(runs.findByEntranceIdAndPeriod(entranceId, "2026-05")).thenReturn(run)
        whenever(units.forEntrance(entranceId, on)).thenReturn(                         // as of the run's legal date, not today
            listOf(declared(a, "ап. 1", 2, 0), declared(b, "ап. 2", 3, 0), declared(c, "ап. 3", 2, 0), declared(added, "ап. 4", 1, 0)),
        )

        val check = service.check(entranceId, "2026-05")

        assertThat(listOf(check.period, check.legalDate, check.chargeRunId, check.unitsCompared)).containsExactly("2026-05", on, run.id, 5)
        assertThat(check.mismatches).containsExactly(                                    // ап. 1 agrees, and is not listed
            HeadcountMismatch(b, "ап. 2", HeadcountDifference.COUNT_DIFFERS, 3, 1, 3, 0),          // a child billed as one, declared as none
            HeadcountMismatch(c, "ап. 3", HeadcountDifference.COUNT_DIFFERS, 1, 0, 2, 0),          // a person declared after the run
            HeadcountMismatch(added, "ап. 4", HeadcountDifference.NOT_BILLED, null, null, 1, 0),
            HeadcountMismatch(gone, "ап. 9", HeadcountDifference.NOT_IN_BOOK, 2, 0, null, null),
        )
    }

    @Test
    fun `PM-BOOK-012 a book that still says what was billed has nothing to report, and a period with no run is not found`() {
        whenever(runs.findByEntranceIdAndPeriod(entranceId, "2026-05")).thenReturn(run)
        whenever(units.forEntrance(entranceId, on)).thenReturn(
            listOf(declared(a, "ап. 1", 2, 0), declared(b, "ап. 2", 3, 1), declared(c, "ап. 3", 1, 0), declared(gone, "ап. 9", 2, 0)),
        )
        val check = service.check(entranceId, "2026-05")
        assertThat(check.mismatches).isEmpty()
        assertThat(check.unitsCompared).isEqualTo(4)
        assertThatThrownBy { service.check(entranceId, "2026-06") }.isInstanceOf(NoSuchElementException::class.java)
    }
}

/** The endpoint with the service mocked — no database. Proves the route binds, the report serialises, and no run is a 404. */
@WebMvcTest(HeadcountCheckController::class)
class HeadcountCheckWebTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var headcount: HeadcountCheckService

    private val entranceId = UUID.randomUUID()

    @Test
    fun `GET the period's headcount report returns the units that differ, each with both counts`() {
        val unitId = UUID.randomUUID()
        whenever(headcount.check(entranceId, "2026-05")).thenReturn(
            HeadcountCheck(
                entranceId, "2026-05", LocalDate.parse("2026-05-01"), UUID.randomUUID(), 6,
                listOf(
                    HeadcountMismatch(unitId, "ап. 3", HeadcountDifference.COUNT_DIFFERS, 1, 0, 2, 0),
                    HeadcountMismatch(UUID.randomUUID(), "ап. 4", HeadcountDifference.NOT_BILLED, null, null, 1, 0),
                ),
            ),
        )
        mvc.perform(get("/api/money/entrances/$entranceId/charge-runs/2026-05/headcount"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.legalDate").value("2026-05-01"))
            .andExpect(jsonPath("$.unitsCompared").value(6))
            .andExpect(jsonPath("$.mismatches[0].unitId").value(unitId.toString()))
            .andExpect(jsonPath("$.mismatches[0].difference").value("COUNT_DIFFERS"))
            .andExpect(jsonPath("$.mismatches[0].billedOccupants").value(1))
            .andExpect(jsonPath("$.mismatches[0].declaredOccupants").value(2))
            .andExpect(jsonPath("$.mismatches[1].difference").value("NOT_BILLED"))
            .andExpect(jsonPath("$.mismatches[1].billedOccupants").doesNotExist())
    }

    @Test
    fun `a period with no run issued is a 404`() {
        whenever(headcount.check(entranceId, "2026-06")).thenThrow(NoSuchElementException("no charge run issued for 2026-06"))
        mvc.perform(get("/api/money/entrances/$entranceId/charge-runs/2026-06/headcount"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("no charge run issued for 2026-06"))
    }
}
