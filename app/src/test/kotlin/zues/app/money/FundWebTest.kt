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

/** The fund endpoints with the service mocked — no database. Proves the routes bind, each field reaches the service, and the errors map. */
@WebMvcTest(FundController::class)
class FundWebTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var fund: FundService

    private val entranceId = UUID.randomUUID()
    private val chair = UUID.randomUUID()

    private fun postDisbursement(body: String = """{"amountMinor":20000,"purpose":"WORKS","authorisedBy":"$chair","decisionId":"GA-2026-7"}""") =
        post("/api/money/entrances/$entranceId/fund/disbursements").contentType(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `GET the fund shows its balance, what is committed and what is available`() {
        whenever(fund.view(entranceId)).thenReturn(FundView(entranceId, "BG80BNBG96611020345678", "Иван Петров", 50_000, 20_000, 30_000, emptyList()))
        mvc.perform(get("/api/money/entrances/$entranceId/fund"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanceMinor").value(50_000))
            .andExpect(jsonPath("$.committedMinor").value(20_000))
            .andExpect(jsonPath("$.availableMinor").value(30_000))
    }

    @Test
    fun `POST a disbursement hands each field to the service and returns 201 with it committed`() {
        whenever(fund.commit(eq(entranceId), any())).thenReturn(
            DisbursementView(UUID.randomUUID(), 20_000, "WORKS", "GA-2026-7", null, null, chair, "COMMITTED", LocalDate.parse("2026-09-29")),
        )
        mvc.perform(
            postDisbursement(
                """{"amountMinor":20000,"purpose":"PASSPORT_MEASURE","authorisedBy":"$chair",""" +
                    """"decisionId":"D","emergencyJustification":"E","passportMeasure":"M"}""",
            ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("COMMITTED"))
            .andExpect(jsonPath("$.decisionId").value("GA-2026-7"))
        verify(fund).commit(entranceId, CommitDisbursement(20_000, "PASSPORT_MEASURE", chair, "D", "E", "M"))
    }

    @Test
    fun `a shortfall, an unsignable account or a table check is a 409, a bad request a 400, no fund a 404`() {
        for ((error, expected) in listOf(
            FundShortfall("short") to 409, FundUnsignable("no registered holder") to 409, DataIntegrityViolationException("check") to 409,
            IllegalArgumentException("no decision") to 400, NoSuchElementException("no fund") to 404,
        )) {
            whenever(fund.commit(eq(entranceId), any())).thenThrow(error)
            mvc.perform(postDisbursement()).andExpect(status().`is`(expected))
        }
        whenever(fund.commit(eq(entranceId), any())).thenThrow(DataIntegrityViolationException("violates check constraint \"fund_disbursement_x\""))
        mvc.perform(postDisbursement())
            .andExpect(jsonPath("$.error").value("the disbursement conflicts with the fund's records"))   // the database's text stays inside
    }

    private val disbursementId = UUID.randomUUID()

    private fun act(verb: String, body: String) = post("/api/money/entrances/$entranceId/fund/disbursements/$disbursementId/$verb")
        .contentType(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `POST pay and cancel hand each field to the service and return the disbursement`() {
        val paid = DisbursementView(
            disbursementId, 20_000, "WORKS", "GA-2026-7", null, null, chair, "PAID", LocalDate.parse("2026-09-01"), paidOn = LocalDate.parse("2026-09-15"),
        )
        whenever(fund.pay(eq(entranceId), eq(disbursementId), any())).thenReturn(paid)
        whenever(fund.cancel(eq(entranceId), eq(disbursementId), any())).thenReturn(
            paid.copy(status = "CANCELLED", paidOn = null, cancelledOn = LocalDate.parse("2026-09-29"), cancelledBy = chair, cancelReason = "revoked"),
        )
        mvc.perform(act("pay", """{"paidOn":"2026-09-15","paidBy":"$chair"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.paidOn").value("2026-09-15"))
        verify(fund).pay(entranceId, disbursementId, PayDisbursement(LocalDate.parse("2026-09-15"), chair))
        mvc.perform(act("cancel", """{"cancelledBy":"$chair","reason":"the GA revoked decision GA-2026-7"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelReason").value("revoked"))
        verify(fund).cancel(entranceId, disbursementId, CancelDisbursement(chair, "the GA revoked decision GA-2026-7"))
    }

    @Test
    fun `a closed disbursement is a 409, an unknown one a 404, a bad date or signatory a 400`() {
        for ((error, expected) in listOf(
            DisbursementClosed("already paid") to 409, NoSuchElementException("no such disbursement") to 404,
            IllegalArgumentException("after today") to 400,
        )) {
            whenever(fund.pay(eq(entranceId), eq(disbursementId), any())).thenThrow(error)
            mvc.perform(act("pay", """{"paidOn":"2026-09-15","paidBy":"$chair"}""")).andExpect(status().`is`(expected))
            whenever(fund.cancel(eq(entranceId), eq(disbursementId), any())).thenThrow(error)
            mvc.perform(act("cancel", """{"cancelledBy":"$chair","reason":"revoked"}""")).andExpect(status().`is`(expected))
        }
        mvc.perform(act("pay", """{"paidOn":"15.09.2026","paidBy":"$chair"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("paidOn must be an ISO date (YYYY-MM-DD)"))
    }

    @Test
    fun `a malformed id is a 400 naming the value, not the framework's classes`() {
        mvc.perform(get("/api/money/entrances/x/fund"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Invalid UUID string: x"))
    }
}
