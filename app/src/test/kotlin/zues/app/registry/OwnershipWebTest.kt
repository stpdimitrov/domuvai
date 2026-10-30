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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.util.UUID

@WebMvcTest(OwnershipController::class)
@org.springframework.context.annotation.Import(zues.app.identity_org.WhoAsks::class, BookEntries::class, Registrations::class)
class OwnershipWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var ownership: OwnershipService
    @MockitoBean lateinit var lists: BookLists
    @MockitoBean lateinit var logins: zues.app.identity_org.Logins
    @MockitoBean lateinit var registry: RegistryService
    @MockitoBean lateinit var policy: zues.app.policy.Policy
    @MockitoBean lateinit var clock: java.time.Clock

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()

    @Test
    fun `POST parties returns 201 with the id`() {
        whenever(ownership.registerParty(any())).thenReturn(partyId)
        mvc.perform(
            post("/api/registry/parties").contentType(MediaType.APPLICATION_JSON)
                .content("""{"fullName":"Иван Петров","idType":"EGN","idValue":"7501010010"}"""),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.partyId").value(partyId.toString()))
    }

    @Test
    fun `POST title returns 201 with the id`() {
        whenever(ownership.assignTitle(any(), any(), any())).thenReturn(UUID.randomUUID())
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units/$unitId/titles").contentType(MediaType.APPLICATION_JSON)
                .content("""{"partyId":"$partyId","titleRole":"OWN","share":"1"}"""),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.titleId").exists())
    }

    @Test
    fun `a share out of range is a 400`() {
        whenever(ownership.assignTitle(any(), any(), any())).thenThrow(IllegalArgumentException("share must be within (0, 1]"))
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units/$unitId/titles").contentType(MediaType.APPLICATION_JSON)
                .content("""{"partyId":"$partyId","titleRole":"OWN","share":"2"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `a title for an unknown party is a 404`() {
        whenever(ownership.assignTitle(any(), any(), any())).thenThrow(NoSuchElementException("no party $partyId"))
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units/$unitId/titles").contentType(MediaType.APPLICATION_JSON)
                .content("""{"partyId":"$partyId","titleRole":"OWN","share":"1"}"""),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `PM-BOOK-011 the owners response carries the party's id beside the name but no id number`() {
        whenever(lists.owners(any(), any(), any()))
            .thenReturn(BookAnswer.Served(listOf(OwnerView(unitId, partyId, "Иван Петров", "OWN", BigDecimal("1.000000")))))
        val body = mvc.perform(get("/api/registry/entrances/$entranceId/owners").param("on", "2026-05-01"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].partyId").value(partyId.toString()))
            .andExpect(jsonPath("$[0].partyName").value("Иван Петров"))
            .andExpect(jsonPath("$[0].idValue").doesNotExist())
            .andReturn().response.contentAsString
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("idValue").doesNotContain("idType")
    }

    @Test
    fun `PM-SEC-001 with sign-in switched off nobody is asking — a party is registered, and the policy is not asked`() {
        whenever(ownership.registerParty(any())).thenReturn(partyId)
        mvc.perform(post("/api/registry/parties").contentType(MediaType.APPLICATION_JSON).content("""{"fullName":"Иван Петров"}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.partyId").value(partyId.toString()))
        org.mockito.kotlin.verify(ownership).registerParty(RegisterParty("Иван Петров"))
        org.mockito.kotlin.verifyNoInteractions(policy, registry)
    }

    @Test
    fun `PM-SEC-001 a signed-in person the policy refuses registers no party — a 403 naming the rule, and nothing written`() {
        val entrances = listOf(Entrance(UUID.randomUUID(), UUID.randomUUID(), "А", "GA"), Entrance(UUID.randomUUID(), UUID.randomUUID(), "Б", "GA"))
        whenever(logins.partyOf("https://id.example.test/realms/domuvai", "owner-1")).thenReturn(partyId)
        whenever(registry.listEntrances()).thenReturn(entrances)
        whenever(clock.instant()).thenReturn(java.time.Instant.parse("2026-10-29T10:00:00Z"))
        val asking = zues.app.policy.Asking(zues.app.policy.Login("https://id.example.test/realms/domuvai", "owner-1"), partyId)
        whenever(policy.decideInAny(asking, zues.app.policy.Action.REGISTER_PARTY, entrances.map { it.id }, java.time.LocalDate.of(2026, 10, 29)))
            .thenReturn(zues.app.policy.Decision(false, "PM-SEC-001", null, zues.app.policy.Policy.MATRIX_VERSION))
        mvc.perform(
            post("/api/registry/parties")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt { it.subject("owner-1").issuer("https://id.example.test/realms/domuvai") })
                .contentType(MediaType.APPLICATION_JSON).content("""{"fullName":"Иван Петров"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.rule").value("PM-SEC-001"))
            .andExpect(jsonPath("$.error").value("a party is registered by the deployment's administrator, or by the manager or the board of an entrance"))
        org.mockito.kotlin.verify(ownership, org.mockito.kotlin.never()).registerParty(any())
    }
}
