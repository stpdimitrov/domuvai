package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The HTTP contract of the calculator. The controller calls the pure calculator directly,
 * so no bean is mocked and no database is needed — it runs in the gate pack everywhere.
 */
@WebMvcTest(ChargeRunController::class)
class ChargeRunWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    private val validRequest = ChargeRunRequest(
        entranceId = "entrance-1", period = "2026-05", legalDate = "2026-05-01",
        lines = listOf(TariffLineRequest("MANAGEMENT", "PER_PERSON", decisionId = "GA-2026-1", rateMinor = 500)),
        units = listOf(
            UnitRequest("u1", "ап. 1", idealParts = "60.0000", occupants = 2),
            UnitRequest("u2", "ап. 2", idealParts = "40.0000", occupants = 1),
        ),
    )

    @Test
    fun `POST preview computes the run`() {
        mvc.perform(
            post("/api/money/charge-runs/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(validRequest)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(1500))
            .andExpect(jsonPath("$.charges[0].lines[0].stream").value("MANAGEMENT"))
    }

    @Test
    fun `a tariff line without a GA decision is rejected as 400`() {
        val bad = validRequest.copy(
            lines = listOf(TariffLineRequest("MANAGEMENT", "PER_PERSON", decisionId = "", rateMinor = 500)),
        )
        mvc.perform(
            post("/api/money/charge-runs/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(bad)),
        )
            .andExpect(status().isBadRequest)
    }
}
