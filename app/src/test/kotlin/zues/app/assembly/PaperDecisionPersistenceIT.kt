package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

private const val PAPER_REALM_IT = "https://id.example.test/realms/domuvai"

/**
 * A decision taken on paper on a real Postgres, the application wired as it runs with sign-in configured and one
 * administrator named: what the administrator records stays as it was recorded, whoever asks the table to change it
 * (PM-SEC-004), and the table takes no record short of what the protocol must give (PM-DOC-007). Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["domuvai.auth.issuer-uri=$PAPER_REALM_IT", "domuvai.auth.audience=domuvai-api", "domuvai.auth.mode=", "domuvai.auth.admins=operator-p"])
@AutoConfigureMockMvc
class PaperDecisionPersistenceIT {

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
    @Autowired lateinit var jdbc: JdbcTemplate

    private val content = """{"decisionText":"Вноска за управление\nи поддръжка","protocolDate":"2026-03-14","protocolRef":"Протокол № 3","archiveLocation":"шкаф в офиса","custodian":"Иван Иванов"}"""

    private fun entrance(): UUID {
        val condominium = UUID.randomUUID().also { jdbc.update("INSERT INTO registry.condominium (id, address) VALUES (?, ?)", it, "ул. Шипка ${UUID.randomUUID()}") }
        return UUID.randomUUID().also { jdbc.update("INSERT INTO registry.entrance (id, condominium_id, label, management_form) VALUES (?, ?, 'А', 'GA')", it, condominium) }
    }

    private fun record(entrance: UUID) = mvc.perform(
        post("/api/assembly/entrances/$entrance/paper-decisions").contentType(MediaType.APPLICATION_JSON).content(content)
            .with(jwt().jwt { it.subject("operator-p").issuer(PAPER_REALM_IT) }),
    )

    private fun recorded(entrance: UUID): String = record(entrance).andExpect(status().isCreated).andReturn().response.contentAsString.substringAfter("\"id\":\"").substringBefore('"')

    @Test
    fun `PM-SEC-004 a record names who made it, and is not changed, removed or emptied by anyone`() {
        val id = recorded(entrance())
        val kept = jdbc.queryForMap("SELECT * FROM assembly.paper_decision WHERE id = ?::uuid", id)
        assertThat(kept["login_subject"]).isEqualTo("operator-p")
        assertThat(kept["login_issuer"]).isEqualTo(PAPER_REALM_IT)
        assertThat(kept["decision_text"]).isEqualTo("Вноска за управление\nи поддръжка")
        assertThat(kept["basis_hash"] as String).matches("[0-9a-f]{64}")
        for (attempt in listOf("UPDATE assembly.paper_decision SET custodian = 'друг' WHERE id = '$id'", "DELETE FROM assembly.paper_decision WHERE id = '$id'", "TRUNCATE assembly.paper_decision"))
            assertThatThrownBy { jdbc.execute(attempt) }.describedAs(attempt).isInstanceOf(DataAccessException::class.java).hasMessageContaining("paper_decision")
        assertThat(jdbc.queryForMap("SELECT * FROM assembly.paper_decision WHERE id = ?::uuid", id)).isEqualTo(kept)
    }

    @Test
    fun `PM-DOC-007 the table itself takes no record without its archive location or custodian`() {
        val block = entrance()
        val insert = "INSERT INTO assembly.paper_decision (id, entrance_id, decision_text, protocol_date, protocol_ref, archive_location, custodian, basis, basis_hash, law_version, engine_version, recorded_at) " +
            "VALUES (gen_random_uuid(), '$block', 'т', DATE '2026-03-14', 'п', %s, %s, 'б', '${"a".repeat(64)}', 'л', 'е', now())"
        for ((location, custodian) in listOf("NULL" to "'к'", "' '" to "'к'", "'м'" to "NULL", "'м'" to "' '"))
            assertThatThrownBy { jdbc.update(insert.format(location, custodian)) }.isInstanceOf(DataAccessException::class.java)
        jdbc.update(insert.format("'м'", "'к'"))   // the one the rest differ from: only the refused field refuses it
    }

    @Test
    fun `PM-SEC-001 an entrance nobody registered is a 404, said to the administrator`() {
        record(UUID.randomUUID()).andExpect(status().isNotFound).andExpect(jsonPath("$.error").isNotEmpty)
    }
}
