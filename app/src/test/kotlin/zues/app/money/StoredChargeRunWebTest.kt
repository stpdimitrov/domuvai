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

@WebMvcTest(StoredChargeRunController::class)
class StoredChargeRunWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var service: ChargeRunService

    private val entranceId = UUID.randomUUID()
    private val body = StoredChargeRunRequest(
        period = "2026-05", legalDate = "2026-05-01",
        lines = listOf(TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
    )

    private fun postPreview() = post("/api/money/entrances/$entranceId/charge-runs/preview")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))

    @Test
    fun `POST preview returns the computed run`() {
        whenever(service.preview(any(), any())).thenReturn(
            ChargeRunResponse(entranceId.toString(), "2026-05", "2026-05-01", "1.3", "0.1.0", 10_000, emptyList()),
        )
        mvc.perform(postPreview())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(10_000))
    }

    @Test
    fun `an entrance with no units is a 404`() {
        whenever(service.preview(any(), any())).thenThrow(NoSuchElementException("no units"))
        mvc.perform(postPreview()).andExpect(status().isNotFound)
    }
}
