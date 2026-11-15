package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.policy.Asking
import zues.app.registry.Units
import java.time.LocalDate
import java.util.UUID

/** The issued periods with the runs and the access mocked — no Spring, no database (PM-BOOK-012, PM-SEC-002). */
class IssuedPeriodsTest {

    private val runs: ChargeRunHeaders = mock()
    private val units: Units = mock()
    private val access: MoneyAccess = mock()
    private val service = IssuedPeriodsService(runs, units, access)
    private val asking = Asking(null, UUID.randomUUID())
    private val entranceId = UUID.randomUUID()

    private fun stored(vararg headers: ChargeRunHeader) =
        whenever(runs.issued(entranceId)).thenReturn(headers.toList())

    @Test
    fun `PM-BOOK-012 the issued periods are served as the store orders them, each with its date and its run`() {
        val may = ChargeRunHeader(UUID.randomUUID(), "2026-05", LocalDate.parse("2026-05-01"))
        val april = ChargeRunHeader(UUID.randomUUID(), "2026-04", LocalDate.parse("2026-04-01"))
        stored(may, april)
        assertThat(service.of(entranceId, asking)).containsExactly(
            IssuedPeriod("2026-05", may.legalDate, may.id),
            IssuedPeriod("2026-04", april.legalDate, april.id),
        )
    }

    @Test
    fun `PM-BOOK-012 an entrance with no run has an empty list, and one that does not exist is not found`() {
        stored()
        whenever(units.entranceExists(entranceId)).thenReturn(true)
        assertThat(service.of(entranceId, asking)).isEmpty()
        whenever(units.entranceExists(entranceId)).thenReturn(false)
        assertThatThrownBy { service.of(entranceId, asking) }.isInstanceOfSatisfying(NoChargeRun::class.java) {
            assertThat(it.answer.code).isEqualTo(NoChargeRunCode.NO_ENTRANCE)
        }
    }

    @Test
    fun `PM-SEC-002 a reader refused the entrance's arrears is refused before anything is read`() {
        doThrow(MoneyReadForbidden("PM-SEC-002")).whenever(access).entranceArrears(entranceId, asking)
        assertThatThrownBy { service.of(entranceId, asking) }.isInstanceOf(MoneyReadForbidden::class.java)
        verifyNoInteractions(runs, units)
    }
}

/** The endpoint with the service mocked — no database. Proves the route binds and the list carries a header and nothing else. */
@WebMvcTest(IssuedPeriodsController::class)
class IssuedPeriodsWebTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var periods: IssuedPeriodsService
    @MockitoBean lateinit var whoAsks: zues.app.identity_org.WhoAsks

    private val entranceId = UUID.randomUUID()
    private val asking = Asking(null, null)

    @BeforeEach
    fun signedIn() {
        whenever(whoAsks.of(null)).thenReturn(asking)
    }

    @Test
    fun `PM-FEE-014 GET the issued periods serves each run's header, no amount and no person`() {
        val run = UUID.randomUUID()
        whenever(periods.of(entranceId, asking)).thenReturn(listOf(IssuedPeriod("2026-05", LocalDate.parse("2026-05-01"), run)))
        mvc.perform(get("/api/money/entrances/$entranceId/charge-runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].length()").value(3))
            .andExpect(jsonPath("$[0].period").value("2026-05"))
            .andExpect(jsonPath("$[0].legalDate").value("2026-05-01"))
            .andExpect(jsonPath("$[0].chargeRunId").value(run.toString()))
    }

    @Test
    fun `an entrance that does not exist is a 404 with its code`() {
        whenever(periods.of(entranceId, asking)).thenThrow(NoChargeRun.noEntrance(entranceId))
        mvc.perform(get("/api/money/entrances/$entranceId/charge-runs"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NO_ENTRANCE"))
    }
}
