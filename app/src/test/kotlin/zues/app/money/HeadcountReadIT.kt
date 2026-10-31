package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
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
import zues.app.assembly.GaDecisions
import zues.app.identity_org.Logins
import zues.app.registry.AssignTitle
import zues.app.registry.BookHeadcountsAdapter
import zues.app.registry.OwnershipService
import zues.app.registry.RegisterEntrance
import zues.app.registry.RegisterMember
import zues.app.registry.RegisterParty
import zues.app.registry.RegisterUnit
import zues.app.registry.RegistryService
import zues.kernel.toSofiaDate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The headcount check's read of the book, end to end against real PostgreSQL: the entry survives the 403 thrown after it,
 * the table takes the new kind, and the book's own export lists it. Docker-gated — skips locally, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class HeadcountReadIT {

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
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var logins: Logins
    @MockitoBean lateinit var decisions: GaDecisions

    @BeforeEach
    fun everyCitedDecisionPassed() { whenever(decisions.passed(any(), any())).thenReturn(true) }

    private val realm = "https://id.example.test/realms/${UUID.randomUUID()}"
    private val today: LocalDate get() = LocalDate.parse(toSofiaDate(Instant.now()))
    private val legalDate = LocalDate.parse("2026-05-01")

    private fun person(name: String, subject: String): UUID = ownership.registerParty(RegisterParty(name)).also { logins.tie(realm, subject, it) }

    private fun MockHttpServletRequestBuilder.by(subject: String) = with(jwt().jwt { it.subject(subject).issuer(realm) })

    /** An entrance of one flat with two persons declared, its May issued, and [manager] in office. */
    private fun issued(manager: UUID): UUID {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Раковски 9", "А", "GA")).entranceId
        val flat = registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "100.0000"))).single()
        registry.registerHousehold(entrance, flat, listOf(RegisterMember(validFrom = "2020-01-01"), RegisterMember(validFrom = "2020-01-01")))
        jdbc.update(
            "INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (?, ?, 'BM', ?, ?, ?)",
            UUID.randomUUID(), entrance, manager, today.minusMonths(1), today.plusYears(1),
        )
        val run = json.writeValueAsString(
            StoredChargeRunRequest(period = "2026-05", legalDate = legalDate.toString(), lines = listOf(TariffLineRequest("MANAGEMENT", "PER_PERSON", "GA-2026-1", rateMinor = 500))),
        )
        mvc.perform(post("/api/money/entrances/$entrance/charge-runs").contentType(MediaType.APPLICATION_JSON).content(run)).andExpect(status().isCreated)
        return entrance
    }

    private fun entries(entrance: UUID): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT actor, purpose, book_date, outcome, rule_id FROM book_access WHERE entrance_id = ? AND kind = 'HEADCOUNT_READ' ORDER BY at, id", entrance)

    private fun report(entrance: UUID, period: String = "2026-05") = "/api/money/entrances/$entrance/charge-runs/$period/headcount"

    @Test
    fun `PM-BOOK-007 a served check writes exactly one HEADCOUNT_READ entry, dated the run's legal date, and the export lists it`() {
        val manager = person("Мария Иванова", "login-manager")
        val entrance = issued(manager)

        mvc.perform(get(report(entrance)).by("login-manager")).andExpect(status().isOk)
            .andExpect(jsonPath("$.unitsCompared").value(1)).andExpect(jsonPath("$.mismatches.length()").value(0))

        val entry = entries(entrance).single()
        assertThat(entry.values).containsExactly(manager, BookHeadcountsAdapter.PURPOSE, java.sql.Date.valueOf(legalDate), "SERVED", "PM-BOOK-006")
        mvc.perform(get("/api/registry/entrances/$entrance/book/access-log").param("purpose", "проверка на КЗЛД").by("login-manager"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.kind == 'HEADCOUNT_READ')].actorName").value("Мария Иванова"))
            .andExpect(jsonPath("$[?(@.kind == 'HEADCOUNT_READ')].bookDate").value(legalDate.toString()))
    }

    @Test
    fun `PM-BOOK-006 a refused caller gets a 403 and an entry with no counts, with a run and without one — and a reader's 404 enters nothing`() {
        val entrance = issued(person("Мария Иванова", "login-manager"))
        val owner = person("Иван Петров", "login-owner")

        mvc.perform(get(report(entrance)).by("login-owner")).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.rule").value("PM-BOOK-006")).andExpect(jsonPath("$.unitsCompared").doesNotExist())
        mvc.perform(get(report(entrance, "2026-06")).by("login-owner")).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.rule").value("PM-BOOK-006"))

        assertThat(entries(entrance).map { it["actor"] }).containsExactly(owner, owner)
        assertThat(entries(entrance).map { it["outcome"] }).containsExactly("REFUSED", "REFUSED")
        assertThat(entries(entrance).map { it["book_date"].toString() }).containsExactlyInAnyOrder(legalDate.toString(), today.toString())

        mvc.perform(get(report(entrance, "2026-06")).by("login-manager")).andExpect(status().isNotFound)
        assertThat(entries(entrance)).hasSize(2)
    }

    @Test
    fun `PM-SEC-004 a headcount read's entry cannot be changed or removed, and with sign-in off the read is on the record as given to nobody`() {
        val entrance = issued(person("Мария Иванова", "login-manager"))

        mvc.perform(get(report(entrance))).andExpect(status().isOk)
        assertThat(entries(entrance).single().values).containsExactly(null, BookHeadcountsAdapter.PURPOSE, java.sql.Date.valueOf(legalDate), "OPEN", "PM-BOOK-006")

        jdbc.update("UPDATE book_access SET purpose = 'нищо' WHERE entrance_id = ?", entrance)
        jdbc.update("DELETE FROM book_access WHERE entrance_id = ?", entrance)
        assertThat(entries(entrance).single()["purpose"]).isEqualTo(BookHeadcountsAdapter.PURPOSE)
    }
}
