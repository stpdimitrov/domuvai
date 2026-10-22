package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.app.identity_org.Logins
import zues.app.registry.AssignTitle
import zues.app.registry.OwnershipService
import zues.app.registry.RegisterEntrance
import zues.app.registry.RegisterParty
import zues.app.registry.RegisterUnit
import zues.app.registry.RegistryService
import zues.kernel.toSofiaDate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Money's personal reads against real PostgreSQL, through the sign-in, the roles the registry and the mandates give,
 * and the policy (PM-SEC-002, ADR-002). Docker-gated — runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class MoneyAccessIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            val schemas = "registry,identity_org,assembly,money,maintenance,compliance,evidence,app,intake,public"
            registry.add("spring.datasource.url") { "${postgres.jdbcUrl}&currentSchema=$schemas" }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var registry: RegistryService
    @Autowired lateinit var ownership: OwnershipService
    @Autowired lateinit var logins: Logins
    @Autowired lateinit var jdbc: JdbcTemplate

    private val realm = "https://id.example.test/realms/${UUID.randomUUID()}"
    private val today: LocalDate get() = LocalDate.parse(toSofiaDate(Instant.now()))

    private fun person(name: String, subject: String): UUID = ownership.registerParty(RegisterParty(name)).also { logins.tie(realm, subject, it) }

    private fun mandate(entrance: UUID, body: String, party: UUID) {
        jdbc.update(
            "INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), entrance, body, party, today.minusMonths(1), today.plusMonths(11),
        )
    }

    private fun MockHttpServletRequestBuilder.by(subject: String) = with(jwt().jwt { it.subject(subject).issuer(realm) })

    @Test
    fun `PM-SEC-002 PM-DEBT-007 PM-DEBT-003 PM-GOV-008 an owner reads their own unit only, the offices read the entrance and its journal, the cashier nothing`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Шипка 3", "А", "GA")).entranceId
        val (flat1, flat2) = registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "60.0000"), RegisterUnit("ап. 2", "APARTMENT", idealParts = "40.0000")))
        ownership.assignTitle(entrance, flat1, AssignTitle(person("Иван Петров", "owner-1"), "OWN", validFrom = "2020-01-01"))
        ownership.assignTitle(entrance, flat2, AssignTitle(person("Георги Стоянов", "owner-2"), "OWN", validFrom = "2020-01-01"))
        mandate(entrance, "BM", person("Мария Иванова", "manager"))
        mandate(entrance, "CTL", person("Елена Колева", "controller"))
        mandate(entrance, "CSH", person("Стефан Илиев", "cashier"))
        mvc.perform(
            post("/api/money/entrances/$entrance/charge-runs").contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(StoredChargeRunRequest("2026-05", "2026-05-01", lines = listOf(TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)))),
            ),
        ).andExpect(status().isCreated)

        fun statement(unit: UUID) = get("/api/money/units/$unit/statement")
        fun arrears(unit: UUID) = get("/api/money/units/$unit/arrears").param("asOf", "2026-05-20")
        val entranceArrears = { get("/api/money/entrances/$entrance/arrears").param("asOf", "2026-05-20") }

        // the owner of ап. 1: their own unit, nobody else's, not the entrance
        mvc.perform(statement(flat1).by("owner-1")).andExpect(status().isOk).andExpect(jsonPath("$.balanceMinor").value(6_000))
        mvc.perform(arrears(flat1).by("owner-1")).andExpect(status().isOk)
        mvc.perform(statement(flat2).by("owner-1")).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-002"))
        mvc.perform(arrears(flat2).by("owner-1")).andExpect(status().isForbidden)
        mvc.perform(entranceArrears().by("owner-1")).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-002"))
        val journal = { get("/api/money/entrances/$entrance/journal").param("from", "2026-01-01").param("to", "2026-12-31") }
        mvc.perform(journal().by("owner-1")).andExpect(status().isForbidden)            // the journal is every unit's charges too

        // the manager and the controller: every unit and the entrance
        for (office in listOf("manager", "controller")) {
            mvc.perform(statement(flat2).by(office)).andExpect(status().isOk)
            mvc.perform(entranceArrears().by(office)).andExpect(status().isOk).andExpect(jsonPath("$.totalMinor").value(10_000))
            mvc.perform(journal().by(office)).andExpect(status().isOk)
        }

        // the cashier, and a login tied to nobody: nothing
        for (nobody in listOf("cashier", "a-login-tied-to-nobody")) {
            mvc.perform(statement(flat1).by(nobody)).andExpect(status().isForbidden)
            mvc.perform(entranceArrears().by(nobody)).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `PM-SEC-002 a payment is read back by its unit's owner, not another, and an owner learns nothing of a payment that is not there`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Шипка 5", "А", "GA")).entranceId
        val (flat1, flat2) = registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "60.0000"), RegisterUnit("ап. 2", "APARTMENT", idealParts = "40.0000")))
        ownership.assignTitle(entrance, flat1, AssignTitle(person("Иван Петров", "p-owner-1"), "OWN", validFrom = "2020-01-01"))
        ownership.assignTitle(entrance, flat2, AssignTitle(person("Георги Стоянов", "p-owner-2"), "OWN", validFrom = "2020-01-01"))
        mandate(entrance, "BM", person("Мария Иванова", "p-manager"))
        mvc.perform(
            post("/api/money/entrances/$entrance/charge-runs").contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(StoredChargeRunRequest("2026-05", "2026-05-01", lines = listOf(TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)))),
            ),
        ).andExpect(status().isCreated)
        val paymentId = json.readTree(
            mvc.perform(
                post("/api/money/entrances/$entrance/payments").header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"unitId":"$flat1","amountMinor":1000,"valueDate":"2026-05-20","receivedInto":"CASH"}"""),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get("paymentId").asText()

        val read = { id: Any -> get("/api/money/entrances/$entrance/payments/$id") }
        mvc.perform(read(paymentId).by("p-owner-1")).andExpect(status().isOk)
        mvc.perform(read(paymentId).by("p-owner-2")).andExpect(status().isForbidden)
        mvc.perform(read(UUID.randomUUID()).by("p-owner-1")).andExpect(status().isForbidden)    // not a 404: an owner is told nothing
        mvc.perform(read(UUID.randomUUID()).by("p-manager")).andExpect(status().isNotFound)
    }

    @Test
    fun `PM-SEC-002 PM-SEC-001 PM-GOV-008 PM-BOOK-006 the fund is read by the offices and the entrance's owners, the headcount by the offices only`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Шипка 7", "А", "GA")).entranceId
        val (flat1) = registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "100.0000")))
        ownership.assignTitle(entrance, flat1, AssignTitle(person("Иван Петров", "f-owner"), "OWN", validFrom = "2020-01-01"))
        ownership.assignTitle(entrance, flat1, AssignTitle(person("Анна Петрова", "f-user"), "USR", validFrom = "2020-01-01"))
        mandate(entrance, "BM", person("Мария Иванова", "f-manager"))
        mandate(entrance, "MB", person("Петър Колев", "f-board"))
        mandate(entrance, "CTL", person("Елена Колева", "f-controller"))
        mandate(entrance, "CSH", person("Стефан Илиев", "f-cashier"))
        val elsewhere = registry.registerEntrance(RegisterEntrance("ул. Шипка 9", "А", "GA")).entranceId
        val (flatElsewhere) = registry.registerUnits(elsewhere, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "100.0000")))
        ownership.assignTitle(elsewhere, flatElsewhere, AssignTitle(person("Георги Стоянов", "f-owner-elsewhere"), "OWN", validFrom = "2020-01-01"))

        val fundReads = listOf("fund-accounts", "fund/handover-statements").map { "/api/money/entrances/$entrance/$it" }
        // allowed, and then told there is nothing: no fund account, no operating account, no such statement
        val nothingThere = listOf("fund", "operating-account", "fund/handover-statements/${UUID.randomUUID()}").map { "/api/money/entrances/$entrance/$it" }
        val headcount = "/api/money/entrances/$entrance/charge-runs/2026-05/headcount"
        for (reader in listOf("f-owner", "f-manager", "f-board", "f-controller")) {
            for (path in fundReads) mvc.perform(get(path).by(reader)).andExpect(status().isOk)
            for (path in nothingThere) mvc.perform(get(path).by(reader)).andExpect(status().isNotFound)
        }
        for (nobody in listOf("f-user", "f-cashier", "f-owner-elsewhere", "a-login-tied-to-nobody")) {
            for (path in fundReads + nothingThere) {
                mvc.perform(get(path).by(nobody)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-002"))
            }
        }
        for (office in listOf("f-manager", "f-board", "f-controller")) mvc.perform(get(headcount).by(office)).andExpect(status().isNotFound)   // allowed; no run issued
        for (nobody in listOf("f-owner", "f-cashier")) {
            mvc.perform(get(headcount).by(nobody)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-BOOK-006"))
        }
    }
}
