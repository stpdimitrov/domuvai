package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.app.identity_org.Logins
import zues.kernel.toSofiaDate
import zues.law.constantOn
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The Book of the Condominium against real PostgreSQL: assemble it from a unit with an owner,
 * household and a non-use period, and from a unit with none, and check completeness both ways
 * (PM-BOOK-002) and that the electronic book reads back (PM-BOOK-001). Docker-gated — skips
 * locally, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class BookPersistenceIT {

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
    @Autowired lateinit var registry: RegistryService
    @Autowired lateinit var ownership: OwnershipService
    @MockitoSpyBean lateinit var book: BookService          // the real one; one test makes it fail after the entry is written
    @Autowired lateinit var declarations: DeclarationService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var logins: Logins

    private val realm = "https://id.example.test/realms/${UUID.randomUUID()}"

    /** A registered party with a login of its own, tied to it. */
    private fun person(name: String, subject: String): UUID = ownership.registerParty(RegisterParty(name)).also { logins.tie(realm, subject, it) }

    /** A mandate as the assembly's protocol would give it; nothing writes one through the api yet. */
    private fun mandate(entrance: UUID, body: String, party: UUID, from: LocalDate, to: LocalDate, succeededAt: LocalDate? = null) {
        jdbc.update(
            "INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to, succeeded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), entrance, body, party, from, to, succeededAt,
        )
    }

    /** The request as this login's: a token the api would have accepted, naming the subject under the test's issuer. */
    private fun MockHttpServletRequestBuilder.by(subject: String, more: Map<String, Any> = emptyMap()) =
        with(jwt().jwt { token -> token.subject(subject).issuer(realm).also { more.forEach { (name, value) -> it.claim(name, value) } } })

    private val today: LocalDate get() = LocalDate.parse(toSofiaDate(Instant.now()))

    @Test
    fun `PM-BOOK-003 PM-BOOK-004 a filed declaration persists with its template version and clears what was owed`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Раковски 3", "А", "GA")).entranceId
        val unit = registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "100.0000"))).single()
        val party = ownership.registerParty(RegisterParty("Мария Георгиева"))
        ownership.assignTitle(entrance, unit, AssignTitle(party, "OWN", validFrom = "2020-01-01"))
        val dayAfterDeadline = LocalDate.parse("2020-01-17")         // acquired 1 Jan 2020; the window closed on the 16th
        assertThat(declarations.overdue(entrance, dayAfterDeadline).single().partyName).isEqualTo("Мария Георгиева")

        val filed = declarations.file(entrance, unit, party, "ACQUISITION")
        assertThat(filed.templateVersion).isEqualTo(constantOn("BOOK_DECLARATION_TEMPLATE", filed.filedOn.toString()).value)
        assertThat(declarations.overdue(entrance, filed.filedOn)).isEmpty()         // filed: nothing owed now
        assertThat(declarations.overdue(entrance, dayAfterDeadline)).hasSize(1)     // and what was owed then still holds
    }

    @Test
    fun `PM-BOOK-001 002 the book assembles a unit's record and marks completeness`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Раковски 1", "А", "GA")).entranceId
        val unitIds = registry.registerUnits(
            entrance,
            listOf(
                RegisterUnit("ап. 1", "APARTMENT", idealParts = "60.0000"),
                RegisterUnit("ап. 2", "APARTMENT", idealParts = "40.0000"),
            ),
        )
        val unit1 = unitIds[0]
        val party = ownership.registerParty(RegisterParty("Иван Петров"))
        ownership.assignTitle(entrance, unit1, AssignTitle(party, "OWN", validFrom = "2026-01-01"))
        registry.registerHousehold(
            entrance, unit1, listOf(RegisterMember(validFrom = "2026-01-01"), RegisterMember(validFrom = "2026-01-01")),
        )
        registry.registerAbsence(entrance, unit1, listOf(RegisterAbsence("2026-01-05", "2026-02-05")))

        val theBook = book.forEntrance(entrance, LocalDate.parse("2026-06-01"))

        assertThat(theBook.units).hasSize(2)
        val e1 = theBook.units.first { it.designation == "ап. 1" }
        assertThat(e1.idealParts).isEqualTo("60.0000")
        assertThat(e1.parties.first { it.name == "Иван Петров" }.role).isEqualTo("OWN")
        assertThat(e1.householdCount).isEqualTo(2)
        assertThat(e1.nonUse).hasSize(1)
        assertThat(e1.complete).isTrue()                       // PM-BOOK-002: ideal parts + an owner named

        val e2 = theBook.units.first { it.designation == "ап. 2" }
        assertThat(e2.complete).isFalse()                      // no owner named
        assertThat(theBook.complete).isFalse()                 // not every unit is complete

        // PM-BOOK-001 — the electronic book reads back over HTTP, to its manager and on the record
        mandate(entrance, "BM", person("Мария Иванова", "login-manager"), today.minusMonths(1), today.plusMonths(11))
        mvc.perform(get("/api/registry/entrances/$entrance/book").param("on", "2026-06-01").param("purpose", "проверка").by("login-manager"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.units.length()").value(2))
    }

    @Test
    fun `PM-BOOK-006 owner A asking for the book is a 403 and an entry, the manager in office reads it, and one no longer in office does not`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Раковски 9", "А", "GA")).entranceId
        val other = registry.registerEntrance(RegisterEntrance("ул. Раковски 9", "Б", "GA")).entranceId
        val (flat1, flat2) = registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "60.0000"), RegisterUnit("ап. 2", "APARTMENT", idealParts = "40.0000")))
        val ownerA = person("Иван Петров", "login-owner-a")
        val ownerB = person("Георги Стоянов", "login-owner-b")
        ownership.assignTitle(entrance, flat1, AssignTitle(ownerA, "OWN", validFrom = "2020-01-01"))
        ownership.assignTitle(entrance, flat2, AssignTitle(ownerB, "OWN", validFrom = "2020-01-01"))
        registry.registerHousehold(entrance, flat2, listOf(RegisterMember(validFrom = "2020-01-01")))
        val manager = person("Мария Иванова", "login-manager")
        val former = person("Петър Димов", "login-former")
        val controller = person("Елена Колева", "login-controller")
        val boardMember = person("Радка Тонева", "login-board")
        val cashier = person("Стефан Илиев", "login-cashier")
        val took = today.minusMonths(1)                                                           // the day Maria took over from Petar
        mandate(entrance, "BM", former, took.minusYears(2), took, succeededAt = took)
        mandate(entrance, "BM", manager, took, took.plusYears(2))
        mandate(entrance, "CTL", controller, took, took.plusYears(2))
        mandate(entrance, "MB", boardMember, took, took.plusYears(2))
        mandate(entrance, "CSH", cashier, took, took.plusYears(2))
        mandate(other, "BM", person("Николай Василев", "login-next-door"), took, took.plusYears(2))
        val url = "/api/registry/entrances/$entrance/book"
        fun entries(outcome: String) = jdbc.queryForObject("SELECT count(*) FROM book_access WHERE entrance_id = ? AND outcome = ?", Long::class.java, entrance, outcome)

        // Owner A wants owner B's household: a 403, nothing of the book, and an entry of the refusal that names him and the rule.
        mvc.perform(get(url).param("purpose", "кой живее в ап. 2").by("login-owner-a"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-BOOK-006")).andExpect(jsonPath("$.units").doesNotExist())
        val refusal = jdbc.queryForMap("SELECT actor, purpose, kind, outcome, rule_id, login_issuer, login_subject FROM book_access WHERE entrance_id = ?", entrance)
        assertThat(refusal.values).containsExactly(ownerA, "кой живее в ап. 2", "BOOK_READ", "REFUSED", "PM-BOOK-006", realm, "login-owner-a")
        // … and saying he is the manager — as a parameter, as a claim — changes nothing: the reader is the login's party.
        mvc.perform(get(url).param("purpose", "проверка").param("actor", manager.toString()).by("login-owner-a", mapOf("actor" to manager.toString(), "partyId" to manager.toString())))
            .andExpect(status().isForbidden)
        // … and with no purpose, or about an entrance nobody registered, he is told the same and nothing more.
        mvc.perform(get(url).by("login-owner-a")).andExpect(status().isForbidden)
        mvc.perform(get("/api/registry/entrances/${UUID.randomUUID()}/book").param("purpose", "проверка").by("login-owner-a")).andExpect(status().isForbidden)

        // The manager, the board and the controller in office today read it; the cashier, the manager next door and the one before her do not —
        // not even the book as of a day in his own term: who may read is decided today.
        mvc.perform(get(url).param("purpose", "годишен отчет").by("login-manager")).andExpect(status().isOk).andExpect(jsonPath("$.units.length()").value(2))
        mvc.perform(get(url).param("purpose", "заседание на съвета").by("login-board")).andExpect(status().isOk)
        mvc.perform(get(url).param("purpose", "годишна проверка").by("login-controller")).andExpect(status().isOk)
        mvc.perform(get(url).param("purpose", "проверка").by("login-cashier")).andExpect(status().isForbidden)
        mvc.perform(get(url).param("purpose", "проверка").by("login-next-door")).andExpect(status().isForbidden)
        mvc.perform(get(url).param("purpose", "проверка").by("login-former")).andExpect(status().isForbidden)
        mvc.perform(get(url).param("on", took.minusYears(1).toString()).param("purpose", "проверка").by("login-former")).andExpect(status().isForbidden)
        // A login tied to no party, and nobody at all (sign-in is off in this test's application): refused, and still on the record.
        mvc.perform(get(url).param("purpose", "проверка").by("login-of-nobody")).andExpect(status().isForbidden)
        mvc.perform(get(url).param("purpose", "проверка").param("actor", manager.toString())).andExpect(status().isForbidden)
        assertThat(entries("SERVED")).isEqualTo(3)
        assertThat(entries("REFUSED")).isEqualTo(9)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM book_access WHERE entrance_id = ? AND purpose = ?", Long::class.java, entrance, NO_PURPOSE_GIVEN)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM book_access WHERE entrance_id = ? AND actor IS NULL AND outcome = 'REFUSED'", Long::class.java, entrance)).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM book_access WHERE entrance_id = ? AND actor = ? AND outcome = 'SERVED'", Long::class.java, entrance, manager)).isEqualTo(1)

        // The log of who read is the manager's to export, not owner A's — and it shows the refusals.
        mvc.perform(get("$url/access-log").param("purpose", "кой ме е гледал").by("login-owner-a")).andExpect(status().isForbidden)
        mvc.perform(get("$url/access-log").param("purpose", "проверка на КЗЛД").by("login-manager"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(14))
            .andExpect(jsonPath("$[0].outcome").value("REFUSED")).andExpect(jsonPath("$[0].actorName").value("Иван Петров")).andExpect(jsonPath("$[0].ruleId").value("PM-BOOK-006"))
            .andExpect(jsonPath("$[0].loginIssuer").value(realm)).andExpect(jsonPath("$[0].loginSubject").value("login-owner-a"))
            .andExpect(jsonPath("$[3].outcome").value("SERVED")).andExpect(jsonPath("$[3].actorName").value("Мария Иванова"))
            .andExpect(jsonPath("$[13].kind").value("LOG_EXPORT")).andExpect(jsonPath("$[13].outcome").value("SERVED"))

        // The table's own word on it: a served read is a party's, a refusal cites its rule, a login is whole.
        val row = "INSERT INTO book_access (id, entrance_id, actor, purpose, kind, book_date, at, outcome, rule_id, login_issuer, login_subject) VALUES (gen_random_uuid(), ?, ?, 'проверка', 'BOOK_READ', current_date, now(), ?, ?, ?, ?)"
        for (bad in listOf(
            arrayOf(entrance, null, "SERVED", "PM-BOOK-006", realm, "x"), arrayOf(entrance, manager, "REFUSED", null, realm, "x"),
            arrayOf(entrance, manager, "SERVED", "PM-BOOK-006", realm, null), arrayOf(entrance, manager, "PEEKED", "PM-BOOK-006", realm, "x"),
            arrayOf(entrance, manager, "SERVED", "any rule", realm, "x"),
        )) assertThatThrownBy { jdbc.update(row, *bad) }.describedAs(bad.joinToString()).isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `PM-BOOK-007 a read of the book writes its entry, a malformed one writes none, and an entry is never changed or removed`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Раковски 7", "А", "GA")).entranceId
        registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "100.0000")))
        val manager = person("Мария Иванова", "login-manager-7")
        mandate(entrance, "BM", manager, today.minusMonths(1), today.plusMonths(11))
        val url = "/api/registry/entrances/$entrance/book"
        fun entries() = jdbc.queryForObject("SELECT count(*) FROM book_access WHERE entrance_id = ?", Long::class.java, entrance)
        fun read(purpose: String) = get(url).param("purpose", purpose).by("login-manager-7")

        mvc.perform(get(url).by("login-manager-7")).andExpect(status().isBadRequest)                              // no purpose
        mvc.perform(read(" ")).andExpect(status().isBadRequest)
        assertThat(entries()).isEqualTo(0)

        mvc.perform(read("годишен отчет").param("on", "2026-06-01")).andExpect(status().isOk).andExpect(jsonPath("$.units.length()").value(1))
        mvc.perform(read("справка за собственик")).andExpect(status().isOk)
        assertThat(entries()).isEqualTo(2)

        // The entry and the book stand or fall together: a read that fails after its entry was written leaves none.
        doThrow(IllegalStateException("the book could not be assembled")).whenever(book).forEntrance(eq(entrance), any())
        assertThatThrownBy { mvc.perform(read("неуспешно четене")) }.hasRootCauseInstanceOf(IllegalStateException::class.java)
        reset(book)
        assertThat(entries()).isEqualTo(2)

        jdbc.update("UPDATE book_access SET purpose = 'друго', outcome = 'REFUSED' WHERE entrance_id = ?", entrance)   // ignored
        jdbc.update("DELETE FROM book_access WHERE entrance_id = ?", entrance)                                    // ignored
        assertThatThrownBy { jdbc.execute("TRUNCATE book_access") }.isInstanceOf(DataIntegrityViolationException::class.java)   // refused
        mvc.perform(get("$url/access-log").param("purpose", "проверка на КЗЛД").by("login-manager-7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))                                                           // the export is an entry too
            .andExpect(jsonPath("$[0].kind").value("BOOK_READ"))
            .andExpect(jsonPath("$[0].purpose").value("годишен отчет"))
            .andExpect(jsonPath("$[0].outcome").value("SERVED"))
            .andExpect(jsonPath("$[0].ruleId").value("PM-BOOK-006"))
            .andExpect(jsonPath("$[0].loginSubject").value("login-manager-7"))
            .andExpect(jsonPath("$[0].bookDate").value("2026-06-01"))
            .andExpect(jsonPath("$[0].actorName").value("Мария Иванова"))
            .andExpect(jsonPath("$[1].purpose").value("справка за собственик"))
            .andExpect(jsonPath("$[2].kind").value("LOG_EXPORT"))
            .andExpect(jsonPath("$[2].bookDate").doesNotExist())

        // The table's own refusals, one at a time: a read with no date, an export with one, a blank purpose, one too long, an unknown kind.
        val row = "INSERT INTO book_access (id, entrance_id, actor, purpose, kind, book_date, at) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?::date, now())"
        for ((purpose, kind, date) in listOf(
            Triple("проверка", "BOOK_READ", null), Triple("проверка", "LOG_EXPORT", "2026-06-01"), Triple(" ", "BOOK_READ", "2026-06-01"),
            Triple("проверка", "PEEK", "2026-06-01"), Triple("о".repeat(501), "BOOK_READ", "2026-06-01"),
        )) {
            assertThatThrownBy { jdbc.update(row, entrance, manager, purpose, kind, date) }.isInstanceOf(DataIntegrityViolationException::class.java)
        }
        jdbc.update(row, entrance, manager, "проверка", "BOOK_READ", "2026-06-01")                                // and a sound one is taken
        assertThat(entries()).isEqualTo(4)
    }
}
