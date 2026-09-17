package zues.app.money

import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.util.UUID

@WebMvcTest(StatementController::class)
class StatementWebTest {

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var statements: StatementService

    private val unitId = UUID.randomUUID()

    @Test
    fun `GET statement returns the balance and itemised lines`() {
        whenever(statements.forUnit(unitId)).thenReturn(
            UnitStatement(
                unitId, 18_000,
                listOf(StatementLine("2026-05", "MANAGEMENT", "BY_IDEAL_PARTS", BigDecimal("60.000000"), 6_000, "derived")),
            ),
        )
        mvc.perform(get("/api/money/units/$unitId/statement"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balanceMinor").value(18000))
            .andExpect(jsonPath("$.lines[0].component").value("MANAGEMENT"))
            .andExpect(jsonPath("$.lines[0].derivation").value("derived"))
    }
}
