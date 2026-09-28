package zues.app.registry

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@WebMvcTest(DeclarationController::class)
class DeclarationWebTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var declarations: DeclarationService

    @TestConfiguration
    class FixedClock {
        // 00:30 on 10 June in Sofia (UTC+3 in summer); still 9 June in UTC
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-06-09T21:30:00Z"), ZoneOffset.UTC)
    }

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()

    @Test
    fun `POST a declaration returns 201 with the system's filing date and the template version`() {
        whenever(declarations.file(entranceId, unitId, partyId, "ACQUISITION")).thenReturn(
            BookDeclaration(UUID.randomUUID(), entranceId, unitId, partyId, "ACQUISITION", LocalDate.parse("2026-06-10"), "ministry-template-1"),
        )
        mvc.perform(
            post("/api/registry/entrances/$entranceId/book/declarations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"unitId":"$unitId","partyId":"$partyId"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.filedOn").value("2026-06-10"))
            .andExpect(jsonPath("$.templateVersion").value("ministry-template-1"))
    }

    @Test
    fun `PM-SYS-004 GET overdue defaults to today in Sofia and names the holder only`() {
        whenever(declarations.overdue(entranceId, LocalDate.parse("2026-06-10"))).thenReturn(
            listOf(OverdueDeclaration(unitId, "ап. 1", "Мария Георгиева", "OWN", LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-18"))),
        )
        mvc.perform(get("/api/registry/entrances/$entranceId/book/declarations/overdue"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].partyName").value("Мария Георгиева"))
            .andExpect(jsonPath("$[0].dueOn").value("2026-05-18"))
    }

    @Test
    fun `an unknown kind or a malformed date is a 400`() {
        whenever(declarations.file(any(), any(), any(), eq("WHIM"))).thenThrow(IllegalArgumentException("unknown kind WHIM"))
        mvc.perform(
            post("/api/registry/entrances/$entranceId/book/declarations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"unitId":"$unitId","partyId":"$partyId","kind":"WHIM"}"""),
        ).andExpect(status().isBadRequest)
        mvc.perform(get("/api/registry/entrances/$entranceId/book/declarations/overdue").param("on", "16.06.2026"))
            .andExpect(status().isBadRequest)
    }
}
