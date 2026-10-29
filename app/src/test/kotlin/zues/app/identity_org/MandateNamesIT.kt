package zues.app.identity_org

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.app.policy.Asking
import zues.app.policy.Login
import zues.kernel.toSofiaDate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val REALM_NAMES = "https://id.example.test/realms/domuvai"

/**
 * The entrance's mandates with the office-holders' names on a real Postgres, the application wired as it runs with
 * sign-in configured and one administrator named: the registry's adapter answers the port, the real policy decides by
 * the real mandates, and the book's access log is the real table (#249 N1). Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["domuvai.auth.issuer-uri=$REALM_NAMES", "domuvai.auth.audience=domuvai-api", "domuvai.auth.mode=", "domuvai.auth.admins=operator-names"])
class MandateNamesIT {

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

    @Autowired lateinit var reading: MandateReading
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun entrance(): UUID {
        val condominium = UUID.randomUUID().also { jdbc.update("INSERT INTO registry.condominium (id, address) VALUES (?, ?)", it, "ул. Шипка 1") }
        return UUID.randomUUID().also { jdbc.update("INSERT INTO registry.entrance (id, condominium_id, label, management_form) VALUES (?, ?, 'А', 'GA')", it, condominium) }
    }

    private fun party(name: String, egn: String? = null): UUID = UUID.randomUUID().also {
        jdbc.update("INSERT INTO registry.party (id, full_name, id_type, id_value) VALUES (?, ?, ?, ?)", it, name, egn?.let { "EGN" }, egn)
    }

    private fun mandate(entrance: UUID, body: String, party: UUID) {
        jdbc.update(
            "INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), entrance, body, party, LocalDate.parse("2020-01-01"), LocalDate.parse("2099-01-01"),
        )
    }

    private fun asking(party: UUID) = Asking(Login(REALM_NAMES, "login-$party"), party)
    private val administrator = Asking(Login(REALM_NAMES, "operator-names"), null)

    private fun read(who: Asking, entranceId: UUID) = (reading.read(who, entranceId, null) as MandateAnswer.Done).value
    private fun entries(entranceId: UUID) = jdbc.queryForList("SELECT * FROM registry.book_access WHERE entrance_id = ? AND kind = 'PARTY_NAMES' ORDER BY at", entranceId)

    /** An entrance with a manager and a controller, and a hall beside it run by a board. */
    private inner class Block {
        val entrance = entrance()
        val hall = entrance()
        val manager = party("Иван Петров", egn = "7501020018")
        val controller = party("Мария Иванова")
        val member = party("Георги Димитров")
        init {
            mandate(entrance, "BM", manager)
            mandate(entrance, "CTL", controller)
            mandate(hall, "MB", member)
        }
    }

    @Test
    fun `PM-BOOK-006 the entrance's mandates carry each office-holder's name to the manager, the board and the controller`() {
        val b = Block()
        for (who in listOf(b.manager, b.controller)) {
            assertThat(read(asking(who), b.entrance).associate { it.partyId to it.partyName })
                .isEqualTo(mapOf(b.manager to "Иван Петров", b.controller to "Мария Иванова"))
        }
        assertThat(read(asking(b.member), b.hall).single().partyName).isEqualTo("Георги Димитров")
    }

    @Test
    fun `PM-BOOK-006 the administrator reads the entrance's mandates with the ids only — no name`() {
        val b = Block()
        val read = read(administrator, b.entrance)
        assertThat(read.map { it.partyId }).containsExactlyInAnyOrder(b.manager, b.controller)
        assertThat(read.map { it.partyName }).containsOnlyNulls()
        assertThat(json.writeValueAsString(read)).doesNotContain("Иван Петров").doesNotContain("Мария Иванова")
    }

    @Test
    fun `PM-BOOK-011 a name beside an id is the name only — a party registered with an EGN shows no identity number in the mandates read`() {
        val b = Block()
        val text = json.writeValueAsString(read(asking(b.controller), b.entrance))
        assertThat(text).contains("Иван Петров").doesNotContain("7501020018").doesNotContain("idValue").doesNotContain("EGN")
    }

    @Test
    fun `PM-BOOK-007 names served beside the mandates are entered in the book's access log with actor, purpose and time — withheld names enter nothing`() {
        val b = Block()
        val before = Instant.now()
        read(asking(b.controller), b.entrance)
        val entry = entries(b.entrance).single()
        assertThat(entry["actor"]).isEqualTo(b.controller)
        assertThat(entry["login_subject"]).isEqualTo("login-${b.controller}")
        assertThat(entry["purpose"]).isEqualTo(MandateReading.PURPOSE)
        assertThat(entry["outcome"]).isEqualTo("SERVED")
        assertThat(entry["book_date"].toString()).isEqualTo(toSofiaDate(Instant.now()))
        assertThat((entry["at"] as java.sql.Timestamp).toInstant()).isAfterOrEqualTo(before.minusSeconds(1))
        read(administrator, b.entrance)                                                        // names withheld: nothing of the book was given
        assertThat(entries(b.entrance)).hasSize(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registry.book_access WHERE entrance_id = ?", Int::class.java, b.entrance)).isEqualTo(1)
        read(asking(b.manager), b.entrance)                                                    // one entry per read served
        assertThat(entries(b.entrance).map { it["actor"] }).containsExactly(b.controller, b.manager)
        // a manager of one entrance is refused the hall's mandates — neither ids nor names — and nothing is entered there
        assertThat(reading.read(asking(b.manager), b.hall, null)).isInstanceOf(MandateAnswer.Refused::class.java)
        assertThat(entries(b.hall)).isEmpty()
    }

    @Test
    fun `PM-BOOK-007 names given with sign-in switched off are entered as open, to nobody — and an open entry naming a party is refused`() {
        // The mandates read itself is refused to nobody; the port answers nobody only with sign-in switched off
        // (PartyNamesAdapterTest). The table takes that entry — open, to nobody — and no open entry naming a party.
        val b = Block()
        fun open(actor: UUID?) = jdbc.update(
            "INSERT INTO registry.book_access (id, entrance_id, actor, purpose, kind, book_date, at, outcome, rule_id) VALUES (?, ?, ?, ?, 'PARTY_NAMES', ?, now(), 'OPEN', 'PM-BOOK-006')",
            UUID.randomUUID(), b.entrance, actor, MandateReading.PURPOSE, LocalDate.parse(toSofiaDate(Instant.now())),
        )
        assertThat(open(null)).isEqualTo(1)
        assertThatThrownBy { open(b.manager) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
