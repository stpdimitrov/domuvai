package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
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
import zues.kernel.toSofiaDate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Fund-account persistence against real PostgreSQL. Proves the table's own guards: one account
 * per entrance per purpose and one IBAN across all entrances, so the fund is distinct from the
 * operating account (PM-FUND-004) and monies are not commingled (PM-FUND-005); and the mandate a
 * fund's account is opened under is stored, read back, and held to that account. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class FundAccountPersistenceIT {

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

        // The IBAN uniqueness is global and these tests share one container, so every registered
        // account needs a distinct number. A static counter guarantees it across the per-method
        // test instances; reusing a literal here would collide across tests, not in production.
        private val ibanSeq = AtomicLong(0)
        private fun freshIban(): String = "BG80BNBG" + "%014d".format(ibanSeq.incrementAndGet())
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun createEntrance(): UUID {
        val response = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON)
                .content("""{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("entranceId").asText())
    }

    private fun register(entranceId: UUID, iban: String, purpose: String) =
        mvc.perform(
            post("/api/money/entrances/$entranceId/fund-accounts").contentType(MediaType.APPLICATION_JSON)
                .content("""{"iban":"$iban","purpose":"$purpose","holderName":"Иван Петров","holderKind":"ASSOCIATION"}"""),
        )

    @Test
    fun `PM-FUND-001 a repair-and-renewal fund account is established and read back`() {
        val entranceId = createEntrance()
        val iban = freshIban()
        register(entranceId, iban, "REPAIR_RENEWAL").andExpect(status().isCreated)

        mvc.perform(get("/api/money/entrances/$entranceId/fund-accounts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].purpose").value("REPAIR_RENEWAL"))
            .andExpect(jsonPath("$[0].iban").value(iban))
    }

    @Test
    fun `PM-FUND-005 a second account of the same purpose for the entrance is refused`() {
        val entranceId = createEntrance()
        register(entranceId, freshIban(), "REPAIR_RENEWAL").andExpect(status().isCreated)
        register(entranceId, freshIban(), "REPAIR_RENEWAL").andExpect(status().isConflict)
    }

    private fun party(): String {
        val response = mvc.perform(
            post("/api/registry/parties").contentType(MediaType.APPLICATION_JSON)
                .content("""{"fullName":"Иван Петров","idType":"EGN","idValue":"7501010010"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(response).get("partyId").asText()
    }

    /** A mandate in force, as the table holds it: recording one through the api is the administrator's, and has its own tests. */
    private fun mandate(entranceId: UUID, partyId: String, body: String = "BM"): UUID = UUID.randomUUID().also {
        val today = LocalDate.parse(toSofiaDate(Instant.now()))
        jdbc.update(
            "INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (?, ?, ?, ?, ?, ?)",
            it, entranceId, body, UUID.fromString(partyId), today.minusMonths(1), today.plusMonths(11),
        )
    }

    private fun registerManagers(entranceId: UUID, partyId: String) = mvc.perform(
        post("/api/money/entrances/$entranceId/fund-accounts").contentType(MediaType.APPLICATION_JSON).content(
            """{"iban":"${freshIban()}","purpose":"REPAIR_RENEWAL","holderName":"Иван Петров","holderKind":"MANAGER","holderPartyId":"$partyId"}""",
        ),
    )

    @Test
    fun `PM-FUND-004 the holder and the mandate are stored and read back`() {
        val entranceId = createEntrance()
        val partyId = party()
        val mandate = mandate(entranceId, partyId)
        registerManagers(entranceId, partyId).andExpect(status().isCreated)

        mvc.perform(get("/api/money/entrances/$entranceId/fund-accounts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].holderParty").value(partyId))
            .andExpect(jsonPath("$[0].mandateId").value(mandate.toString()))
    }

    @Test
    fun `PM-FUND-004 a fund's manager standing on no mandate is a 400 and nothing is stored`() {
        val entranceId = createEntrance()
        val partyId = party()
        val elsewhere = createEntrance()
        mandate(elsewhere, partyId)
        mandate(entranceId, partyId, "CTL")
        registerManagers(entranceId, partyId)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("PM-FUND-004")))
        mvc.perform(get("/api/money/entrances/$entranceId/fund-accounts")).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `PM-FUND-004 an account stored before the mandate was kept reads as having none`() {
        val entranceId = createEntrance()
        register(entranceId, freshIban(), "REPAIR_RENEWAL").andExpect(status().isCreated)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM money.fund_account WHERE entrance_id = ? AND mandate_id IS NULL", Long::class.java, entranceId)).isEqualTo(1)
        mvc.perform(get("/api/money/entrances/$entranceId/fund-accounts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].mandateId").doesNotExist())
    }

    @Test
    fun `PM-FUND-004 the table holds a mandate only on a manager's fund account`() {
        val entranceId = createEntrance()
        val partyId = party()
        val mandate = mandate(entranceId, partyId)
        register(entranceId, freshIban(), "OPERATING").andExpect(status().isCreated)
        assertThatThrownBy {
            jdbc.update("UPDATE money.fund_account SET mandate_id = ? WHERE entrance_id = ?", mandate, entranceId)
        }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("fund_account_mandate_is_the_managers_of_the_fund")
    }

    @Test
    fun `PM-FUND-004 the fund cannot reuse the operating account's IBAN, but coexists on its own`() {
        val entranceId = createEntrance()
        val operating = freshIban()
        register(entranceId, operating, "OPERATING").andExpect(status().isCreated)
        register(entranceId, operating, "REPAIR_RENEWAL").andExpect(status().isConflict)   // same IBAN — commingling
        register(entranceId, freshIban(), "REPAIR_RENEWAL").andExpect(status().isCreated)   // distinct account, fine
    }
}
