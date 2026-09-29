package zues.app.money

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/** The fund endpoints with the service mocked — no database. Proves the routes bind and the errors map. */
@WebMvcTest(FundController::class)
class FundWebTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var fund: FundService

    private val entranceId = UUID.randomUUID()
    private val chair = UUID.randomUUID()

    private fun postDisbursement() = post("/api/money/entrances/$entranceId/fund/disbursements")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"amountMinor":20000,"purpose":"WORKS","authorisedBy":"$chair","decisionId":"GA-2026-7"}""")

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
    fun `POST a disbursement returns 201 with it committed`() {
        whenever(fund.commit(eq(entranceId), any())).thenReturn(
            DisbursementView(UUID.randomUUID(), 20_000, "WORKS", "GA-2026-7", null, null, chair, "COMMITTED", LocalDate.parse("2026-09-29")),
        )
        mvc.perform(postDisbursement())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("COMMITTED"))
            .andExpect(jsonPath("$.decisionId").value("GA-2026-7"))
    }

    @Test
    fun `a shortfall or an unsignable account is a 409, a bad request a 400, no fund a 404`() {
        for ((error, expected) in listOf(
            FundShortfall("short") to 409, IllegalStateException("no registered holder") to 409,
            IllegalArgumentException("no decision") to 400, NoSuchElementException("no fund") to 404,
        )) {
            whenever(fund.commit(eq(entranceId), any())).thenThrow(error)
            mvc.perform(postDisbursement()).andExpect(status().`is`(expected))
        }
    }
}
