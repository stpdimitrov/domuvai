package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.law.numberOn
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Arrears ageing against real PostgreSQL: issue a run, then read a unit's arrears as of a date and
 * see the outstanding land in the right band (PM-DEBT-001), each debt due by the payment term in force on
 * its own date (PM-SYS-002). Docker-gated — skips locally, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ArrearsIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            val schemas = "registry,identity_org,assembly,money,maintenance,compliance,evidence,app,public"
            registry.add("spring.datasource.url") { "${postgres.jdbcUrl}&currentSchema=$schemas" }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var postings: PostingRepository

    private fun createEntrance(): UUID {
        val response = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON)
                .content("""{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("entranceId").asText())
    }

    private fun registerUnits(entranceId: UUID): List<UUID> {
        val response = mvc.perform(
            post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON).content(
                """{"units":[
                     {"designation":"ап. 1","unitType":"FLAT","idealParts":"60.0000","separateEntrance":false},
                     {"designation":"ап. 2","unitType":"FLAT","idealParts":"40.0000","separateEntrance":false}
                   ]}""",
            ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(response).get("unitIds").map { UUID.fromString(it.asText()) }
    }

    /** May's run, raised at value date 2026-05-01. */
    private fun issueMay(entranceId: UUID) = issue(entranceId, "2026-05")

    /** A month's run: 10_000 + 20_000 by ideal parts, raised on the month's first day — ап. 1 (60%) owes 18_000, ап. 2 12_000. */
    private fun issue(entranceId: UUID, period: String) {
        mvc.perform(
            post("/api/money/entrances/$entranceId/charge-runs").contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(
                    StoredChargeRunRequest(
                        period = period, legalDate = "$period-01",
                        lines = listOf(
                            TariffLineRequest("MANAGEMENT", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000),
                            TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 20_000),
                        ),
                    ),
                ),
            ),
        ).andExpect(status().isCreated)
    }

    /** A cash payment — it settles the unit's oldest debt first. */
    private fun pay(entranceId: UUID, unitId: UUID, amountMinor: Long, on: String) {
        mvc.perform(
            post("/api/money/entrances/$entranceId/payments").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"unitId":"$unitId","amountMinor":$amountMinor,"receivedInto":"CASH","valueDate":"$on"}"""),
        ).andExpect(status().isCreated)
    }

    @Test
    fun `PM-DEBT-001 a unit's outstanding is aged as of a date`() {
        val entranceId = createEntrance()
        val units = registerUnits(entranceId)
        issueMay(entranceId)   // due 14 days after 2026-05-01, on 2026-05-15

        // 2026-05-20 is 5 days past due -> the whole 18000 (ап. 1, 60%) sits in the 0-30 band
        mvc.perform(get("/api/money/units/${units[0]}/arrears").param("asOf", "2026-05-20"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(18_000))
            .andExpect(jsonPath("$.buckets[1].band").value("0-30"))
            .andExpect(jsonPath("$.buckets[1].amountMinor").value(18_000))
            .andExpect(jsonPath("$.buckets[4].amountMinor").value(0))
    }

    @Test
    fun `PM-DEBT-001 an entrance's arrears list each unit that owes, with the day its oldest debt fell due`() {
        val entranceId = createEntrance()
        val units = registerUnits(entranceId)
        issueMay(entranceId)
        pay(entranceId, units[1], 12_000, "2026-05-10")                  // ап. 2 pays its 12_000 in full
        val dueOn = LocalDate.parse("2026-05-01").plusDays(numberOn("PAYMENT_TERM_DAYS", "2026-05-01").toLong())   // PM-DEBT-002, the term on the debt's date

        mvc.perform(get("/api/money/entrances/$entranceId/arrears").param("asOf", "2026-05-20"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(18_000))
            .andExpect(jsonPath("$.units.length()").value(1))                // ап. 2 owes nothing, so it is left out
            .andExpect(jsonPath("$.units[0].unitId").value(units[0].toString()))
            .andExpect(jsonPath("$.units[0].buckets[1].amountMinor").value(18_000))
            .andExpect(jsonPath("$.units[0].oldestDebt.dueOn").value(dueOn.toString()))
            .andExpect(jsonPath("$.units[0].oldestDebt.overdueDays").value(ChronoUnit.DAYS.between(dueOn, LocalDate.parse("2026-05-20"))))
    }

    @Test
    fun `PM-SYS-002 each debt falls due by the payment term in force on its own date — a part-paid debt, read back from Postgres`() {
        val entranceId = createEntrance()
        val units = registerUnits(entranceId)
        issue(entranceId, "2026-05")
        issue(entranceId, "2026-06")
        pay(entranceId, units[0], 8_000, "2026-06-10")                   // ап. 1: May's 18_000 is 10_000 now
        // The law has one term, so the read is given a test's own two: 10 days for a debt dated before June, 40 from then on.
        val twoTerms = ArrearsService(postings) { if (it.isBefore(LocalDate.parse("2026-06-01"))) 10 else 40 }

        val report = twoTerms.forEntrance(entranceId, LocalDate.parse("2026-07-01"))

        // May's debts fell due on 11.05, 51 days before the read; June's fall due on 11.07. The term on the read date
        // is 40 days: it would have May's fall due on 10.06, 21 days before the read.
        val mayDue = OldestDebt(LocalDate.parse("2026-05-11"), 51)
        fun bands(current: Long, days31to60: Long) = listOf(
            AgeingBucket("CURRENT", current), AgeingBucket("0-30", 0), AgeingBucket("31-60", days31to60),
            AgeingBucket("61-90", 0), AgeingBucket("90+", 0),
        )
        assertThat(report.units).containsExactly(
            UnitArrears(units[0], "2026-07-01", 28_000, bands(18_000, 10_000), mayDue),
            UnitArrears(units[1], "2026-07-01", 24_000, bands(12_000, 12_000), mayDue),
        )
        assertThat(report.totalMinor).isEqualTo(52_000)
    }

    @Test
    fun `PM-SYS-002 a read date before any payment term reads as nothing owed, not a bad request`() {
        val entranceId = createEntrance()
        val units = registerUnits(entranceId)
        issueMay(entranceId)

        mvc.perform(get("/api/money/entrances/$entranceId/arrears").param("asOf", "1990-01-01"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(0))
            .andExpect(jsonPath("$.units.length()").value(0))
        mvc.perform(get("/api/money/units/${units[0]}/arrears").param("asOf", "1990-01-01"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalMinor").value(0))
            .andExpect(jsonPath("$.oldestDebt").doesNotExist())
    }
}
