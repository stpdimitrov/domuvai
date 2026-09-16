package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(RegistryController::class)
class AbsenceWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var registry: RegistryService

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val body = RegisterAbsencesRequest(listOf(NewAbsenceRequest("2026-06-01", "2026-08-01")))

    private fun postAbsences() = post("/api/registry/entrances/$entranceId/units/$unitId/absences")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))

    @Test
    fun `POST absences returns 201 with the ids`() {
        whenever(registry.registerAbsence(any(), any(), any())).thenReturn(listOf(UUID.randomUUID()))
        mvc.perform(postAbsences())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.absenceIds.length()").value(1))
    }

    @Test
    fun `absences for an unknown unit is a 404`() {
        whenever(registry.registerAbsence(any(), any(), any()))
            .thenThrow(NoSuchElementException("no unit $unitId"))
        mvc.perform(postAbsences()).andExpect(status().isNotFound)
    }

    @Test
    fun `an inverted span is a 400`() {
        whenever(registry.registerAbsence(any(), any(), any()))
            .thenThrow(IllegalArgumentException("absentTo must be after absentFrom"))
        mvc.perform(postAbsences()).andExpect(status().isBadRequest)
    }
}
