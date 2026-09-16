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

@WebMvcTest(RegistryController::class)
class HouseholdWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var registry: RegistryService

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val body = RegisterHouseholdRequest(
        listOf(NewMemberRequest(isChildUnder6 = false), NewMemberRequest(isChildUnder6 = true)),
    )

    private fun postHousehold() = post("/api/registry/entrances/$entranceId/units/$unitId/household")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))

    @Test
    fun `POST household returns 201 with the member ids`() {
        whenever(registry.registerHousehold(any(), any(), any()))
            .thenReturn(listOf(UUID.randomUUID(), UUID.randomUUID()))
        mvc.perform(postHousehold())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.memberIds.length()").value(2))
    }

    @Test
    fun `household for an unknown unit is a 404`() {
        whenever(registry.registerHousehold(any(), any(), any()))
            .thenThrow(NoSuchElementException("no unit $unitId"))
        mvc.perform(postHousehold()).andExpect(status().isNotFound)
    }
}
