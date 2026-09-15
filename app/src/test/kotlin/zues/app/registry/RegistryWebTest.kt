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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * The HTTP contract, with the service mocked — proves routing, status codes and JSON
 * shape without a database, so it runs in the gate pack with or without Docker.
 */
@WebMvcTest(RegistryController::class)
class RegistryWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var registry: RegistryService

    @Test
    fun `POST an entrance returns 201 with the created ids`() {
        val entranceId = UUID.randomUUID()
        val condominiumId = UUID.randomUUID()
        whenever(registry.registerEntrance(any())).thenReturn(EntranceCreated(entranceId, condominiumId))

        mvc.perform(
            post("/api/registry/entrances")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(RegisterEntranceRequest("ул. Раковски 1", "А", "GA"))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.entranceId").value(entranceId.toString()))
            .andExpect(jsonPath("$.condominiumId").value(condominiumId.toString()))
    }

    @Test
    fun `GET returns the entrance list`() {
        val entrance = Entrance(UUID.randomUUID(), UUID.randomUUID(), "А", "GA")
        whenever(registry.listEntrances()).thenReturn(listOf(entrance))

        mvc.perform(get("/api/registry/entrances"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].label").value("А"))
            .andExpect(jsonPath("$[0].managementForm").value("GA"))
    }
}
