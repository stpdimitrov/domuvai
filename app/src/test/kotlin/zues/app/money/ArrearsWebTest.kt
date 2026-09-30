package zues.app.money

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

@WebMvcTest(ArrearsController::class)
class ArrearsWebTest {

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var arrears: ArrearsService

    private val unitId = UUID.randomUUID()

    @Test
    fun `GET arrears returns the aged buckets`() {
        whenever(arrears.forUnit(eq(unitId), any())).thenReturn(
            UnitArrears(
                unitId, "2026-06-01", 3_000,
                listOf(
                    AgeingBucket("CURRENT", 0), AgeingBucket("0-30", 1_000),
                    AgeingBucket("31-60", 0), AgeingBucket("61-90", 0), AgeingBucket("90+", 2_000),
                ),
            ),
        )
        mvc.perform(get("/api/money/units/$unitId/arrears").param("asOf", "2026-06-01"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(3000))
            .andExpect(jsonPath("$.buckets[1].band").value("0-30"))
            .andExpect(jsonPath("$.buckets[4].amountMinor").value(2000))
    }

    @Test
    fun `a malformed asOf is a 400`() {
        mvc.perform(get("/api/money/units/$unitId/arrears").param("asOf", "nonsense"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET an entrance's arrears returns each owing unit and the total, with the oldest debt's due day`() {
        val entranceId = UUID.randomUUID()
        val bands = listOf(AgeingBucket("CURRENT", 0), AgeingBucket("0-30", 8_000), AgeingBucket("31-60", 0), AgeingBucket("61-90", 0), AgeingBucket("90+", 0))
        whenever(arrears.forEntrance(eq(entranceId), eq(LocalDate.parse("2026-09-30")))).thenReturn(
            EntranceArrears(entranceId, "2026-09-30", 8_000, listOf(UnitArrears(unitId, "2026-09-30", 8_000, bands, OldestDebt(LocalDate.parse("2026-09-15"), 15)))),
        )
        mvc.perform(get("/api/money/entrances/$entranceId/arrears").param("asOf", "2026-09-30"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(8000))
            .andExpect(jsonPath("$.units[0].unitId").value(unitId.toString()))
            .andExpect(jsonPath("$.units[0].oldestDebt.dueOn").value("2026-09-15"))
            .andExpect(jsonPath("$.units[0].oldestDebt.overdueDays").value(15))
        mvc.perform(get("/api/money/entrances/$entranceId/arrears").param("asOf", "30.09.2026"))
            .andExpect(status().isBadRequest)
    }
}
