package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.util.UUID

@WebMvcTest(PaymentController::class)
class PaymentWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var payments: PaymentService

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val body = RecordPaymentRequest(unitId, 15_000, "2026-05-20", "OPERATING")
    private val view = PaymentView(
        UUID.randomUUID(), entranceId, unitId, 15_000, "2026-05-20", "OPERATING", PaymentAllocation.OLDEST_FIRST, null,
        listOf(AllocatedPart("2026-04-01", 10_000), AllocatedPart("2026-05-01", 5_000)), 0,
    )

    private fun postPayment(key: String? = "k-1") = post("/api/money/entrances/$entranceId/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))
        .apply { if (key != null) header("Idempotency-Key", key) }

    @Test
    fun `POST returns 201 with the rule applied and the allocation`() {
        whenever(payments.record(eq(entranceId), eq("k-1"), any())).thenReturn(view)
        mvc.perform(postPayment())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.allocationRule").value("OLDEST_FIRST"))
            .andExpect(jsonPath("$.allocation[0].debtDate").value("2026-04-01"))
            .andExpect(jsonPath("$.allocation[1].amountMinor").value(5_000))
    }

    @Test
    fun `GET returns a payment's allocation`() {
        whenever(payments.find(entranceId, view.paymentId)).thenReturn(view)
        mvc.perform(get("/api/money/entrances/$entranceId/payments/${view.paymentId}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.allocationRule").value("OLDEST_FIRST"))
    }

    @Test
    fun `a payment without an Idempotency-Key is a 400`() {
        mvc.perform(postPayment(key = null)).andExpect(status().isBadRequest)
    }

    @Test
    fun `a key reused for a different payment is a 409`() {
        whenever(payments.record(any(), any(), any())).thenThrow(IdempotencyKeyReused("reused"))
        mvc.perform(postPayment()).andExpect(status().isConflict)
    }

    @Test
    fun `a key lost in a concurrent race is a 409, not a 500`() {
        whenever(payments.record(any(), any(), any())).thenThrow(org.springframework.dao.DuplicateKeyException("uq"))
        mvc.perform(postPayment()).andExpect(status().isConflict)
    }

    @Test
    fun `a unit outside the entrance is a 404`() {
        whenever(payments.record(any(), any(), any())).thenThrow(NoSuchElementException("not in entrance"))
        mvc.perform(postPayment()).andExpect(status().isNotFound)
    }

    @Test
    fun `an unregistered account is a 400`() {
        whenever(payments.record(any(), any(), any())).thenThrow(IllegalArgumentException("no OPERATING account"))
        mvc.perform(postPayment()).andExpect(status().isBadRequest)
    }
}
