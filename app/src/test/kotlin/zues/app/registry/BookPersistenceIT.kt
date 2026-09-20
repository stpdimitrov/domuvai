package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
import java.time.LocalDate

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

        // PM-BOOK-001 — the electronic book reads back over HTTP
        mvc.perform(get("/api/registry/entrances/$entrance/book").param("on", "2026-06-01"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.units.length()").value(2))
    }
}
