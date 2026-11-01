package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
import java.util.UUID

@WebMvcTest(FundAccountController::class)
class FundAccountWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var fund: FundAccountService
    @MockitoBean lateinit var access: MoneyAccess
    @MockitoBean lateinit var whoAsks: zues.app.identity_org.WhoAsks

    private val entranceId = UUID.randomUUID()
    private val body = RegisterFundAccountRequest("BG80BNBG96611020345678", "REPAIR_RENEWAL", "Иван Петров", "MANAGER")

    private fun postFund() = post("/api/money/entrances/$entranceId/fund-accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))

    @Test
    fun `POST fund-accounts returns 201 with the account`() {
        whenever(fund.register(any(), any()))
            .thenReturn(FundAccountRegistered(UUID.randomUUID(), entranceId, "BG80BNBG96611020345678", "REPAIR_RENEWAL"))
        mvc.perform(postFund())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.purpose").value("REPAIR_RENEWAL"))
            .andExpect(jsonPath("$.iban").value("BG80BNBG96611020345678"))
    }

    @Test
    fun `PM-SEC-002 an account is read without its holder's party id unless the reader may see the parties, its holder's name and IBAN alike`() {
        val holder = UUID.randomUUID()
        val mandate = UUID.randomUUID()
        whenever(fund.list(entranceId)).thenReturn(listOf(FundAccountRow(UUID.randomUUID(), entranceId, "BG80BNBG96611020345678", "REPAIR_RENEWAL", "Иван Петров", "MANAGER", holder, mandate)))
        mvc.perform(get("/api/money/entrances/$entranceId/fund-accounts"))
            .andExpect(jsonPath("$[0].holderParty").doesNotExist())
            .andExpect(jsonPath("$[0].mandateId").doesNotExist())
            .andExpect(jsonPath("$[0].holderName").value("Иван Петров"))
            .andExpect(jsonPath("$[0].iban").value("BG80BNBG96611020345678"))
        whenever(access.seesFundParties(eq(entranceId), anyOrNull())).thenReturn(true)
        mvc.perform(get("/api/money/entrances/$entranceId/fund-accounts"))
            .andExpect(jsonPath("$[0].holderParty").value(holder.toString()))
            .andExpect(jsonPath("$[0].mandateId").value(mandate.toString()))
    }

    @Test
    fun `PM-FUND-004 a fund's manager with no mandate is a 400 in the service's words`() {
        whenever(fund.register(any(), any())).thenThrow(IllegalArgumentException("no standing mandate (PM-FUND-004)"))
        mvc.perform(postFund())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("no standing mandate (PM-FUND-004)"))
    }

    @Test
    fun `PM-FUND-005 a duplicate account is a 409`() {
        whenever(fund.register(any(), any())).thenThrow(FundAccountConflict("already registered"))
        mvc.perform(postFund()).andExpect(status().isConflict)
    }

    @Test
    fun `a malformed IBAN is a 400`() {
        whenever(fund.register(any(), any())).thenThrow(IllegalArgumentException("not a well-formed IBAN"))
        mvc.perform(postFund()).andExpect(status().isBadRequest)
    }
}
