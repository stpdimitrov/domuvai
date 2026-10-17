package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
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
 * Who enters in the book who lives in a unit, against real PostgreSQL — through the sign-in, the titles and mandates that
 * give roles, and the policy (PM-BOOK-005, PM-GOV-008, ADR-002). Docker-gated — runs in CI.
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
}
