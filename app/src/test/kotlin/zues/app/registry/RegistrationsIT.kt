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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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

private const val REGISTRATIONS_REALM_IT = "https://id.example.test/realms/domuvai"

/**
 * The list of entrances, and an entrance or a party registered, against real PostgreSQL with sign-in configured and one
 * administrator named: every request signed, every role derived from a real title or mandate, decided by the policy
 * (PM-SEC-001, PM-BOOK-005; the owner's decisions R1a, R2a, R3a on #322). Docker-gated — runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["domuvai.auth.issuer-uri=$REGISTRATIONS_REALM_IT", "domuvai.auth.audience=domuvai-api", "domuvai.auth.mode=", "domuvai.auth.admins=operator-r"])
@AutoConfigureMockMvc
class RegistrationsIT {

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

    private val run = UUID.randomUUID().toString().take(8)
    private val today: LocalDate get() = LocalDate.parse(toSofiaDate(Instant.now()))

    private fun person(subject: String): UUID = ownership.registerParty(RegisterParty("Лице $subject")).also { logins.tie(REGISTRATIONS_REALM_IT, "$subject-$run", it) }

    private fun mandate(entrance: UUID, body: String, party: UUID, endedToday: Boolean = false) {
        jdbc.update(
            "INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to, succeeded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), entrance, body, party, today.minusMonths(1), today.plusMonths(11), if (endedToday) today else null,
        )
    }

    /** An entrance of a building of its own, with its one unit. */
    private fun entrance(): Pair<UUID, UUID> {
        val entrance = registry.registerEntrance(RegisterEntrance("ул. Шипка $run-${UUID.randomUUID()}", "А", "GA")).entranceId
        return entrance to registry.registerUnits(entrance, listOf(RegisterUnit("ап. 1", "APARTMENT", idealParts = "100.0000"))).single()
    }

    private fun signed(subject: String) = jwt().jwt { it.subject(if (subject == "operator-r") subject else "$subject-$run").issuer(REGISTRATIONS_REALM_IT) }

    private fun listed(subject: String): List<UUID> {
        val body = mvc.perform(get("/api/registry/entrances").with(signed(subject))).andExpect(status().isOk).andReturn().response.contentAsString
        return json.readTree(body).map { UUID.fromString(it["id"].asText()) }
    }

    private fun send(path: String, subject: String, body: String): ResultActions =
        mvc.perform(post(path).with(signed(subject)).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun parties(name: String): Int = jdbc.queryForObject("SELECT count(*) FROM registry.party WHERE full_name = ?", Int::class.java, name)!!

    private fun entrancesLabelled(label: String): Int = jdbc.queryForObject("SELECT count(*) FROM registry.entrance WHERE label = ?", Int::class.java, label)!!

    @Test
    fun `PM-SEC-001 each person lists only the entrances where they hold a role today — the administrator every one, a login tied to nobody none`() {
        val (a, flatA) = entrance()
        val (b, flatB) = entrance()
        person("owner").also { ownership.assignTitle(a, flatA, AssignTitle(it, "OWN", "1")) }
        person("manager").also { mandate(b, "BM", it) }
        person("ended").also { mandate(a, "BM", it, endedToday = true) }
        person("future").also { ownership.assignTitle(b, flatB, AssignTitle(it, "OWN", "1", validFrom = today.plusDays(1).toString())) }

        assertThat(listed("owner")).containsExactly(a)
        assertThat(listed("manager")).containsExactly(b)
        assertThat(listed("ended")).isEmpty()
        assertThat(listed("future")).isEmpty()
        assertThat(listed("untied")).isEmpty()                         // a login the api ties to nobody: 200 [], not a refusal
        assertThat(listed("operator-r")).contains(a, b)
    }

    @Test
    fun `PM-BOOK-005 the manager or the board of any entrance registers the party a title will name`() {
        val (a, flatA) = entrance()
        val (b, _) = entrance()
        person("p-manager").also { mandate(a, "BM", it) }
        person("p-board").also { mandate(b, "MB", it) }
        for (subject in listOf("p-manager", "p-board", "operator-r")) {
            val name = "Нов собственик $subject $run"
            send("/api/registry/parties", subject, """{"fullName":"$name"}""").andExpect(status().isCreated).andExpect(jsonPath("$.partyId").exists())
            assertThat(parties(name)).describedAs(subject).isEqualTo(1)
        }
        val named = mvc.perform(
            post("/api/registry/parties").with(signed("p-board")).contentType(MediaType.APPLICATION_JSON).content("""{"fullName":"Купувач $run"}"""),
        ).andReturn().response.contentAsString.let { json.readTree(it)["partyId"].asText() }
        send("/api/registry/entrances/$a/units/$flatA/titles", "p-manager", """{"partyId":"$named","titleRole":"OWN","share":"1"}""").andExpect(status().isCreated)
    }

    @Test
    fun `PM-SEC-001 the owner, the user, the controller, the cashier and a login tied to nobody register no party — each a 403 naming the rule, and no party written`() {
        val (a, flatA) = entrance()
        person("r-owner").also { ownership.assignTitle(a, flatA, AssignTitle(it, "OWN", "0.5")) }
        person("r-user").also { ownership.assignTitle(a, flatA, AssignTitle(it, "USR", "1")) }
        person("r-controller").also { mandate(a, "CTL", it) }
        person("r-cashier").also { mandate(a, "CSH", it) }
        for (subject in listOf("r-owner", "r-user", "r-controller", "r-cashier", "r-untied")) {
            val name = "Отказан $subject $run"
            send("/api/registry/parties", subject, """{"fullName":"$name"}""")
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.rule").value("PM-SEC-001"))
                .andExpect(jsonPath("$.error").value("a party is registered by the deployment's administrator, or by the manager or the board of an entrance"))
            assertThat(parties(name)).describedAs(subject).isZero()
        }
    }

    @Test
    fun `PM-SEC-001 only the administrator registers an entrance — the manager and the board of another are refused, and no entrance written`() {
        val (a, _) = entrance()
        person("e-manager").also { mandate(a, "BM", it) }
        person("e-board").also { mandate(a, "MB", it) }
        for (subject in listOf("e-manager", "e-board", "e-untied")) {
            val label = "Отказан $subject $run"
            send("/api/registry/entrances", subject, """{"address":"ул. Солун $run","label":"$label","managementForm":"GA"}""")
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.rule").value("PM-SEC-001"))
                .andExpect(jsonPath("$.error").value("an entrance is registered by the deployment's administrator"))
            assertThat(entrancesLabelled(label)).describedAs(subject).isZero()
        }
        send("/api/registry/entrances", "operator-r", """{"address":"ул. Солун $run","label":"Б $run","managementForm":"GA"}""").andExpect(status().isCreated)
        assertThat(entrancesLabelled("Б $run")).isEqualTo(1)
    }
}
