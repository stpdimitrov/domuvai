package zues.app.money

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.identity_org.WhoAsks
import zues.app.policy.Asking
import java.util.UUID

/**
 * Every entrance-level read of the fund asks [MoneyAccess] about the entrance in the path for the person the sign-in
 * names, before anything is read — and a refusal is a 403 naming the rule, the service untouched. Who is allowed is the
 * policy's, proved in [MoneyAccessTest] and end to end in `MoneyAccessIT`. The headcount check asks the book, through its
 * service, and answers the same way.
 */
@WebMvcTest(FundController::class, FundAccountController::class, FundHandoverController::class, LedgerController::class, HeadcountCheckController::class)
class FundReadWebTest {

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var fund: FundService
    @MockitoBean lateinit var accounts: FundAccountService
    @MockitoBean lateinit var handovers: FundHandoverService
    @MockitoBean lateinit var ledger: LedgerReads
    @MockitoBean lateinit var headcount: HeadcountCheckService
    @MockitoBean lateinit var access: MoneyAccess
    @MockitoBean lateinit var whoAsks: WhoAsks

    private val entrance = UUID.randomUUID()
    private val asking = Asking(null, UUID.randomUUID())
    private val fundReads = listOf(
        "/api/money/entrances/$entrance/fund",
        "/api/money/entrances/$entrance/fund-accounts",
        "/api/money/entrances/$entrance/fund/handover-statements",
        "/api/money/entrances/$entrance/fund/handover-statements/${UUID.randomUUID()}",
        "/api/money/entrances/$entrance/operating-account",
    )
    private val headcountRead = "/api/money/entrances/$entrance/charge-runs/2026-05/headcount"

    @Test
    fun `PM-SEC-002 each read of the fund asks about the entrance in the path, and a refusal is a 403 naming the rule — nothing read`() {
        whenever(whoAsks.of(any())).thenReturn(asking)
        whenever(whoAsks.of(null)).thenReturn(asking)
        doThrow(MoneyReadForbidden("PM-SEC-002")).whenever(access).fund(eq(entrance), eq(asking))
        for (path in fundReads) {
            mvc.perform(get(path)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-002"))
        }
        verify(access, org.mockito.kotlin.times(fundReads.size)).fund(entrance, asking)
        verifyNoInteractions(fund, accounts, handovers, ledger)
    }

    @Test
    fun `PM-BOOK-006 the headcount check is asked for the person the sign-in names, and a refusal is a 403 naming the rule`() {
        whenever(whoAsks.of(null)).thenReturn(asking)
        whenever(headcount.check(entrance, "2026-05", asking)).thenThrow(MoneyReadForbidden("PM-BOOK-006"))
        mvc.perform(get(headcountRead)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-BOOK-006"))
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("the book declares")))
        verify(access, never()).fund(any(), any())
    }
}
