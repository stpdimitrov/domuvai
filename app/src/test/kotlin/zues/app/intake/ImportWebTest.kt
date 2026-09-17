package zues.app.intake

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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

@WebMvcTest(ImportController::class)
class ImportWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var imports: ImportService

    private val entranceId = UUID.randomUUID()
    private val importId = UUID.randomUUID()
    private val request = FeeSheetDryRunRequest(
        period = "2026-05", legalDate = "2026-05-01",
        lines = listOf(TariffInput("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
        csv = "designation,ideal_parts,occupants,fee_minor\nап. 1,60.0000,2,6000\nап. 2,40.0000,1,4000",
    )

    @Test
    fun `POST imports returns 201 with the id and report`() {
        whenever(imports.record(any(), any()))
            .thenReturn(ImportResult(importId, DryRunReport(2, 2, 0, emptyList(), emptyList(), true)))
        mvc.perform(
            post("/api/intake/entrances/$entranceId/imports").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.importId").value(importId.toString()))
            .andExpect(jsonPath("$.report.reproduced").value(true))
    }

    @Test
    fun `GET import returns the stored verdict`() {
        whenever(imports.find(importId)).thenReturn(ImportRow(importId, entranceId, "REPRODUCED", "abc123", 2, 0, 0))
        mvc.perform(get("/api/intake/imports/$importId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REPRODUCED"))
            .andExpect(jsonPath("$.sourceSha").value("abc123"))
    }

    @Test
    fun `GET a missing import is a 404`() {
        whenever(imports.find(any())).thenThrow(NoSuchElementException("no import"))
        mvc.perform(get("/api/intake/imports/${UUID.randomUUID()}")).andExpect(status().isNotFound)
    }
}
