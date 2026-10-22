package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
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
    @MockitoBean lateinit var book: BookEntries
    @MockitoBean lateinit var who: zues.app.identity_org.WhoAsks

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
    fun `POST an entrance into an existing building passes the building through`() {
        val entranceId = UUID.randomUUID()
        val condominiumId = UUID.randomUUID()
        whenever(registry.registerEntrance(any())).thenReturn(EntranceCreated(entranceId, condominiumId))

        mvc.perform(
            post("/api/registry/entrances")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"Б","managementForm":"GA","condominiumId":"$condominiumId"}"""),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.condominiumId").value(condominiumId.toString()))
        verify(registry).registerEntrance(RegisterEntrance(null, "Б", "GA", condominiumId))
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

    @Test
    fun `PM-SEC-001 GET units asks about the entrance in the path for the person signed in, and a refusal is a 403 naming the rule — the list not read`() {
        val entrance = UUID.randomUUID()
        val asking = zues.app.policy.Asking(null, UUID.randomUUID())
        whenever(who.of(any())).thenReturn(asking)                                       // only the sign-in's person: none is null
        whenever(book.listUnits(entrance, asking)).thenThrow(BookReadForbidden("PM-SEC-001"))
        mvc.perform(get("/api/registry/entrances/$entrance/units").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.rule").value("PM-SEC-001"))
            .andExpect(jsonPath("$.error").value("an entrance's units are for those who hold a role in it"))
        verify(book).listUnits(entrance, asking)
        org.mockito.kotlin.verifyNoInteractions(registry)
    }
}
