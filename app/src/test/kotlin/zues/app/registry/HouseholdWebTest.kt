package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.kotlin.any
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import zues.app.identity_org.Logins
import zues.app.identity_org.WhoAsks
import zues.app.policy.Policy
import java.time.Clock
import java.util.UUID

/** Sign-in switched off, as the test task runs: nobody is asking, and the entry is made as before (PM-BOOK-005). Who may enter is BookEntriesIT's. */
@WebMvcTest(RegistryController::class)
@Import(BookEntries::class, WhoAsks::class, Registrations::class)
class HouseholdWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var registry: RegistryService
    @MockitoBean lateinit var ownership: OwnershipService
    @MockitoBean lateinit var policy: Policy
    @MockitoBean lateinit var logins: Logins
    @MockitoBean lateinit var clock: Clock

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

    private fun endStay(memberId: UUID, on: String) =
        post("/api/registry/entrances/$entranceId/units/$unitId/household/$memberId/end")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"on":"$on"}""")

    @Test
    fun `POST end closes the resident's stay on the declared day`() {
        val memberId = UUID.randomUUID()
        whenever(registry.endHouseholdStay(entranceId, unitId, memberId, LocalDate.parse("2026-05-01"))).thenReturn(
            HouseholdMember(memberId, entranceId, unitId, null, false, LocalDate.parse("2026-01-10"), LocalDate.parse("2026-05-01")),
        )
        mvc.perform(endStay(memberId, "2026-05-01"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.validFrom").value("2026-01-10"))
            .andExpect(jsonPath("$.validTo").value("2026-05-01"))
    }

    @Test
    fun `a move-out not after the move-in, or a malformed date, is a 400`() {
        whenever(registry.endHouseholdStay(any(), any(), any(), any())).thenThrow(IllegalArgumentException("move-out before move-in"))
        mvc.perform(endStay(UUID.randomUUID(), "2026-05-01")).andExpect(status().isBadRequest)
        mvc.perform(endStay(UUID.randomUUID(), "01.05.2026")).andExpect(status().isBadRequest)
    }

    @Test
    fun `PM-BOOK-005 with sign-in switched off nobody is asking — the entry is made and the policy is not asked`() {
        whenever(registry.registerHousehold(any(), any(), any())).thenReturn(listOf(UUID.randomUUID()))
        mvc.perform(postHousehold()).andExpect(status().isCreated)
        verifyNoInteractions(policy)
    }
}
