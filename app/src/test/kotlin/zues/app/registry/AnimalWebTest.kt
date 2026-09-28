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
import java.time.LocalDate
import java.util.UUID

@WebMvcTest(RegistryController::class)
class AnimalWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var registry: RegistryService

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val body = RegisterAnimalsRequest(
        listOf(NewAnimalRequest("cat", "VP-1"), NewAnimalRequest("dog")),
    )

    private fun postAnimals() = post("/api/registry/entrances/$entranceId/units/$unitId/animals")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))

    @Test
    fun `POST animals returns 201 with the ids`() {
        whenever(registry.registerAnimals(any(), any(), any()))
            .thenReturn(listOf(UUID.randomUUID(), UUID.randomUUID()))
        mvc.perform(postAnimals())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.animalIds.length()").value(2))
    }

    @Test
    fun `animals for an unknown unit is a 404`() {
        whenever(registry.registerAnimals(any(), any(), any()))
            .thenThrow(NoSuchElementException("no unit $unitId"))
        mvc.perform(postAnimals()).andExpect(status().isNotFound)
    }

    @Test
    fun `POST end closes the animal's stay on the declared day`() {
        val animalId = UUID.randomUUID()
        whenever(registry.endAnimalStay(entranceId, unitId, animalId, LocalDate.parse("2026-05-01"))).thenReturn(
            Animal(animalId, entranceId, unitId, "cat", "VP-1", LocalDate.parse("2026-01-10"), LocalDate.parse("2026-05-01")),
        )
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units/$unitId/animals/$animalId/end")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"on":"2026-05-01"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.validTo").value("2026-05-01"))
    }
}
