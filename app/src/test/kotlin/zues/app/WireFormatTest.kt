package zues.app

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import zues.app.money.DisbursementView
import zues.app.money.FundController
import zues.app.money.FundService
import zues.app.money.FundView
import java.time.LocalDate
import java.util.UUID

/**
 * The wire says what the published contract says (ADR-013, E2E-01): a field with no value is left out, never sent
 * as `null` — the generated client types it as absent. `jsonPath(…).doesNotExist()` passes on a null too, so this
 * reads the body as sent. Only the HTTP converter changes: the shared ObjectMapper, which also writes the event
 * publication registry, still writes nulls, so no stored event's text changes.
 */
@WebMvcTest(FundController::class)
class WireFormatTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var shared: ObjectMapper
    @Autowired lateinit var adapter: RequestMappingHandlerAdapter
    @MockitoBean lateinit var fund: FundService

    private val open = DisbursementView(UUID.randomUUID(), 20_000, "WORKS", "GA-2026-7", null, null, UUID.randomUUID(), "COMMITTED", LocalDate.parse("2026-09-30"))

    @Test
    fun `an empty field is left out of a response, not sent as null`() {
        val entranceId = UUID.randomUUID()
        whenever(fund.view(entranceId)).thenReturn(FundView(entranceId, "BG80BNBG96611020345678", "Иван Петров", 50_000, 20_000, 30_000, listOf(open)))

        val body = mvc.perform(get("/api/money/entrances/$entranceId/fund")).andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(body).contains("\"decisionId\":\"GA-2026-7\"", "\"committedOn\":\"2026-09-30\"")
        assertThat(body).doesNotContain("null", "\"paidOn\"", "\"emergencyJustification\"", "\"passportMeasure\"", "\"cancelReason\"")
    }

    @Test
    fun `only the wire changes — the shared mapper still writes null, and a map keeps its null values`() {
        assertThat(shared.writeValueAsString(open)).contains("\"paidOn\":null")          // stored events keep their text
        val wire = adapter.messageConverters.filterIsInstance<MappingJackson2HttpMessageConverter>()
            .first { it.canWrite(DisbursementView::class.java, MediaType.APPLICATION_JSON) }.objectMapper   // the one that writes
        assertThat(wire.writeValueAsString(open)).doesNotContain("null")
        assertThat(wire.writeValueAsString(mapOf("column" to null))).isEqualTo("""{"column":null}""")
    }
}
