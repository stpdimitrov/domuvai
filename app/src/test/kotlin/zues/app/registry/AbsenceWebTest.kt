package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.identity_org.Logins
import zues.app.identity_org.WhoAsks
import zues.app.policy.Policy
import java.time.Clock
import java.util.UUID

/** Sign-in switched off, as the test task runs: nobody is asking, and the entry is made as before (PM-BOOK-005). Who may enter is BookEntriesIT's. */
@WebMvcTest(RegistryController::class)
@Import(BookEntries::class, WhoAsks::class, Registrations::class)
class AbsenceWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var registry: RegistryService
    @MockitoBean lateinit var ownership: OwnershipService
    @MockitoBean lateinit var policy: Policy
    @MockitoBean lateinit var logins: Logins
    @MockitoBean lateinit var clock: Clock

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
    fun `PM-FEE-007 the day handed in reaches the book, and leaving it out still files`() {
        whenever(registry.registerAbsence(any(), any(), any())).thenReturn(listOf(UUID.randomUUID()))
        fun filing(absence: String) = mvc.perform(
            post("/api/registry/entrances/$entranceId/units/$unitId/absences")
                .contentType(MediaType.APPLICATION_JSON).content("""{"absences":[$absence]}"""),
        ).andExpect(status().isCreated)

        filing("""{"absentFrom":"2026-06-01","absentTo":"2026-08-01","handedInOn":"2026-08-20"}""")
        filing("""{"absentFrom":"2026-06-01","absentTo":"2026-08-01"}""")

        verify(registry).registerAbsence(entranceId, unitId, listOf(RegisterAbsence("2026-06-01", "2026-08-01", "2026-08-20")))
        verify(registry).registerAbsence(entranceId, unitId, listOf(RegisterAbsence("2026-06-01", "2026-08-01", null)))
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
