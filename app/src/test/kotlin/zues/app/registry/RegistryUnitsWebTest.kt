package zues.app.registry

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

/**
 * The HTTP edge for units, with the service mocked — proves routing and how the two
 * failure modes surface (invalid parts → 400, unknown entrance → 404) without a database.
 */
@WebMvcTest(RegistryController::class)
class RegistryUnitsWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var registry: RegistryService

    private val entranceId = UUID.randomUUID()
    private val body = RegisterUnitsRequest(
        listOf(NewUnitRequest("ап. 1", "FLAT", null, "100.0000", false)),
    )

    private fun postUnits() = post("/api/registry/entrances/$entranceId/units")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))

    @Test
    fun `POST units returns 201 with the created ids`() {
        val id = UUID.randomUUID()
        whenever(registry.registerUnits(any(), any())).thenReturn(listOf(id))

        mvc.perform(postUnits())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.unitIds[0]").value(id.toString()))
    }

    @Test
    fun `ideal parts that do not sum to 100 percent are a 400`() {
        whenever(registry.registerUnits(any(), any()))
            .thenThrow(IllegalStateException("ideal parts sum to 99.980000%, must be 100.000000% (PM-ORG-002)"))

        mvc.perform(postUnits()).andExpect(status().isBadRequest)
    }

    @Test
    fun `units for an unknown entrance are a 404`() {
        whenever(registry.registerUnits(any(), any()))
            .thenThrow(NoSuchElementException("no entrance $entranceId"))

        mvc.perform(postUnits()).andExpect(status().isNotFound)
    }
}
