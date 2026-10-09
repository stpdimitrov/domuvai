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
import zues.law.Comparison
import zues.law.Denominator
import zues.law.MajorityRule
import java.time.Instant
import java.util.UUID

@WebMvcTest(AssemblyController::class)
class AssemblyWebTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var assemblies: AssemblyService

    private val entranceId = UUID.randomUUID()
    private val convenor = UUID.randomUUID()
    private val at = Instant.parse("2026-11-20T17:00:00Z")
    private val draft = Assembly(UUID.randomUUID(), entranceId, convenor, "CTL", at, "фоайето", "IN_PERSON", "DRAFT", true, "спукана тръба")
    private val base = "/api/assembly/entrances/$entranceId/assemblies"

    private fun body(convenedAs: String) =
        """{"convenedBy":"$convenor","convenedAs":"$convenedAs","scheduledAt":"$at","place":"фоайето","mode":"IN_PERSON","urgent":true,"urgencyReason":"спукана тръба"}"""

    @Test
    fun `PM-GA-005 POST convenes a draft and answers 201 with the capacity and the recorded urgency`() {
        whenever(assemblies.convene(entranceId, Convene(convenor, "CTL", at, "фоайето", "IN_PERSON", true, "спукана тръба"))).thenReturn(draft)
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(body("CTL")))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.convenedAs").value("CTL"))
            .andExpect(jsonPath("$.urgent").value(true))
            .andExpect(jsonPath("$.urgencyReason").value("спукана тръба"))
    }

    @Test
    fun `PM-GA-002 a capacity that may not convene is a 400`() {
        whenever(assemblies.convene(eq(entranceId), any())).thenThrow(IllegalArgumentException("No enum constant ConvenorOffice.CSH"))
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(body("CSH"))).andExpect(status().isBadRequest)
    }

    @Test
    fun `PM-VOTE-004 POST an agenda item answers the majority it was bound to, with its rule and source`() {
        val majority = MajorityRule("COMMON_PART_USE_RIGHT", "88.5", Comparison.AT_LEAST, Denominator.TOTAL, "2026-01-01", "чл. 17 ЗУЕС", false, "PM-VOTE-004")
        val item = AgendaItem(UUID.randomUUID(), entranceId, draft.id, 1, "Покривът под наем", "COMMON_PART_USE_RIGHT", majority.id)
        whenever(assemblies.addAgendaItem(entranceId, draft.id, "Покривът под наем", "COMMON_PART_USE_RIGHT")).thenReturn(item to majority)
        mvc.perform(
            post("$base/${draft.id}/agenda").contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"Покривът под наем","itemType":"COMMON_PART_USE_RIGHT"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.majority.thresholdPct").value("88.5"))
            .andExpect(jsonPath("$.majority.denominator").value("TOTAL"))
            .andExpect(jsonPath("$.majority.comparison").value("AT_LEAST"))
            .andExpect(jsonPath("$.majority.source").value("чл. 17 ЗУЕС"))
            .andExpect(jsonPath("$.majority.rule").value("PM-VOTE-004"))
            .andExpect(jsonPath("$.majority.verified").value(false))
    }

    @Test
    fun `PM-VOTE-004 an item whose majority waits on counsel is a 422, and a missing assembly a 404`() {
        whenever(assemblies.addAgendaItem(any(), any(), any(), eq("COMMON_PART_USE_RIGHT"))).thenThrow(MajorityPending("TODO(legal): PM-VOTE-004"))
        mvc.perform(
            post("$base/${draft.id}/agenda").contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"Покривът под наем","itemType":"COMMON_PART_USE_RIGHT"}"""),
        ).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.error").value("TODO(legal): PM-VOTE-004"))

        whenever(assemblies.addAgendaItem(any(), any(), any(), eq("GENERAL"))).thenThrow(IllegalStateException("not a draft"))
        mvc.perform(post("$base/${draft.id}/agenda").contentType(MediaType.APPLICATION_JSON).content("""{"text":"Разни","itemType":"GENERAL"}"""))
            .andExpect(status().isConflict)

        whenever(assemblies.read(entranceId, draft.id)).thenThrow(NoSuchElementException("no assembly"))
        mvc.perform(get("$base/${draft.id}")).andExpect(status().isNotFound)
    }

    @Test
    fun `GET reads the assembly with its agenda in order`() {
        val item = AgendaItem(UUID.randomUUID(), entranceId, draft.id, 1, "Отчет", "GENERAL", "GENERAL@2009-01-01")
        whenever(assemblies.read(entranceId, draft.id)).thenReturn(draft to listOf(item))
        mvc.perform(get("$base/${draft.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.place").value("фоайето"))
            .andExpect(jsonPath("$.agenda[0].majorityRuleId").value("GENERAL@2009-01-01"))
    }
}
