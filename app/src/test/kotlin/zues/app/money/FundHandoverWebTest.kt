package zues.app.money

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
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
import java.time.Instant
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
            chair, successor, 43_000, 10_700, 20_500, 33_200, 33_000, -200, false, 10_800, 22_400, emptyList(),
            LocalDate.parse("2026-09-29"), Instant.parse("2026-09-28T21:30:00Z"),
        ),
        "{\"availableMinor\":22400}", "ab12", "1.3", "0.2.0",
    )

    private fun issue(body: String) = post("/api/money/entrances/$entranceId/fund/handover-statements")
        .header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON).content(body)

    private val body = """{"handoverOn":"2026-09-15","from":"2026-09-01","outgoingPartyId":"$chair","incomingPartyId":"$successor","bankBalanceMinor":33000}"""

    @Test
    fun `POST a handover hands each field to the service and returns 201 with the statement, its basis and hash`() {
        whenever(handovers.issue(eq(entranceId), any(), any())).thenReturn(view)
        mvc.perform(issue(body))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.statement.closingMinor").value(33_200))
            .andExpect(jsonPath("$.statement.reconciled").value(false))
            .andExpect(jsonPath("$.statement.issuedAt").value("2026-09-28T21:30:00Z"))
            .andExpect(jsonPath("$.basis").value("{\"availableMinor\":22400}"))
            .andExpect(jsonPath("$.basisHash").value("ab12"))
        verify(handovers).issue(entranceId, "k-1", IssueHandover(LocalDate.parse("2026-09-15"), LocalDate.parse("2026-09-01"), chair, successor, 33_000))
        mvc.perform(issue(body.replace(""""from":"2026-09-01",""", ""))).andExpect(status().isCreated)
        verify(handovers).issue(entranceId, "k-1", IssueHandover(LocalDate.parse("2026-09-15"), null, chair, successor, 33_000))   // from the fund's first record
    }

    @Test
    fun `a handover without the bank's balance is a 400 — never a balance of zero nobody entered`() {
        mvc.perform(issue(body.replace(""","bankBalanceMinor":33000""", "")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("bankBalanceMinor is the balance on the bank's own statement for the handover date — required"))
        verifyNoInteractions(handovers)
    }

    @Test
    fun `a handover without an Idempotency-Key is a 400, and a key reused for a different request a 409`() {       // DEVBRIEF §8 (#58)
        mvc.perform(post("/api/money/entrances/$entranceId/fund/handover-statements").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
        org.mockito.Mockito.verifyNoInteractions(handovers)
        whenever(handovers.issue(eq(entranceId), any(), any())).thenThrow(IdempotencyKeyReused("reused"))
        mvc.perform(issue(body))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("Idempotency-Key was already used for a different request"))
    }

    @Test
    fun `GET one statement, or every statement newest first, as issued`() {
        whenever(handovers.find(entranceId, view.id)).thenReturn(view)
        whenever(handovers.list(entranceId)).thenReturn(listOf(view))
        mvc.perform(get("/api/money/entrances/$entranceId/fund/handover-statements/${view.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(view.id.toString()))
            .andExpect(jsonPath("$.statement.handoverOn").value("2026-09-15"))
        mvc.perform(get("/api/money/entrances/$entranceId/fund/handover-statements"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(view.id.toString()))
    }

    @Test
    fun `a bad request is a 400, no fund or statement a 404, a table check or an unregistered party a 409`() {
        for ((error, expected) in listOf(
            IllegalArgumentException("after today") to 400, NoSuchElementException("no fund") to 404, DataIntegrityViolationException("check") to 409,
        )) {
            whenever(handovers.issue(eq(entranceId), any(), any())).thenThrow(error)
            mvc.perform(issue(body)).andExpect(status().`is`(expected))
        }
        whenever(handovers.issue(eq(entranceId), any(), any())).thenThrow(DataIntegrityViolationException("violates foreign key constraint \"fund_handover_statement_incoming_party_fkey\""))
        mvc.perform(issue(body))
            .andExpect(jsonPath("$.error").value("the statement conflicts with the fund's records, or a party named on it is not registered"))
        mvc.perform(issue(body.replace("2026-09-15", "15.09.2026")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("handoverOn and from are ISO dates (YYYY-MM-DD)"))
    }
}
