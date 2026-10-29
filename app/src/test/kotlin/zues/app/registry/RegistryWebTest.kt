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
@org.springframework.context.annotation.Import(Registrations::class)
class RegistryWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var registry: RegistryService
    @MockitoBean lateinit var book: BookEntries
    @MockitoBean lateinit var who: zues.app.identity_org.WhoAsks
    @MockitoBean lateinit var ownership: OwnershipService
    @MockitoBean lateinit var policy: zues.app.policy.Policy
    @MockitoBean lateinit var clock: java.time.Clock

    private val nobody = zues.app.policy.Asking(null, null)

    @org.junit.jupiter.api.BeforeEach
    fun unsigned() {
        whenever(who.of(org.mockito.kotlin.anyOrNull())).thenReturn(nobody)            // sign-in switched off: nobody is asking
    }

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

    @Test
    fun `PM-SEC-001 with sign-in switched off nobody is asking — every entrance is listed, an entrance registered, and the policy is not asked`() {
        val entrances = listOf(Entrance(UUID.randomUUID(), UUID.randomUUID(), "А", "GA"), Entrance(UUID.randomUUID(), UUID.randomUUID(), "Б", "GA"))
        whenever(registry.listEntrances()).thenReturn(entrances)
        whenever(registry.registerEntrance(any())).thenReturn(EntranceCreated(UUID.randomUUID(), UUID.randomUUID()))
        mvc.perform(get("/api/registry/entrances")).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(2))
        mvc.perform(post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON).content("""{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""))
            .andExpect(status().isCreated)
        org.mockito.kotlin.verifyNoInteractions(policy)
    }

    @Test
    fun `PM-SEC-001 a signed-in person the policy refuses registers no entrance — a 403 naming the rule, and nothing written`() {
        val asking = zues.app.policy.Asking(null, UUID.randomUUID())
        whenever(who.of(any())).thenReturn(asking)
        whenever(clock.instant()).thenReturn(java.time.Instant.parse("2026-10-29T10:00:00Z"))
        whenever(policy.decide(asking, zues.app.policy.Action.REGISTER_ENTRANCE, zues.app.policy.Resource.Deployment, java.time.LocalDate.of(2026, 10, 29)))
            .thenReturn(zues.app.policy.Decision(false, "PM-SEC-001", null, zues.app.policy.Policy.MATRIX_VERSION))
        mvc.perform(
            post("/api/registry/entrances").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt())
                .contentType(MediaType.APPLICATION_JSON).content("""{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.rule").value("PM-SEC-001"))
            .andExpect(jsonPath("$.error").value("an entrance is registered by the deployment's administrator"))
        verify(registry, org.mockito.kotlin.never()).registerEntrance(any())
    }

    @Test
    fun `PM-SEC-001 a signed-in person is listed only the entrances the policy allows them, as at today in Sofia`() {
        val asking = zues.app.policy.Asking(null, UUID.randomUUID())
        val (mine, theirs) = Entrance(UUID.randomUUID(), UUID.randomUUID(), "А", "GA") to Entrance(UUID.randomUUID(), UUID.randomUUID(), "Б", "GA")
        whenever(who.of(any())).thenReturn(asking)
        whenever(registry.listEntrances()).thenReturn(listOf(theirs, mine))
        whenever(clock.instant()).thenReturn(java.time.Instant.parse("2026-10-29T22:30:00Z"))      // already the 30th in Sofia
        whenever(policy.decide(any(), any(), any(), any())).thenReturn(zues.app.policy.Decision(false, "PM-SEC-001", null, zues.app.policy.Policy.MATRIX_VERSION))
        whenever(policy.decide(asking, zues.app.policy.Action.LIST_ENTRANCE, zues.app.policy.Resource.OfEntrance(mine.id), java.time.LocalDate.of(2026, 10, 30)))
            .thenReturn(zues.app.policy.Decision(true, "PM-SEC-001", zues.app.policy.Role.OWN, zues.app.policy.Policy.MATRIX_VERSION))
        val jwt = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
        mvc.perform(get("/api/registry/entrances").with(jwt))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(mine.id.toString()))
        whenever(registry.listEntrances()).thenReturn(listOf(theirs))
        mvc.perform(get("/api/registry/entrances").with(jwt)).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))   // holding none: 200 [], not a refusal
    }
}
