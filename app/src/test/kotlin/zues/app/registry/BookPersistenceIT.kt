package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.law.constantOn
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
    @Autowired lateinit var book: BookService
    @Autowired lateinit var declarations: DeclarationService
    @Autowired lateinit var jdbc: JdbcTemplate

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

        // PM-BOOK-001 — the electronic book reads back over HTTP, to a reader on the record
        mvc.perform(get("/api/registry/entrances/$entrance/book").param("on", "2026-06-01").param("actor", party.toString()).param("purpose", "проверка"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.units.length()").value(2))
    }

    @Test
    fun `PM-BOOK-007 a read of the book writes its entry, a refused read writes none, and an entry is never changed or removed`() {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Раковски 7", "А", "GA")).entranceId
        registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "100.0000")))
        val manager = ownership.registerParty(RegisterParty("Мария Иванова"))
        val url = "/api/registry/entrances/$entrance/book"
        fun entries() = jdbc.queryForObject("SELECT count(*) FROM book_access WHERE entrance_id = ?", Long::class.java, entrance)

        mvc.perform(get(url)).andExpect(status().isBadRequest)                                                    // nobody named
        mvc.perform(get(url).param("actor", manager.toString()).param("purpose", " ")).andExpect(status().isBadRequest)
        mvc.perform(get(url).param("actor", UUID.randomUUID().toString()).param("purpose", "проверка")).andExpect(status().isBadRequest)   // not registered
        mvc.perform(get("/api/registry/entrances/${UUID.randomUUID()}/book").param("actor", manager.toString()).param("purpose", "проверка"))
            .andExpect(status().isNotFound)
        assertThat(entries()).isEqualTo(0)

        mvc.perform(get(url).param("on", "2026-06-01").param("actor", manager.toString()).param("purpose", "годишен отчет"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.units.length()").value(1))
        mvc.perform(get(url).param("actor", manager.toString()).param("purpose", "справка за собственик")).andExpect(status().isOk)
        assertThat(entries()).isEqualTo(2)

        jdbc.update("UPDATE book_access SET purpose = 'друго' WHERE entrance_id = ?", entrance)                  // ignored
        jdbc.update("DELETE FROM book_access WHERE entrance_id = ?", entrance)                                    // ignored
        mvc.perform(get("$url/access-log").param("actor", manager.toString()).param("purpose", "проверка на КЗЛД"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))                                                           // the export is an entry too
            .andExpect(jsonPath("$[0].kind").value("BOOK_READ"))
            .andExpect(jsonPath("$[0].purpose").value("годишен отчет"))
            .andExpect(jsonPath("$[0].bookDate").value("2026-06-01"))
            .andExpect(jsonPath("$[0].actorName").value("Мария Иванова"))
            .andExpect(jsonPath("$[1].purpose").value("справка за собственик"))
            .andExpect(jsonPath("$[2].kind").value("LOG_EXPORT"))
            .andExpect(jsonPath("$[2].bookDate").doesNotExist())

        // The table's own refusals, one at a time: a read with no date, an export with one, a blank purpose, an unknown kind.
        val row = "INSERT INTO book_access (id, entrance_id, actor, purpose, kind, book_date, at) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?::date, now())"
        for ((purpose, kind, date) in listOf(
            Triple("проверка", "BOOK_READ", null), Triple("проверка", "LOG_EXPORT", "2026-06-01"), Triple(" ", "BOOK_READ", "2026-06-01"),
            Triple("проверка", "PEEK", "2026-06-01"),
        )) {
            assertThatThrownBy { jdbc.update(row, entrance, manager, purpose, kind, date) }.isInstanceOf(DataIntegrityViolationException::class.java)
        }
        jdbc.update(row, entrance, manager, "проверка", "BOOK_READ", "2026-06-01")                                // and a sound one is taken
        assertThat(entries()).isEqualTo(4)
    }
}
