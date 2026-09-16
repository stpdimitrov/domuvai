package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(ChargeRunStoreController::class)
class ChargeRunStoreWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var store: ChargeRunStore

    private val entranceId = UUID.randomUUID()
    private val body = StoredChargeRunRequest(
        period = "2026-05", legalDate = "2026-05-01",
        lines = listOf(TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
    )

    private fun postRun() = post("/api/money/entrances/$entranceId/charge-runs")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))

    @Test
    fun `POST issues a run and returns 201 with the line count`() {
        whenever(store.issue(any(), any()))
            .thenReturn(ChargeRunIssued(UUID.randomUUID(), entranceId, "2026-05", 10_000, 2))
        mvc.perform(postRun())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.lineCount").value(2))
            .andExpect(jsonPath("$.totalMinor").value(10_000))
    }

    @Test
    fun `a second run for the same period is a 409`() {
        whenever(store.issue(any(), any())).thenThrow(ChargeRunAlreadyIssued("already issued"))
        mvc.perform(postRun()).andExpect(status().isConflict)
    }

    @Test
    fun `an entrance with no units is a 404`() {
        whenever(store.issue(any(), any())).thenThrow(NoSuchElementException("no units"))
        mvc.perform(postRun()).andExpect(status().isNotFound)
    }
}
