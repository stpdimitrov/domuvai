package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDate
import java.util.UUID

/**
 * BOOK-02e (#441) against real PostgreSQL: a party is registered in an entrance, and only that entrance's book may
 * name it — the service refuses another's as it refuses an unknown one, the database refuses it underneath, and the
 * migration that tied existing parties refuses one already named in two books. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PartyOfItsEntrancePersistenceIT {

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
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var book: BookService
    @Autowired lateinit var transactions: TransactionTemplate

    private fun post(path: String, body: String) = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
    private fun created(path: String, body: String, field: String) =
        UUID.fromString(json.readTree(post(path, body).andExpect(status().isCreated).andReturn().response.contentAsString).get(field).asText())

    private fun entrance(): Pair<UUID, UUID> {
        val entrance = created("/api/registry/entrances", """{"address":"ул. Шипка 1","label":"А","managementForm":"GA"}""", "entranceId")
        val unit = json.readTree(
            post("/api/registry/entrances/$entrance/units", """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""")
                .andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get("unitIds")[0].asText()
        return entrance to UUID.fromString(unit)
    }

    private fun party(entrance: UUID, name: String) =
        created("/api/registry/parties", """{"entranceId":"$entrance","fullName":"$name","idType":"EGN","idValue":"7501010010"}""", "partyId")

    private fun rows(table: String, unit: UUID) = jdbc.queryForObject("SELECT count(*) FROM registry.$table WHERE unit_id = ?", Int::class.java, unit)!!

    @Test
    fun `PM-BOOK-011 entrance A cannot enter B's party as a title holder or a member — the answer of an unknown id, nothing stored, and A's book never names them`() {
        val (a, flatA) = entrance()
        val (b, _) = entrance()
        val ofB = party(b, "Борис Борисов")
        val unknown = UUID.randomUUID()
        assertThat(jdbc.queryForObject("SELECT entrance_id FROM registry.party WHERE id = ?", UUID::class.java, ofB)).isEqualTo(b)

        for (named in listOf(ofB, unknown)) {
            post("/api/registry/entrances/$a/units/$flatA/titles", """{"partyId":"$named","titleRole":"OWN","share":"1"}""")
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("PM-BOOK-011: no party $named"))
            post("/api/registry/entrances/$a/units/$flatA/household", """{"members":[{"partyId":"$named"}]}""")
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("PM-BOOK-011: no party $named"))
        }
        assertThat(rows("title", flatA) to rows("household_member", flatA)).isEqualTo(0 to 0)

        // A's own party is entered as before, and A's book names them and nobody of B's
        val ofA = party(a, "Анна Петрова")
        post("/api/registry/entrances/$a/units/$flatA/titles", """{"partyId":"$ofA","titleRole":"OWN","share":"1","validFrom":"2026-01-01"}""").andExpect(status().isCreated)
        post("/api/registry/entrances/$a/units/$flatA/household", """{"members":[{"partyId":"$ofA","validFrom":"2026-01-01"}]}""").andExpect(status().isCreated)
        val read = json.writeValueAsString(book.forEntrance(a, LocalDate.parse("2026-06-01")))
        assertThat(read).contains("Анна Петрова").doesNotContain("Борис Борисов").doesNotContain(ofB.toString())
    }

    @Test
    fun `PM-BOOK-011 the database refuses a title or a member naming a party of another entrance, and a party registered in none`() {
        val (a, flatA) = entrance()
        val (b, _) = entrance()
        val ofB = party(b, "Борис Борисов")
        assertThatThrownBy {
            jdbc.update("INSERT INTO registry.title (id, entrance_id, unit_id, party_id, title_role, share, valid_from) VALUES (?, ?, ?, ?, 'OWN', 1, '2026-01-01')", UUID.randomUUID(), a, flatA, ofB)
        }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("title_party_of_entrance")
        assertThatThrownBy {
            jdbc.update("INSERT INTO registry.household_member (id, entrance_id, unit_id, party_id, valid_from) VALUES (?, ?, ?, ?, '2026-01-01')", UUID.randomUUID(), a, flatA, ofB)
        }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("household_member_party_of_entrance")
        assertThatThrownBy { jdbc.update("INSERT INTO registry.party (id, full_name) VALUES (?, 'Никой')", UUID.randomUUID()) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("party_registered_in_an_entrance")
        assertThat(rows("title", flatA) to rows("household_member", flatA)).isEqualTo(0 to 0)
    }

    /** The BOOK-02e migration run again on [before], over the schema as it stood before it, in a transaction rolled back. */
    private fun <T> migrated(before: String, read: () -> T): Result<T> {
        val migration = ClassPathResource("db/migration/V202611081620__party_of_its_entrance.sql").getContentAsString(Charsets.UTF_8)
        var outcome: Result<T>? = null
        transactions.executeWithoutResult { tx ->
            jdbc.execute(
                """ALTER TABLE registry.title DROP CONSTRAINT title_party_of_entrance;
                   ALTER TABLE registry.household_member DROP CONSTRAINT household_member_party_of_entrance;
                   ALTER TABLE registry.party DROP COLUMN entrance_id;
                   $before""",
            )
            outcome = runCatching { jdbc.execute(migration); read() }
            tx.setRollbackOnly()
        }
        return outcome!!
    }

    @Test
    fun `PM-BOOK-011 the migration ties a party to the one book naming it, and refuses one already named in two, naming it`() {
        val (a, flatA) = entrance()
        val (b, flatB) = entrance()
        val (spread, single) = UUID.randomUUID() to UUID.randomUUID()
        val seed = """INSERT INTO registry.party (id, full_name) VALUES ('$spread', 'Двама'), ('$single', 'Един');
                      INSERT INTO registry.title (id, entrance_id, unit_id, party_id, title_role, share, valid_from) VALUES ('${UUID.randomUUID()}', '$a', '$flatA', '$single', 'OWN', 1, '2026-01-01');"""
        val inTwo = """INSERT INTO registry.title (id, entrance_id, unit_id, party_id, title_role, share, valid_from) VALUES ('${UUID.randomUUID()}', '$a', '$flatA', '$spread', 'USR', 1, '2026-01-01');
                       INSERT INTO registry.household_member (id, entrance_id, unit_id, party_id, valid_from) VALUES ('${UUID.randomUUID()}', '$b', '$flatB', '$spread', '2026-01-01');"""

        val refused = migrated(seed + inTwo) {}.exceptionOrNull()
        assertThat(refused).isNotNull()
        assertThat(generateSequence(refused) { it.cause }.joinToString { it.message.orEmpty() })
            .contains("party $spread is named in the books of more than one entrance").doesNotContain(single.toString())

        val tied = migrated(seed) { jdbc.queryForList("SELECT id, entrance_id FROM registry.party WHERE id IN ('$single', '$spread')").associate { it["id"] to it["entrance_id"] } }.getOrThrow()
        assertThat(tied).isEqualTo(mapOf(single to a, spread to null))   // named in no book: no book can name it
    }
}
