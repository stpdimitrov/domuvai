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
            .andExpect(jsonPath("$.charges[0].lines[0].decisionId").value("GA-2026-1"))   // PM-FEE-003
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

    @Test
    fun `PM-FEE-017 a malformed reading is a 400 in words — never a 500, never a class name`() {
        val metered = validRequest.copy(consumption = listOf(ConsumptionLineRequest("WATER", 230, "GA-2026-9")))
        for (quantity in listOf("1E+400", "12345678901234567890", "-1", "12.3456")) {
            mvc.perform(
                post("/api/money/charge-runs/preview").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(metered.copy(readings = listOf(ReadingRequest("u1", "WATER", quantity))))),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("a reading is up to six digits")))
        }
        mvc.perform(
            post("/api/money/charge-runs/preview").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(metered.copy(readings = listOf(ReadingRequest("u1", "GAS", "1"))))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("zues."))))
    }
}
