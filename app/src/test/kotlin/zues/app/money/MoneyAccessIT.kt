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
    @MockitoBean lateinit var decisions: GaDecisions

    @BeforeEach
    fun everyCitedDecisionPassed() { whenever(decisions.passed(any(), any())).thenReturn(true) }

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
    fun `PM-SEC-002 a unit's open debts are read as its arrears — its owner and the offices, nobody else, and a unit with nothing posted is refused`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Шипка 4", "А", "GA")).entranceId
        val (flat1, flat2) = registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "60.0000"), RegisterUnit("ап. 2", "APARTMENT", idealParts = "40.0000")))
        ownership.assignTitle(entrance, flat1, AssignTitle(person("Иван Петров", "owner-1"), "OWN", validFrom = "2020-01-01"))
        mandate(entrance, "BM", person("Мария Иванова", "manager"))
        mandate(entrance, "CTL", person("Елена Колева", "controller"))
        mandate(entrance, "CSH", person("Стефан Илиев", "cashier"))
        val quietEntrance = registry.registerEntrance(RegisterEntrance("ул. Шипка 6", "А", "GA")).entranceId
        val quiet = registry.registerUnits(quietEntrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "100.0000"))).single()
        ownership.assignTitle(quietEntrance, quiet, AssignTitle(person("Георги Стоянов", "owner-2"), "OWN", validFrom = "2020-01-01"))
        mvc.perform(
            post("/api/money/entrances/$entrance/charge-runs").contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(StoredChargeRunRequest("2026-05", "2026-05-01", lines = listOf(TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)))),
            ),
        ).andExpect(status().isCreated)

        fun openDebts(unit: UUID) = get("/api/money/units/$unit/open-debts").param("asOf", "2026-05-20")

        mvc.perform(openDebts(flat1).by("owner-1")).andExpect(status().isOk).andExpect(jsonPath("$[0].outstandingMinor").value(6_000))
        mvc.perform(openDebts(flat2).by("owner-1")).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-002"))
        for (office in listOf("manager", "controller")) {
            mvc.perform(openDebts(flat2).by(office)).andExpect(status().isOk).andExpect(jsonPath("$[0].outstandingMinor").value(4_000))
        }
        for (nobody in listOf("cashier", "a-login-tied-to-nobody")) {
            mvc.perform(openDebts(flat1).by(nobody)).andExpect(status().isForbidden)
        }
        mvc.perform(openDebts(quiet).by("owner-2")).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-002"))
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

    @Test
    fun `PM-SEC-001 the entrance's accounts are kept by its manager and its board — the controller, the cashier and an owner are refused`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Шипка 11", "А", "GA")).entranceId
        val (flat1) = registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "100.0000")))
        ownership.assignTitle(entrance, flat1, AssignTitle(person("Иван Петров", "k-owner"), "OWN", validFrom = "2020-01-01"))
        mandate(entrance, "BM", person("Мария Иванова", "k-manager"))
        mandate(entrance, "MB", person("Петър Колев", "k-board"))
        mandate(entrance, "CTL", person("Елена Колева", "k-controller"))
        mandate(entrance, "CSH", person("Стефан Илиев", "k-cashier"))
        val run = json.writeValueAsString(StoredChargeRunRequest("2026-05", "2026-05-01", lines = listOf(TariffLineRequest("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000))))
        fun issue() = post("/api/money/entrances/$entrance/charge-runs").contentType(MediaType.APPLICATION_JSON).content(run)
        fun pay(key: String) = post("/api/money/entrances/$entrance/payments").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
            .content("""{"unitId":"$flat1","amountMinor":1000,"valueDate":"2026-05-20","receivedInto":"CASH"}""")
        fun account() = post("/api/money/entrances/$entrance/fund-accounts").contentType(MediaType.APPLICATION_JSON)
            .content("""{"iban":"BG80BNBG96611020345678","purpose":"REPAIR_RENEWAL","holderName":"Мария Иванова","holderKind":"ASSOCIATION"}""")

        for (nobody in listOf("k-controller", "k-cashier", "k-owner", "a-login-tied-to-nobody")) {
            mvc.perform(issue().by(nobody)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
            mvc.perform(pay("k-$nobody").by(nobody)).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))
            mvc.perform(account().by(nobody)).andExpect(status().isForbidden)
        }
        mvc.perform(issue().by("k-manager")).andExpect(status().isCreated)
        mvc.perform(pay("k-1").by("k-board")).andExpect(status().isCreated)
        mvc.perform(account().by("k-board")).andExpect(status().isCreated)
    }

    private fun send(path: String, body: String, key: String = UUID.randomUUID().toString()) =
        post(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)

    private fun issued(request: MockHttpServletRequestBuilder) = json.readTree(mvc.perform(request).andReturn().response.contentAsString)

    private fun rows(table: String, entrance: UUID) = jdbc.queryForObject("SELECT count(*) FROM money.$table WHERE entrance_id = ?", Int::class.java, entrance)

    @Test
    fun `PM-FUND-007 PM-FUND-010 who acts is the sign-in's party — a keeper not holding the account is refused whatever the body says, and the record never takes a body's word`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Шипка 13", "А", "GA")).entranceId
        val holder = person("Мария Иванова", "s-manager")
        val board = person("Петър Колев", "s-board")
        val stranger = ownership.registerParty(RegisterParty("Някой Друг"))
        mandate(entrance, "BM", holder)
        mandate(entrance, "MB", board)
        val base = "/api/money/entrances/$entrance/fund"
        mvc.perform(
            send("/api/money/entrances/$entrance/fund-accounts", """{"iban":"BG53BNBG96611020345679","purpose":"REPAIR_RENEWAL","holderName":"Мария Иванова","holderKind":"ASSOCIATION","holderPartyId":"$holder"}""").by("s-manager"),
        ).andExpect(status().isCreated)

        // a keeper of the accounts who does not hold the fund's, naming the holder in the body: refused, nothing written
        val naming = """{"amountMinor":100,"purpose":"WORKS","decisionId":"GA-2026-1","authorisedBy":"$holder"}"""
        mvc.perform(send("$base/disbursements", naming).by("s-board")).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-FUND-007"))
        assertThat(rows("fund_disbursement", entrance)).isZero()

        // the holder, with a body naming someone else: the record names the holder
        val paying = issued(send("$base/disbursements", naming.replace("$holder", "$stranger")).by("s-manager"))
        val cancelling = issued(send("$base/disbursements", naming.replace("$holder", "$stranger")).by("s-manager"))
        assertThat(paying["authorisedBy"].asText()).isEqualTo("$holder")
        val on = paying["committedOn"].asText()
        mvc.perform(send("$base/disbursements/${paying["id"].asText()}/pay", """{"paidOn":"$on","paidBy":"$holder"}""").by("s-board"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-FUND-007"))
        mvc.perform(send("$base/disbursements/${cancelling["id"].asText()}/cancel", """{"cancelledBy":"$holder","reason":"отпадна"}""").by("s-board"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-FUND-007"))
        mvc.perform(send("$base/disbursements/${paying["id"].asText()}/pay", """{"paidOn":"$on","paidBy":"$stranger"}""").by("s-manager"))
            .andExpect(status().isOk).andExpect(jsonPath("$.paidBy").value("$holder"))
        mvc.perform(send("$base/disbursements/${cancelling["id"].asText()}/cancel", """{"cancelledBy":"$stranger","reason":"отпадна"}""").by("s-manager"))
            .andExpect(status().isOk).andExpect(jsonPath("$.cancelledBy").value("$holder"))

        // a statement is issued by a keeper, who need not be either side: their sign-in goes on the record, outside the basis
        val statement = issued(
            send("$base/handover-statements", """{"handoverOn":"$on","outgoingPartyId":"$holder","incomingPartyId":"$stranger","bankBalanceMinor":-100,"recordedBy":"$stranger"}""").by("s-board"),
        )
        assertThat(statement["recordedBy"].asText()).isEqualTo("$board")
        assertThat(statement["basis"].asText()).doesNotContain("recordedBy").doesNotContain("$board")
        assertThat(jdbc.queryForObject("SELECT recorded_by FROM money.fund_handover_statement WHERE entrance_id = ?", UUID::class.java, entrance)).isEqualTo(board)
        mvc.perform(get("$base/handover-statements/${statement["id"].asText()}").by("s-manager"))
            .andExpect(status().isOk).andExpect(jsonPath("$.recordedBy").value("$board"))           // and the hash still checks out
    }

    @Test
    fun `PM-SEC-002 PM-SEC-001 PM-GOV-008 an owner reads the fund, its accounts and its statements with no party id, the offices with them, an owner in office as the office`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Шипка 15", "А", "GA")).entranceId
        val (flat1, flat2) = registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "60.0000"), RegisterUnit("ап. 2", "APARTMENT", idealParts = "40.0000")))
        ownership.assignTitle(entrance, flat1, AssignTitle(person("Иван Петров", "r-owner"), "OWN", validFrom = "2020-01-01"))
        val holder = person("Мария Иванова", "r-manager")
        val board = person("Петър Колев", "r-board")
        val incoming = person("Елена Колева", "r-controller")
        val boardOwner = person("Анна Петрова", "r-owner-in-office")
        ownership.assignTitle(entrance, flat2, AssignTitle(boardOwner, "OWN", validFrom = "2020-01-01"))
        mandate(entrance, "BM", holder)
        mandate(entrance, "MB", board)
        mandate(entrance, "CTL", incoming)
        mandate(entrance, "MB", boardOwner)
        val base = "/api/money/entrances/$entrance"
        for ((iban, purpose) in listOf("BG26BNBG96611020345680" to "REPAIR_RENEWAL", "BG96BNBG96611020345681" to "OPERATING")) {
            mvc.perform(
                send("$base/fund-accounts", """{"iban":"$iban","purpose":"$purpose","holderName":"Мария Иванова","holderKind":"MANAGER","holderPartyId":"$holder"}""").by("r-manager"),
            ).andExpect(status().isCreated)
        }
        val works = """{"amountMinor":100,"purpose":"WORKS","decisionId":"GA-2026-1"}"""
        val paid = issued(send("$base/fund/disbursements", works).by("r-manager"))
        val on = paid["committedOn"].asText()
        mvc.perform(send("$base/fund/disbursements/${paid["id"].asText()}/pay", """{"paidOn":"$on"}""").by("r-manager")).andExpect(status().isOk)
        val cancelled = issued(send("$base/fund/disbursements", works).by("r-manager"))
        mvc.perform(send("$base/fund/disbursements/${cancelled["id"].asText()}/cancel", """{"reason":"отпадна"}""").by("r-manager")).andExpect(status().isOk)
        val statement = issued(send("$base/fund/handover-statements", """{"handoverOn":"$on","outgoingPartyId":"$holder","incomingPartyId":"$incoming","bankBalanceMinor":-100}""").by("r-board"))
        val reads = listOf("fund", "fund-accounts", "fund/handover-statements", "fund/handover-statements/${statement["id"].asText()}").map { "$base/$it" }
        fun read(path: String, who: String) = mvc.perform(get(path).by(who)).andExpect(status().isOk).andReturn().response.contentAsString

        // the owner: on the whole body, so the basis's text is covered too — every figure, and nobody
        for (path in reads) assertThat(read(path, "r-owner")).describedAs(path).doesNotContain("$holder", "$board", "$incoming", "$boardOwner")
        mvc.perform(get("$base/fund").by("r-owner")).andExpect(jsonPath("$.balanceMinor").value(-100)).andExpect(jsonPath("$.disbursements[0].amountMinor").value(100))
            .andExpect(jsonPath("$.disbursements[0].authorisedBy").doesNotExist()).andExpect(jsonPath("$.disbursements[0].purpose").value("WORKS"))
        mvc.perform(get("$base/fund/handover-statements/${statement["id"].asText()}").by("r-owner"))
            .andExpect(jsonPath("$.statement.closingMinor").value(-100)).andExpect(jsonPath("$.statement.handoverOn").value(on))
            .andExpect(jsonPath("$.basisHash").value(statement["basisHash"].asText())).andExpect(jsonPath("$.basis").doesNotExist())

        // the offices, and the owner who also sits on the board: the ids
        for (office in listOf("r-manager", "r-board", "r-controller", "r-owner-in-office")) {
            assertThat(read(reads[0], office)).describedAs(office).contains("\"authorisedBy\":\"$holder\"", "\"paidBy\":\"$holder\"", "\"cancelledBy\":\"$holder\"")
            assertThat(read(reads[1], office)).describedAs(office).contains("\"holderParty\":\"$holder\"")
            for (path in reads.drop(2)) assertThat(read(path, office)).describedAs(office).contains("\"outgoingPartyId\":\"$holder\"", "\"recordedBy\":\"$board\"", "\"basis\":")
        }

        // the operating account names nobody by id, whoever reads it
        for (reader in listOf("r-owner", "r-manager")) assertThat(read("$base/operating-account", reader)).describedAs(reader).doesNotContain("$holder")
    }
}
