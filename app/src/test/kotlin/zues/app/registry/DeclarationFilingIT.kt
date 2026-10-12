package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
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
 * Who files a declaration for the book, against real PostgreSQL — through the sign-in, the titles and mandates that give
 * roles, and the policy (PM-BOOK-003, PM-BOOK-005, ADR-002). Docker-gated — runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class DeclarationFilingIT {

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

    private fun file(entrance: UUID, unit: UUID, by: String, named: UUID? = null): ResultActions = mvc.perform(
        post("/api/registry/entrances/$entrance/book/declarations").with(jwt().jwt { it.subject(by).issuer(realm) })
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"unitId":"$unit"${named?.let { ""","partyId":"$it"""" } ?: ""}}"""),
    )

    private fun filed(id: String): Pair<UUID, UUID?> = jdbc.queryForMap("SELECT party_id, filed_by FROM registry.book_declaration WHERE id = ?::uuid", id)
        .let { it["party_id"] as UUID to it["filed_by"] as UUID? }

    private fun id(result: ResultActions) = json.readTree(result.andExpect(status().isCreated).andReturn().response.contentAsString).get("id").asText()

    @Test
    fun `PM-BOOK-003 an owner or a user files their own declaration for a unit they hold, and nobody else's`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Витоша 2", "А", "GA")).entranceId
        val (flat1, flat2) = registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "60.0000"), RegisterUnit("ап. 2", "APARTMENT", idealParts = "40.0000")))
        val owner = person("Иван Петров", "owner")
        val other = person("Георги Стоянов", "other-owner")
        val user = person("Анна Петрова", "user")
        ownership.assignTitle(entrance, flat1, AssignTitle(owner, "OWN", validFrom = "2020-01-01"))
        ownership.assignTitle(entrance, flat2, AssignTitle(other, "OWN", validFrom = "2020-01-01"))
        ownership.assignTitle(entrance, flat1, AssignTitle(user, "USR", validFrom = "2020-01-01"))

        assertThat(filed(id(file(entrance, flat1, "owner")))).isEqualTo(owner to owner)          // their own, filed by themselves
        assertThat(filed(id(file(entrance, flat1, "user")))).isEqualTo(user to user)
        file(entrance, flat2, "owner").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-BOOK-003"))   // a unit they do not hold
        file(entrance, flat1, "owner", named = other).andExpect(status().isForbidden)            // in somebody else's name
        file(entrance, UUID.randomUUID(), "owner").andExpect(status().isForbidden)               // a unit not there: told nothing
        file(entrance, flat1, "a-login-tied-to-nobody").andExpect(status().isForbidden)
    }

    @Test
    fun `PM-BOOK-005 the manager or the board record a declaration handed in, in the name of an owner or user of the unit — the controller and the cashier do not`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Витоша 4", "А", "GA")).entranceId
        val (flat1) = registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "100.0000")))
        val owner = person("Иван Петров", "r-owner")
        ownership.assignTitle(entrance, flat1, AssignTitle(owner, "OWN", validFrom = "2020-01-01"))
        val manager = person("Мария Иванова", "r-manager").also { mandate(entrance, "BM", it) }
        val board = person("Радка Тонева", "r-board").also { mandate(entrance, "MB", it) }
        person("Елена Колева", "r-controller").also { mandate(entrance, "CTL", it) }
        person("Стефан Илиев", "r-cashier").also { mandate(entrance, "CSH", it) }

        assertThat(filed(id(file(entrance, flat1, "r-manager", named = owner)))).isEqualTo(owner to manager)   // the filer is on the record
        assertThat(filed(id(file(entrance, flat1, "r-board", named = owner)))).isEqualTo(owner to board)
        file(entrance, flat1, "r-controller", named = owner).andExpect(status().isForbidden)
        file(entrance, flat1, "r-cashier", named = owner).andExpect(status().isForbidden)
        file(entrance, UUID.randomUUID(), "r-manager", named = owner).andExpect(status().isNotFound)          // the manager may be told
        file(entrance, flat1, "r-manager", named = person("Петя Колева", "r-nobody-here")).andExpect(status().isNotFound)   // holds no title on it
        file(entrance, flat1, "r-manager", named = UUID.randomUUID()).andExpect(status().isNotFound)           // the same answer for no such party
        file(entrance, flat1, "r-manager").andExpect(status().isForbidden)                                     // no office is a declarant of its own
    }
}
