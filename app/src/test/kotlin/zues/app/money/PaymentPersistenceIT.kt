package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.law.CATALOGUE_VERSION
import zues.law.ENGINE_VERSION
import java.util.UUID

/**
 * Payments against real PostgreSQL (PM-DEBT-008): bill a unit for April and May, record payments,
 * and see the allocation stored, read back, and reflected in the statement and the arrears bands.
 * Docker-gated — skips locally without Docker, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PaymentPersistenceIT {

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
    @Autowired lateinit var jdbc: JdbcTemplate

    /** An entrance with ап. 1 (60%) and ап. 2 (40%), each billed for April and May; returns (entrance, ап. 1). */
    private fun billedUnit(): Pair<UUID, UUID> {
        val entrance = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON)
                .content("""{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val entranceId = UUID.fromString(json.readTree(entrance).get("entranceId").asText())
        val units = mvc.perform(
            post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON).content(
                """{"units":[
                     {"designation":"ап. 1","unitType":"FLAT","idealParts":"60.0000","separateEntrance":false},
                     {"designation":"ап. 2","unitType":"FLAT","idealParts":"40.0000","separateEntrance":false}
                   ]}""",
            ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        // ап. 1 owes 18000 for each period: 60% of 10000 management + 20000 maintenance
        listOf("2026-04", "2026-05").forEach { period ->
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
        return entranceId to UUID.fromString(json.readTree(units).get("unitIds")[0].asText())
    }

    private fun pay(entranceId: UUID, key: String, body: String): ResultActions = mvc.perform(
        post("/api/money/entrances/$entranceId/payments").header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body),
    )

    private fun owed(unitId: UUID): Long = json.readTree(
        mvc.perform(get("/api/money/units/$unitId/statement")).andReturn().response.contentAsString,
    ).get("balanceMinor").asLong()

    @Test
    fun `PM-DEBT-008 a payment settles the oldest debt first and the arrears fall where the debt was`() {
        val (entranceId, unitId) = billedUnit()
        pay(entranceId, "p-1", """{"unitId":"$unitId","amountMinor":25000,"valueDate":"2026-05-20","receivedInto":"CASH"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.allocationRule").value("OLDEST_FIRST"))
            .andExpect(jsonPath("$.allocation[0].debtDate").value("2026-04-01"))
            .andExpect(jsonPath("$.allocation[0].amountMinor").value(18_000))
            .andExpect(jsonPath("$.allocation[1].debtDate").value("2026-05-01"))
            .andExpect(jsonPath("$.allocation[1].amountMinor").value(7_000))

        assertEquals(11_000, owed(unitId))
        // April is settled, so nothing stays in its older band; May's remainder is 5 days overdue
        // (the same payment term ArrearsIT relies on).
        mvc.perform(get("/api/money/units/$unitId/arrears").param("asOf", "2026-05-20"))
            .andExpect(jsonPath("$.totalMinor").value(11_000))
            .andExpect(jsonPath("$.buckets[?(@.band == '31-60')].amountMinor").value(0))
            .andExpect(jsonPath("$.buckets[?(@.band == '0-30')].amountMinor").value(11_000))
        // read as of a day before the money arrived, both months are still wholly owed
        mvc.perform(get("/api/money/units/$unitId/arrears").param("asOf", "2026-05-10"))
            .andExpect(jsonPath("$.totalMinor").value(36_000))
    }

    @Test
    fun `PM-DEBT-008 a payment never reaches a debt raised after it`() {
        val (entranceId, unitId) = billedUnit()
        // paid on 04-20: only April existed, so the rest is an advance even though May is billed now
        pay(entranceId, "p-1", """{"unitId":"$unitId","amountMinor":20000,"valueDate":"2026-04-20","receivedInto":"CASH"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.allocation.length()").value(1))
            .andExpect(jsonPath("$.allocation[0].debtDate").value("2026-04-01"))
            .andExpect(jsonPath("$.unallocatedMinor").value(2_000))
        assertEquals(18_000, owed(unitId))
    }

    @Test
    fun `PM-DEBT-008 a designated debt is settled first and the allocation is explainable per payment`() {
        val (entranceId, unitId) = billedUnit()
        val recorded = pay(
            entranceId, "p-1",
            """{"unitId":"$unitId","amountMinor":20000,"valueDate":"2026-05-20","receivedInto":"CASH","designatedDebtDate":"2026-05-01"}""",
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val paymentId = json.readTree(recorded).get("paymentId").asText()

        val readBack = mvc.perform(get("/api/money/entrances/$entranceId/payments/$paymentId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.allocationRule").value("DESIGNATED"))
            .andExpect(jsonPath("$.designatedDebtDate").value("2026-05-01"))
            .andExpect(jsonPath("$.allocation[0].debtDate").value("2026-04-01"))
            .andExpect(jsonPath("$.allocation[0].amountMinor").value(2_000))
            .andExpect(jsonPath("$.allocation[1].debtDate").value("2026-05-01"))
            .andExpect(jsonPath("$.allocation[1].amountMinor").value(18_000))
            .andReturn().response.contentAsString
        assertEquals(json.readTree(recorded), json.readTree(readBack))

        // ADR-006: the stored basis carries the rule, its remainder rule and the debts it saw
        val basis = json.readTree(jdbc.queryForObject("SELECT basis::text FROM money.payment WHERE id = ?::uuid", String::class.java, paymentId))
        assertEquals("DESIGNATED", basis.get("rule").asText())
        assertEquals(PaymentBasis.REMAINDER_RULE, basis.get("remainderRule").asText())
        assertEquals(2, basis.get("openDebts").size())
        // ADR-001: the hash re-derives from the stored basis, and the law and engine are pinned
        val pinned = jdbc.queryForMap("SELECT basis_hash, law_version, engine_version FROM money.payment WHERE id = ?::uuid", paymentId)
        @Suppress("UNCHECKED_CAST")
        val canonical = BasisJson.canonical(json.convertValue(basis, Map::class.java) as Map<String, Any?>)
        assertEquals(BasisJson.hash(canonical), pinned["basis_hash"])
        assertEquals(CATALOGUE_VERSION, pinned["law_version"])
        assertEquals(ENGINE_VERSION, pinned["engine_version"])
    }

    @Test
    fun `PM-DEBT-008 a designation of a debt that is not owed is refused, never stored as DESIGNATED`() {
        val (entranceId, unitId) = billedUnit()
        pay(
            entranceId, "p-1",
            """{"unitId":"$unitId","amountMinor":20000,"valueDate":"2026-05-20","receivedInto":"CASH","designatedDebtDate":"2026-06-01"}""",
        ).andExpect(status().isBadRequest)
        assertEquals(36_000, owed(unitId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM money.payment WHERE unit_id = ?::uuid", Int::class.java, unitId.toString()))
    }

    @Test
    fun `PM-DEBT-008 a payment dated before one already recorded is refused, and so is a future date`() {
        val (entranceId, unitId) = billedUnit()
        pay(entranceId, "p-1", """{"unitId":"$unitId","amountMinor":5000,"valueDate":"2026-05-20","receivedInto":"CASH"}""")
            .andExpect(status().isCreated)
        pay(entranceId, "p-2", """{"unitId":"$unitId","amountMinor":5000,"valueDate":"2026-05-10","receivedInto":"CASH"}""")
            .andExpect(status().isConflict)
        pay(entranceId, "p-3", """{"unitId":"$unitId","amountMinor":5000,"valueDate":"2999-01-01","receivedInto":"CASH"}""")
            .andExpect(status().isBadRequest)
        assertEquals(31_000, owed(unitId))
    }

    @Test
    fun `the ledger refuses a credit naming a later debt, and a recorded payment cannot be rewritten`() {
        val (entranceId, unitId) = billedUnit()
        val refused = runCatching {
            jdbc.update(
                "INSERT INTO money.posting (id, entrance_id, journal_id, account, unit_id, amount_minor, value_date, settles_value_date) " +
                    "VALUES (gen_random_uuid(), ?::uuid, gen_random_uuid(), 'RECEIVABLE', ?::uuid, -100, DATE '2026-05-01', DATE '2026-06-01')",
                entranceId.toString(), unitId.toString(),
            )
        }.exceptionOrNull()
        assertEquals(true, refused?.message?.contains("posting_settles_a_receivable"), "the CHECK must name itself: $refused")

        val id = json.readTree(
            pay(entranceId, "p-1", """{"unitId":"$unitId","amountMinor":5000,"valueDate":"2026-05-20","receivedInto":"CASH"}""")
                .andReturn().response.contentAsString,
        ).get("paymentId").asText()
        assertEquals(0, jdbc.update("UPDATE money.payment SET amount_minor = 1 WHERE id = ?::uuid", id))
        assertEquals(5000L, jdbc.queryForObject("SELECT amount_minor FROM money.payment WHERE id = ?::uuid", Long::class.java, id))
    }

    @Test
    fun `PM-DEBT-008 a retried payment is recorded once`() {
        val (entranceId, unitId) = billedUnit()
        val body = """{"unitId":"$unitId","amountMinor":5000,"valueDate":"2026-05-20","receivedInto":"CASH"}"""
        val first = pay(entranceId, "p-1", body).andExpect(status().isCreated).andReturn().response.contentAsString
        val retry = pay(entranceId, "p-1", body).andExpect(status().isCreated).andReturn().response.contentAsString

        assertEquals(json.readTree(first), json.readTree(retry))
        assertEquals(31_000, owed(unitId))
        pay(entranceId, "p-1", body.replace("5000", "6000")).andExpect(status().isConflict)
    }

    @Test
    fun `PM-DEBT-008 an overpayment clears every debt and the rest is held as an advance`() {
        val (entranceId, unitId) = billedUnit()
        val body = """{"unitId":"$unitId","amountMinor":40000,"valueDate":"2026-05-20","receivedInto":"OPERATING"}"""
        // no OPERATING account is registered for the entrance yet
        pay(entranceId, "p-1", body).andExpect(status().isBadRequest)

        mvc.perform(
            post("/api/money/entrances/$entranceId/fund-accounts").contentType(MediaType.APPLICATION_JSON).content(
                """{"iban":"BG80BNBG${"%014d".format(System.nanoTime() % 100_000_000_000_000)}","purpose":"OPERATING","holderName":"Иван Петров","holderKind":"MANAGER"}""",
            ),
        ).andExpect(status().isCreated)
        pay(entranceId, "p-2", body)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.receivedInto").value("OPERATING"))
            .andExpect(jsonPath("$.unallocatedMinor").value(4_000))
        assertEquals(0, owed(unitId))
    }

    @Test
    fun `a unit from another entrance is refused`() {
        val (_, unitId) = billedUnit()
        val (otherEntrance, _) = billedUnit()
        pay(otherEntrance, "p-1", """{"unitId":"$unitId","amountMinor":1000,"valueDate":"2026-05-20","receivedInto":"CASH"}""")
            .andExpect(status().isNotFound)
    }

    @Test
    fun `PM-DEBT-001 an overpayment is netted against the next charge in both arrears reads`() {
        val (entranceId, unitId) = billedUnit()
        pay(entranceId, "p-1", """{"unitId":"$unitId","amountMinor":40000,"valueDate":"2026-05-20","receivedInto":"CASH"}""")
            .andExpect(status().isCreated).andExpect(jsonPath("$.unallocatedMinor").value(4_000))
        mvc.perform(
            post("/api/money/entrances/$entranceId/charge-runs").contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(
                    StoredChargeRunRequest(
                        period = "2026-06", legalDate = "2026-06-01",
                        lines = listOf(
                            TariffLineRequest("MANAGEMENT", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000),
                            TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 20_000),
                        ),
                    ),
                ),
            ),
        ).andExpect(status().isCreated)

        mvc.perform(get("/api/money/units/$unitId/arrears").param("asOf", "2026-06-20"))
            .andExpect(jsonPath("$.totalMinor").value(18_000))
            .andExpect(jsonPath("$.advanceMinor").value(4_000))
            .andExpect(jsonPath("$.netMinor").value(14_000))
        mvc.perform(get("/api/money/entrances/$entranceId/arrears").param("asOf", "2026-06-20"))
            .andExpect(jsonPath("$.units[?(@.unitId == '$unitId')].netMinor").value(14_000))
            .andExpect(jsonPath("$.totalMinor").value(54_000))     // ап. 1 owes June 18,000; ап. 2 owes 12,000 a month, unpaid, for three months
            .andExpect(jsonPath("$.advanceMinor").value(4_000))
            .andExpect(jsonPath("$.netMinor").value(50_000))
    }

    @Test
    fun `PM-DEBT-001 the ledger refuses a receivable or an advance that names no unit`() {
        val (entranceId, _) = billedUnit()
        listOf("RECEIVABLE", "ADVANCE").forEach { account ->
            val refused = runCatching {
                jdbc.update(
                    "INSERT INTO money.posting (id, entrance_id, journal_id, account, unit_id, amount_minor, value_date) " +
                        "VALUES (gen_random_uuid(), ?::uuid, gen_random_uuid(), ?, NULL, -100, DATE '2026-05-01')",
                    entranceId.toString(), account,
                )
            }.exceptionOrNull()
            assertEquals(true, refused?.message?.contains("posting_unit_receivable_advance"), "$account: $refused")
        }
    }
}
