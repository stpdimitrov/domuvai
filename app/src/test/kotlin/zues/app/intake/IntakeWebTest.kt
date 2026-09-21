package zues.app.intake

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(IntakeController::class)
class IntakeWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    private val entranceId = UUID.randomUUID()
    private val csv = "designation,ideal_parts,occupants,fee_minor\nап. 1,60.0000,2,6000\nап. 2,40.0000,1,4000"

    private fun dryRun(request: FeeSheetDryRunRequest) =
        post("/api/intake/entrances/$entranceId/fee-sheet/dry-run")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request))

    @Test
    fun `POST dry-run reproduces the sheet and reports it`() {
        val request = FeeSheetDryRunRequest(
            period = "2026-05", legalDate = "2026-05-01",
            lines = listOf(TariffInput("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
            csv = csv,
        )
        mvc.perform(dryRun(request))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reproduced").value(true))
            .andExpect(jsonPath("$.matched").value(2))
    }

    @Test
    fun `an unknown cost stream is a 400`() {
        val request = FeeSheetDryRunRequest(
            period = "2026-05", legalDate = "2026-05-01",
            lines = listOf(TariffInput("NONSENSE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
            csv = csv,
        )
        mvc.perform(dryRun(request)).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST profile proposes a mapping and names what it cannot place`() {
        // a Bulgarian header with one column the profiler cannot recognise
        val request = ProfileRequest(csv = "Обект,Идеални части,живущи,Сума,телефон\nап. 1,60.0000,2,6000,0888")
        mvc.perform(
            post("/api/intake/entrances/$entranceId/fee-sheet/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mapping.Обект").value("DESIGNATION"))
            .andExpect(jsonPath("$.mapping.Сума").value("FEE_MINOR"))
            .andExpect(jsonPath("$.unmappedColumns[0]").value("телефон"))
            .andExpect(jsonPath("$.missingRequired").isEmpty)
    }

    @Test
    fun `a confirmed mapping reproduces a sheet the profiler cannot recognise`() {
        val request = FeeSheetDryRunRequest(
            period = "2026-05", legalDate = "2026-05-01",
            lines = listOf(TariffInput("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
            csv = "col_a,col_b,col_c,col_d\nап. 1,60.0000,2,6000\nап. 2,40.0000,1,4000",
            mapping = mapOf(
                "col_a" to IntakeField.DESIGNATION, "col_b" to IntakeField.IDEAL_PARTS,
                "col_c" to IntakeField.OCCUPANTS, "col_d" to IntakeField.FEE_MINOR,
            ),
        )
        mvc.perform(dryRun(request))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reproduced").value(true))
            .andExpect(jsonPath("$.matched").value(2))
    }
}
