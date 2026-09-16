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
class OwnershipWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var ownership: OwnershipService

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
    fun `PM-BOOK-011 the owners response carries the name but no id number`() {
        whenever(ownership.ownersAsOf(any(), any()))
            .thenReturn(listOf(OwnerView(unitId, "Иван Петров", "OWN", BigDecimal("1.000000"))))
        mvc.perform(get("/api/registry/entrances/$entranceId/owners").param("on", "2026-05-01"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].partyName").value("Иван Петров"))
            .andExpect(jsonPath("$[0].idValue").doesNotExist())
    }
}
