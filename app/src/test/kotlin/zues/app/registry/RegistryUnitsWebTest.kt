package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.math.BigDecimal

/**
 * The HTTP edge for units, with the service mocked — proves routing and how the two
 * failure modes surface (invalid parts → 400, unknown entrance → 404) without a database.
 */
@WebMvcTest(RegistryController::class)
class RegistryUnitsWebTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @MockitoBean lateinit var registry: RegistryService

    private val entranceId = UUID.randomUUID()
    private val body = RegisterUnitsRequest(
        listOf(NewUnitRequest("ап. 1", "FLAT", null, "100.0000", false)),
    )

    private fun postUnits() = post("/api/registry/entrances/$entranceId/units")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body))

    @Test
    fun `POST units returns 201 with the created ids`() {
        val id = UUID.randomUUID()
        whenever(registry.registerUnits(any(), any())).thenReturn(listOf(id))

        mvc.perform(postUnits())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.unitIds[0]").value(id.toString()))
    }

    @Test
    fun `ideal parts that do not sum to 100 percent are a 400`() {
        whenever(registry.registerUnits(any(), any()))
            .thenThrow(IllegalStateException("ideal parts sum to 99.980000%, must be 100.000000% (PM-ORG-002)"))

        mvc.perform(postUnits()).andExpect(status().isBadRequest)
    }

    @Test
    fun `units for an unknown entrance are a 404`() {
        whenever(registry.registerUnits(any(), any()))
            .thenThrow(NoSuchElementException("no entrance $entranceId"))

        mvc.perform(postUnits()).andExpect(status().isNotFound)
    }

    @Test
    fun `PM-ORG-009 a unit is registered with its business use and its separate entrance as two facts`() {
        val commands = argumentCaptor<List<RegisterUnit>>()
        whenever(registry.registerUnits(any(), commands.capture())).thenReturn(List(4) { UUID.randomUUID() })

        mvc.perform(
            post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON).content(
                """{"units":[
                     {"designation":"магазин 1","unitType":"FLAT","idealParts":"30.0000","businessUse":true},
                     {"designation":"магазин 2","unitType":"FLAT","idealParts":"30.0000","businessUse":true,"separateEntrance":true},
                     {"designation":"ап. 1","unitType":"FLAT","idealParts":"20.0000","separateEntrance":true},
                     {"designation":"ап. 2","unitType":"FLAT","idealParts":"20.0000"}
                   ]}""",
            ),
        ).andExpect(status().isCreated)

        assertThat(commands.firstValue.map { it.businessUse to it.separateEntrance })
            .containsExactly(true to false, true to true, false to true, false to false)
    }

    @Test
    fun `PM-ORG-009 GET units returns business use and the separate entrance apart`() {
        whenever(registry.listUnits(entranceId)).thenReturn(
            listOf(
                PropertyUnit(UUID.randomUUID(), entranceId, "магазин 1", "FLAT", null, BigDecimal("60.0000"), separateEntrance = false, businessUse = true),
                PropertyUnit(UUID.randomUUID(), entranceId, "ап. 1", "FLAT", null, BigDecimal("40.0000"), separateEntrance = true, businessUse = false),
            ),
        )
        mvc.perform(get("/api/registry/entrances/$entranceId/units"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].businessUse").value(true))
            .andExpect(jsonPath("$[0].separateEntrance").value(false))
            .andExpect(jsonPath("$[1].businessUse").value(false))
            .andExpect(jsonPath("$[1].separateEntrance").value(true))
    }
}
