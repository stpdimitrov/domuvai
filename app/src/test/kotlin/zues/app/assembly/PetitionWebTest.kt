package zues.app.assembly

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@WebMvcTest(PetitionController::class)
class PetitionWebTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var petitions: PetitionService

    private val entranceId = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val at = Instant.parse("2026-10-09T09:00:00Z")
    private val petition = Petition(UUID.randomUUID(), entranceId, owner, "Ремонт на покрива", at)
    private val base = "/api/assembly/entrances/$entranceId/petitions"

    private fun weighed(held: String, cannotWeigh: List<String> = emptyList()) = PetitionRead(
        petition, listOf(owner),
        PetitionWeight(LocalDate.parse("2026-10-09"), BigDecimal(held), BigDecimal("20"), "чл. 12 ЗУЕС", false, "GA_PETITION_MIN_PCT@2009-01-01", cannotWeigh, false, setOf(owner)), null,
    )

    @Test
    fun `PM-GA-003 a petition reads with the share held, the threshold with its source, and whether it unlocks`() {
        whenever(petitions.open(entranceId, owner, "Ремонт на покрива")).thenReturn(petition)
        whenever(petitions.read(entranceId, petition.id)).thenReturn(weighed("8.5"), weighed("21.75"))
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content("""{"openedBy":"$owner","subject":"Ремонт на покрива"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.heldPct").value("8.5"))
            .andExpect(jsonPath("$.thresholdPct").value("20"))
            .andExpect(jsonPath("$.thresholdSource").value("чл. 12 ЗУЕС"))
            .andExpect(jsonPath("$.thresholdVerified").value(false))
            .andExpect(jsonPath("$.unlocked").value(false))
            .andExpect(jsonPath("$.signatories[0]").value(owner.toString()))
        mvc.perform(post("$base/${petition.id}/signatures").contentType(MediaType.APPLICATION_JSON).content("""{"partyId":"${UUID.randomUUID()}"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.heldPct").value("21.75"))
            .andExpect(jsonPath("$.unlocked").value(true))
    }

    @Test
    fun `PM-GA-003 a petition that cannot be weighed says why, and convening on it is a 409 with the reason`() {
        val why = "unit 7 has both an owner and a holder of a right of use — whose ideal parts count is not settled (TODO(legal): PM-GA-003)"
        whenever(petitions.read(entranceId, petition.id)).thenReturn(weighed("55", listOf(why)))
        mvc.perform(get("$base/${petition.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.unlocked").value(false))
            .andExpect(jsonPath("$.cannotWeigh[0]").value(why))

        whenever(petitions.convene(eq(entranceId), eq(petition.id), any())).thenThrow(PetitionLocked("the petition cannot be weighed: $why"))
        mvc.perform(
            post("$base/${petition.id}/assembly").contentType(MediaType.APPLICATION_JSON)
                .content("""{"convenedBy":"$owner","scheduledAt":"2026-11-20T16:00:00Z","place":"фоайето","mode":"IN_PERSON","demandUnmet":"не е свикано"}"""),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.error").value("the petition cannot be weighed: $why"))
    }

    @Test
    fun `PM-GA-003 convening on an unlocked petition answers the owners' draft with what unlocked it`() {
        val request = ConveneOnPetition(owner, Instant.parse("2026-11-20T16:00:00Z"), "фоайето", "IN_PERSON", "не е свикано")
        whenever(petitions.convene(entranceId, petition.id, request)).thenReturn(
            Assembly(UUID.randomUUID(), entranceId, owner, "OWNERS", request.scheduledAt, "фоайето", "IN_PERSON", "DRAFT", false, null, petitionId = petition.id) to
                PetitionUnlock(
                    petition.id, entranceId, owner, "не е свикано", LocalDate.parse("2026-10-09"), BigDecimal("21.7500000000"), BigDecimal("20"),
                    "GA_PETITION_MIN_PCT@2009-01-01", false, "1.3", "0.3.0", at,
                ),
        )
        mvc.perform(
            post("$base/${petition.id}/assembly").contentType(MediaType.APPLICATION_JSON)
                .content("""{"convenedBy":"$owner","scheduledAt":"2026-11-20T16:00:00Z","place":"фоайето","mode":"IN_PERSON","demandUnmet":"не е свикано"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.convenedAs").value("OWNERS"))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.heldPct").value("21.75"))
            .andExpect(jsonPath("$.thresholdPct").value("20"))
    }

    @Test
    fun `a signature by someone who is not an owner is a 400, and a missing petition a 404`() {
        whenever(petitions.sign(any(), any(), any())).thenThrow(IllegalArgumentException("only an owner"))
        mvc.perform(post("$base/${petition.id}/signatures").contentType(MediaType.APPLICATION_JSON).content("""{"partyId":"$owner"}"""))
            .andExpect(status().isBadRequest)
        whenever(petitions.read(any(), any())).thenThrow(NoSuchElementException("no petition"))
        mvc.perform(get("$base/${petition.id}")).andExpect(status().isNotFound)
    }
}
