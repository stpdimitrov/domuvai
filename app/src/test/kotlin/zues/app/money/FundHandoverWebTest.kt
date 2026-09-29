package zues.app.money

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/** The handover-statement endpoints with the service mocked — no database. Proves the routes bind, each field reaches the service, and the errors map. */
@WebMvcTest(FundHandoverController::class)
class FundHandoverWebTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var handovers: FundHandoverService

    private val entranceId = UUID.randomUUID()
    private val chair = UUID.randomUUID()
    private val successor = UUID.randomUUID()
    private val view = HandoverStatementView(
        UUID.randomUUID(),
        HandoverStatement(
            entranceId, UUID.randomUUID(), "BG80BNBG96611020345678", "Иван Петров", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-15"),
            chair, successor, 50_000, 11_000, 20_500, 40_500, 40_500, 0, true, 0, 40_500, emptyList(), LocalDate.parse("2026-09-29"),
        ),
        "ab12", "1.3", "0.2.0",
    )

    private fun issue(body: String) = post("/api/money/entrances/$entranceId/fund/handover-statements")
        .contentType(MediaType.APPLICATION_JSON).content(body)

    private val body = """{"handoverOn":"2026-09-15","from":"2026-09-01","outgoingPartyId":"$chair","incomingPartyId":"$successor","bankBalanceMinor":40500}"""

    @Test
    fun `POST a handover hands each field to the service and returns 201 with the statement and its hash`() {
        whenever(handovers.issue(eq(entranceId), any())).thenReturn(view)
        mvc.perform(issue(body))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.statement.closingMinor").value(40_500))
            .andExpect(jsonPath("$.statement.reconciled").value(true))
            .andExpect(jsonPath("$.basisHash").value("ab12"))
        verify(handovers).issue(entranceId, IssueHandover(LocalDate.parse("2026-09-15"), LocalDate.parse("2026-09-01"), chair, successor, 40_500))
        mvc.perform(issue(body.replace(""""from":"2026-09-01",""", ""))).andExpect(status().isCreated)
        verify(handovers).issue(entranceId, IssueHandover(LocalDate.parse("2026-09-15"), null, chair, successor, 40_500))   // from the fund's first record
    }

    @Test
    fun `GET a statement returns it as issued`() {
        whenever(handovers.find(entranceId, view.id)).thenReturn(view)
        mvc.perform(get("/api/money/entrances/$entranceId/fund/handover-statements/${view.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(view.id.toString()))
            .andExpect(jsonPath("$.statement.handoverOn").value("2026-09-15"))
    }

    @Test
    fun `a bad request is a 400, no fund or statement a 404, a table check or an unregistered party a 409`() {
        for ((error, expected) in listOf(
            IllegalArgumentException("after today") to 400, NoSuchElementException("no fund") to 404, DataIntegrityViolationException("check") to 409,
        )) {
            whenever(handovers.issue(eq(entranceId), any())).thenThrow(error)
            mvc.perform(issue(body)).andExpect(status().`is`(expected))
        }
        whenever(handovers.issue(eq(entranceId), any())).thenThrow(DataIntegrityViolationException("violates foreign key constraint \"fund_handover_statement_incoming_party_fkey\""))
        mvc.perform(issue(body))
            .andExpect(jsonPath("$.error").value("the statement conflicts with the fund's records, or a party named on it is not registered"))
        mvc.perform(issue(body.replace("2026-09-15", "15.09.2026")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("handoverOn and from are ISO dates (YYYY-MM-DD)"))
    }
}
