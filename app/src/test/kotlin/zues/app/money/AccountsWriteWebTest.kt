package zues.app.money

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.identity_org.WhoAsks
import zues.app.policy.Asking
import java.util.UUID

/**
 * Every write to an entrance's accounts asks [MoneyAccess] about the entrance in the path — for the global preview, the
 * entrance its body names — for the person the sign-in names, before anything is read or written, the idempotency
 * look-up included; a refusal is a 403 naming the rule. Who is allowed is the policy's, proved in [MoneyAccessTest] and
 * end to end in `MoneyAccessIT`.
 */
@WebMvcTest(
    ChargeRunController::class, StoredChargeRunController::class, ChargeRunStoreController::class, PaymentController::class,
    FundAccountController::class, FundController::class, FundHandoverController::class,
)
class AccountsWriteWebTest {

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var charges: ChargeRunService
    @MockitoBean lateinit var store: ChargeRunStore
    @MockitoBean lateinit var payments: PaymentService
    @MockitoBean lateinit var accounts: FundAccountService
    @MockitoBean lateinit var fund: FundService
    @MockitoBean lateinit var handovers: FundHandoverService
    @MockitoBean lateinit var access: MoneyAccess
    @MockitoBean lateinit var whoAsks: WhoAsks

    private val entrance = UUID.randomUUID()
    private val asking = Asking(null, UUID.randomUUID())
    private val disbursement = UUID.randomUUID()
    private val party = UUID.randomUUID()
    private val run = """{"period":"2026-05","legalDate":"2026-05-01","lines":[]}"""
    private val writes = mapOf(                                      // each with a body that binds: a malformed one is a 400 before anything is asked
        "/api/money/entrances/$entrance/charge-runs/preview" to run,
        "/api/money/entrances/$entrance/charge-runs" to run,
        "/api/money/entrances/$entrance/payments" to """{"unitId":"${UUID.randomUUID()}","amountMinor":1000,"valueDate":"2026-05-20","receivedInto":"CASH"}""",
        "/api/money/entrances/$entrance/fund-accounts" to """{"iban":"BG80BNBG96611020345678","purpose":"REPAIR_RENEWAL","holderName":"Мария Иванова","holderKind":"MANAGER"}""",
        "/api/money/entrances/$entrance/fund/disbursements" to """{"amountMinor":100,"purpose":"WORKS","decisionId":"GA-1"}""",
        "/api/money/entrances/$entrance/fund/disbursements/$disbursement/pay" to """{"paidOn":"2026-05-01"}""",
        "/api/money/entrances/$entrance/fund/disbursements/$disbursement/cancel" to """{"reason":"withdrawn"}""",
        "/api/money/entrances/$entrance/fund/handover-statements" to """{"handoverOn":"2026-05-01","outgoingPartyId":"$party","incomingPartyId":"${UUID.randomUUID()}","bankBalanceMinor":0}""",
    )

    private val SIGNED = 4                                           // a sign-off, a payout, a cancellation and a handover also ask who acts

    private fun write(path: String, body: String = "{}") =
        post(path).header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `PM-SEC-001 each write asks about the entrance in the path, and a refusal is a 403 naming the rule — nothing read or written`() {
        whenever(whoAsks.of(null)).thenReturn(asking)
        doThrow(MoneyWriteForbidden("PM-SEC-001")).whenever(access).keep(eq(entrance), eq(asking))
        doThrow(MoneyWriteForbidden("PM-SEC-001")).whenever(access).keeper(eq(entrance), eq(asking))
        for ((path, body) in writes) {
            mvc.perform(write(path, body)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        }
        verify(access, times(writes.size - SIGNED)).keep(entrance, asking)
        verify(access, times(SIGNED)).keeper(entrance, asking)
        verifyNoInteractions(charges, store, payments, accounts, fund, handovers)
    }

    @Test
    fun `PM-SEC-001 the global preview asks about the entrance its body names, and a refusal is a 403`() {
        whenever(whoAsks.of(null)).thenReturn(asking)
        doThrow(MoneyWriteForbidden("PM-SEC-001")).whenever(access).keep(eq(entrance.toString()), eq(asking))
        val body = """{"entranceId":"$entrance","period":"2026-05","legalDate":"2026-05-01","lines":[],"units":[]}"""
        mvc.perform(write("/api/money/charge-runs/preview", body)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        verify(access).keep(entrance.toString(), asking)
        verify(access, times(0)).keep(any<UUID>(), any())
    }
}
