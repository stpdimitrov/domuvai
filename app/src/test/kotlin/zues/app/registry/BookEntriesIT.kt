package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.app.identity_org.Logins
import zues.kernel.toSofiaDate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Who enters the book — an entrance's units, a unit's titles, who lives in it — against real PostgreSQL: through the
 * sign-in, the titles and mandates that give roles, and the policy (PM-BOOK-005, PM-GOV-008, ADR-002); and the database's
 * own refusal of an entry for a unit of another entrance (PM-ORG-001, #170). Docker-gated — runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class BookEntriesIT {

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

    private fun entrance(address: String): Pair<UUID, UUID> {
        val entrance = registry.registerEntrance(RegisterEntrance(address, "А", "GA")).entranceId
        return entrance to registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "100.0000"))).single()
    }

    private fun send(path: String, by: String, body: String): ResultActions =
        mvc.perform(post(path).with(jwt().jwt { it.subject(by).issuer(realm) }).contentType(MediaType.APPLICATION_JSON).content(body))

    /** The five entries, each as `by` makes it in this unit: a resident, an animal, an absence, the day one of each left. */
    private fun everyEntry(entrance: UUID, unit: UUID, by: String): List<ResultActions> {
        val base = "/api/registry/entrances/$entrance/units/$unit"
        val member = registry.registerHousehold(entrance, unit, listOf(RegisterMember(validFrom = "2026-01-10"))).single()
        val animal = registry.registerAnimals(entrance, unit, listOf(RegisterAnimal("cat", validFrom = "2026-01-10"))).single()
        return listOf(
            send("$base/household", by, """{"members":[{"isChildUnder6":true,"validFrom":"2026-02-01"}]}"""),
            send("$base/animals", by, """{"animals":[{"species":"dog","vetPassportNo":"VP-7","validFrom":"2026-02-01"}]}"""),
            send("$base/absences", by, """{"absences":[{"absentFrom":"2026-06-01","absentTo":"2026-08-01"}]}"""),
            send("$base/household/$member/end", by, """{"on":"2026-05-01"}"""),
            send("$base/animals/$animal/end", by, """{"on":"2026-05-01"}"""),
        )
    }

    /** What the book says lives in the unit: residents, animals and absences, and how many of them have left. */
    private fun book(unit: UUID): List<Int> = listOf(
        "SELECT count(*) FROM registry.household_member WHERE unit_id = ?",
        "SELECT count(*) FROM registry.animal WHERE unit_id = ?",
        "SELECT count(*) FROM registry.absence_declaration WHERE unit_id = ?",
        "SELECT count(*) FROM registry.household_member WHERE unit_id = ? AND valid_to IS NOT NULL",
        "SELECT count(*) FROM registry.animal WHERE unit_id = ? AND valid_to IS NOT NULL",
    ).map { jdbc.queryForObject(it, Int::class.java, unit)!! }

    @Test
    fun `PM-BOOK-005 the manager and the board enter who lives in a unit — its household, animals, absences and the day one left`() {
        val (entrance, flat) = entrance("ул. Витоша 6")
        person("Мария Иванова", "e-manager").also { mandate(entrance, "BM", it) }
        person("Радка Тонева", "e-board").also { mandate(entrance, "MB", it) }

        for (by in listOf("e-manager", "e-board")) {
            val before = book(flat)
            val (household, animals, absences, memberLeft, animalLeft) = everyEntry(entrance, flat, by)
            household.andExpect(status().isCreated).andExpect(jsonPath("$.memberIds.length()").value(1))
            animals.andExpect(status().isCreated).andExpect(jsonPath("$.animalIds.length()").value(1))
            absences.andExpect(status().isCreated).andExpect(jsonPath("$.absenceIds.length()").value(1))
            memberLeft.andExpect(status().isOk).andExpect(jsonPath("$.validTo").value("2026-05-01"))
            animalLeft.andExpect(status().isOk).andExpect(jsonPath("$.validTo").value("2026-05-01"))
            // two residents and two animals each round (one made in-process to be ended), one absence, one of each left
            assertThat(book(flat)).describedAs(by).isEqualTo(listOf(before[0] + 2, before[1] + 2, before[2] + 1, before[3] + 1, before[4] + 1))
        }
        send("/api/registry/entrances/$entrance/units/${UUID.randomUUID()}/household", "e-manager", """{"members":[{}]}""")
            .andExpect(status().isNotFound)                                                                    // the manager may be told

        // a unit of another entrance, asked through this one: the manager here enters nothing there
        val (_, elsewhere) = entrance("ул. Витоша 12")
        val before = book(elsewhere)
        send("/api/registry/entrances/$entrance/units/$elsewhere/household", "e-manager", """{"members":[{}]}""").andExpect(status().isNotFound)
        send("/api/registry/entrances/$entrance/units/$elsewhere/absences", "e-board", """{"absences":[{"absentFrom":"2026-06-01","absentTo":"2026-08-01"}]}""")
            .andExpect(status().isNotFound)
        assertThat(book(elsewhere)).isEqualTo(before)
    }

    @Test
    fun `PM-BOOK-005 the controller, the cashier, the unit's owner and user, another entrance's manager and a login tied to nobody enter nothing — each a 403 naming the rule`() {
        val (entrance, flat) = entrance("ул. Витоша 8")
        val (elsewhere) = entrance("ул. Витоша 10")
        person("Елена Колева", "x-controller").also { mandate(entrance, "CTL", it) }
        person("Стефан Илиев", "x-cashier").also { mandate(entrance, "CSH", it) }
        person("Иван Петров", "x-owner").also { ownership.assignTitle(entrance, flat, AssignTitle(it, "OWN", validFrom = "2020-01-01")) }
        person("Анна Петрова", "x-user").also { ownership.assignTitle(entrance, flat, AssignTitle(it, "USR", validFrom = "2020-01-01")) }
        person("Мария Иванова", "x-other-manager").also { mandate(elsewhere, "BM", it) }

        for (by in listOf("x-controller", "x-cashier", "x-owner", "x-user", "x-other-manager", "x-a-login-tied-to-nobody")) {
            val before = book(flat)
            everyEntry(entrance, flat, by).forEach { it.andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-BOOK-005")) }
            assertThat(book(flat)).describedAs(by).isEqualTo(listOf(before[0] + 1, before[1] + 1, before[2], before[3], before[4]))   // only what was made in-process
        }
        send("/api/registry/entrances/$entrance/units/${UUID.randomUUID()}/household", "x-owner", """{"members":[{}]}""")
            .andExpect(status().isForbidden)                                                                   // a unit not there: told nothing
        send("/api/registry/entrances/$entrance/units/$flat/household/${UUID.randomUUID()}/end", "x-owner", """{"on":"01.05.2026"}""")
            .andExpect(status().isForbidden)                                                                   // refused before the day is read
    }

    private fun titles(unit: UUID): Int = jdbc.queryForObject("SELECT count(*) FROM registry.title WHERE unit_id = ?", Int::class.java, unit)!!

    private fun units(entrance: UUID): Int = jdbc.queryForObject("SELECT count(*) FROM registry.unit WHERE entrance_id = ?", Int::class.java, entrance)!!

    private fun title(entrance: UUID, unit: UUID, by: String, party: UUID, role: String = "OWN"): ResultActions =
        send("/api/registry/entrances/$entrance/units/$unit/titles", by, """{"partyId":"$party","titleRole":"$role","share":"1","validFrom":"2020-01-01"}""")

    private fun unitsBy(entrance: UUID, by: String): ResultActions =
        send("/api/registry/entrances/$entrance/units", by, """{"units":[{"designation":"ап. 1","unitType":"APARTMENT","idealParts":"55.0000"},{"designation":"ап. 2","unitType":"APARTMENT","idealParts":"45.0000"}]}""")

    @Test
    fun `PM-BOOK-005 the manager and the board enter an entrance's units and a unit's owners and users`() {
        val forManager = registry.registerEntrance(RegisterEntrance("ул. Витоша 14", "А", "GA")).entranceId
        val forBoard = registry.registerEntrance(RegisterEntrance("ул. Витоша 16", "А", "GA")).entranceId
        person("Мария Иванова", "t-manager").also { mandate(forManager, "BM", it) }
        person("Радка Тонева", "t-board").also { mandate(forBoard, "MB", it) }
        val owner = person("Иван Петров", "t-owner")
        val user = person("Анна Петрова", "t-user")

        for ((entrance, by) in listOf(forManager to "t-manager", forBoard to "t-board")) {
            unitsBy(entrance, by).andExpect(status().isCreated).andExpect(jsonPath("$.unitIds.length()").value(2))
            val flat = registry.listUnits(entrance).first { it.designation == "ап. 1" }.id
            title(entrance, flat, by, owner).andExpect(status().isCreated)
            title(entrance, flat, by, user, role = "USR").andExpect(status().isCreated)
            assertThat(units(entrance) to titles(flat)).describedAs(by).isEqualTo(2 to 2)
        }
        // a unit of another entrance, asked through this one: the manager here records no title there
        val (_, elsewhere) = entrance("ул. Витоша 18")
        title(forManager, elsewhere, "t-manager", owner).andExpect(status().isNotFound)
        assertThat(titles(elsewhere)).isZero()
    }

    @Test
    fun `PM-BOOK-005 nobody makes themself an owner — the owner and user of a unit, the controller, the cashier, another entrance's manager and a login tied to nobody record no title and register no unit`() {
        val (entrance, flat) = entrance("ул. Витоша 20")
        val (elsewhere) = entrance("ул. Витоша 22")
        val empty = registry.registerEntrance(RegisterEntrance("ул. Витоша 24", "А", "GA")).entranceId
        person("Елена Колева", "n-controller").also { mandate(entrance, "CTL", it); mandate(empty, "CTL", it) }
        person("Стефан Илиев", "n-cashier").also { mandate(entrance, "CSH", it); mandate(empty, "CSH", it) }
        val owner = person("Иван Петров", "n-owner").also { ownership.assignTitle(entrance, flat, AssignTitle(it, "OWN", validFrom = "2020-01-01")) }
        person("Анна Петрова", "n-user").also { ownership.assignTitle(entrance, flat, AssignTitle(it, "USR", validFrom = "2020-01-01")) }
        person("Мария Иванова", "n-other-manager").also { mandate(elsewhere, "BM", it) }
        val stranger = person("Петя Колева", "n-stranger")

        for (by in listOf("n-owner", "n-user", "n-controller", "n-cashier", "n-other-manager", "n-stranger", "n-a-login-tied-to-nobody")) {
            val party = if (by == "n-owner") owner else stranger         // the owner adding themself again, or a stranger made owner
            title(entrance, flat, by, party).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-BOOK-005"))
            unitsBy(empty, by).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-BOOK-005"))
            assertThat(titles(flat) to units(empty)).describedAs(by).isEqualTo(2 to 0)
        }
        title(entrance, UUID.randomUUID(), "n-owner", owner).andExpect(status().isForbidden)             // a unit not there: told nothing
        title(entrance, flat, "n-owner", owner).andExpect(status().isForbidden)                           // whatever the body says
    }

    @Test
    fun `PM-ORG-001 what is entered for a unit belongs to the entrance the unit lies in — the database refuses a row naming another`() {
        val (entrance, flat) = entrance("ул. Витоша 26")
        val (elsewhere) = entrance("ул. Витоша 28")
        val party = person("Иван Петров", "o-owner")
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO registry.title (id, entrance_id, unit_id, party_id, title_role, share, valid_from) VALUES (?, ?, ?, ?, 'OWN', 1, ?)",
                UUID.randomUUID(), elsewhere, flat, party, today,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("title_unit_in_entrance")
        assertThatThrownBy {
            jdbc.update("INSERT INTO registry.household_member (id, entrance_id, unit_id, valid_from) VALUES (?, ?, ?, ?)", UUID.randomUUID(), elsewhere, flat, today)
        }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("household_member_unit_in_entrance")
        jdbc.update("INSERT INTO registry.household_member (id, entrance_id, unit_id, valid_from) VALUES (?, ?, ?, ?)", UUID.randomUUID(), entrance, flat, today)
        // every table that names a unit and an entrance holds them together
        val held = jdbc.queryForList(
            """SELECT c.conrelid::regclass::text FROM pg_constraint c
               WHERE c.contype = 'f' AND c.confrelid = 'registry.unit'::regclass AND array_length(c.conkey, 1) = 2""",
            String::class.java,
        )
        val naming = jdbc.queryForList(
            """SELECT table_name FROM information_schema.columns WHERE table_schema = 'registry' AND column_name IN ('unit_id', 'entrance_id')
               GROUP BY table_name HAVING count(*) = 2""",
            String::class.java,
        )
        assertThat(naming).contains("title", "household_member", "animal", "absence_declaration", "book_declaration")
        assertThat(held.map { it.removePrefix("registry.") }).containsExactlyInAnyOrderElementsOf(naming)
    }
}
