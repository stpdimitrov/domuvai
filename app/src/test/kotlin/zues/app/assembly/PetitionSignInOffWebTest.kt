package zues.app.assembly

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.identity_org.Logins
import zues.app.identity_org.WhoAsks
import zues.app.policy.Held
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The petition endpoints with sign-in switched off (`DOMUVAI_AUTH=off`, as the test task runs): nobody is asking. A
 * signature and an unlock are never undone, so none is made with nobody accountable — even under a source of roles
 * that would give anybody everything; a petition is still read, as everything is in that mode.
 */
@WebMvcTest(PetitionController::class)
@Import(WhoAsks::class, Acting::class, Policy::class, PetitionSignInOffWebTest.Generous::class)
class PetitionSignInOffWebTest {

    class Generous {
        @org.springframework.context.annotation.Bean
        fun clock(): java.time.Clock = java.time.Clock.systemUTC()

        @org.springframework.context.annotation.Bean
        fun roles() = RoleSource { _, entrance, _ -> setOf(Held(Role.OWN, entrance, setOf(UUID.randomUUID())), Held(Role.BM, entrance)) }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var petitions: PetitionService
    @MockitoBean lateinit var logins: Logins

    private val entranceId = UUID.randomUUID()
    private val base = "/api/assembly/entrances/$entranceId/petitions"

    @Test
    fun `PM-SEC-001 with sign-in switched off nothing is opened, signed or convened - whatever the body says - and a petition is still read`() {
        val anyone = UUID.randomUUID()
        val id = UUID.randomUUID()
        for (request in listOf(
            post(base).content("""{"openedBy":"$anyone","subject":"Ремонт на покрива"}"""),
            post("$base/$id/signatures").content("""{"partyId":"$anyone"}"""),
            post("$base/$id/assembly").content("""{"convenedBy":"$anyone","scheduledAt":"2026-11-20T16:00:00Z","place":"фоайето","mode":"IN_PERSON","demandUnmet":"не е свикано"}"""),
        )) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
        }

        val petition = Petition(id, entranceId, anyone, "Ремонт на покрива", Instant.parse("2026-10-09T09:00:00Z"))
        whenever(petitions.read(entranceId, id)).thenReturn(
            PetitionRead(
                petition, listOf(anyone),
                PetitionWeight(LocalDate.parse("2026-10-09"), BigDecimal("8.5"), BigDecimal("20"), "чл. 12 ЗУЕС", false, "GA_PETITION_MIN_PCT@2009-01-01", emptyList(), false, setOf(anyone)),
                null,
            ),
        )
        mvc.perform(get("$base/$id")).andExpect(status().isOk).andExpect(jsonPath("$.heldPct").value("8.5"))
        verify(petitions).read(any(), any())
        verifyNoMoreInteractions(petitions)
    }
}
