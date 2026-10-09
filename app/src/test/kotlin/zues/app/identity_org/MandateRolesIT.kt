package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Decision
import zues.app.policy.Held
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.app.policy.Role
import java.time.LocalDate
import java.util.UUID

/**
 * Roles from mandates on a real Postgres (ADR-002 §4.4): a past date is replayed and the roles are those of the
 * mandates in force then — and the policy, wired as the application wires it, decides by them. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MandateRolesIT {

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

    @Autowired lateinit var roles: MandateRoles
    @Autowired lateinit var policy: Policy
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun entrance(): UUID {
        val condominium = UUID.randomUUID().also { jdbc.update("INSERT INTO registry.condominium (id, address) VALUES (?, ?)", it, "ул. Шипка 1") }
        return UUID.randomUUID().also { jdbc.update("INSERT INTO registry.entrance (id, condominium_id, label, management_form) VALUES (?, ?, 'А', 'GA')", it, condominium) }
    }

    private fun party(): UUID = UUID.randomUUID().also { jdbc.update("INSERT INTO registry.party (id, full_name) VALUES (?, ?)", it, "Иван Петров") }

    private fun mandate(entrance: UUID, body: String, party: UUID?, from: String, to: String, succeededAt: String? = null): UUID = UUID.randomUUID().also {
        jdbc.update(
            "INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to, succeeded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            it, entrance, body, party, LocalDate.parse(from), LocalDate.parse(to), succeededAt?.let { LocalDate.parse(it) },
        )
    }

    private fun day(iso: String) = LocalDate.parse(iso)

    @Test
    fun `PM-SEC-011 who held which role in an entrance is answered for any past date — the roles are those of the mandates in force then`() {
        val block = entrance()
        val (ivan, maria, petar, elena) = List(4) { party() }
        val first = mandate(block, "BM", ivan, "2022-03-01", "2024-03-01")                    // stayed on until Maria took over — and nobody stamped it
        val second = mandate(block, "BM", maria, "2024-05-10", "2026-05-10")
        val controller = mandate(block, "CTL", petar, "2023-01-01", "2025-01-01")
        val cashier = mandate(block, "CSH", elena, "2024-05-10", "2026-05-10")
        mandate(block, "MB", null, "2026-05-10", "2028-05-10")                                // nobody's — and no role, though it starts later
        mandate(entrance(), "BM", party(), "2023-01-01", "2025-01-01")                        // another entrance's

        val ivanManager = RoleHolder(ivan, Role.BM, first)
        val mariaManager = RoleHolder(maria, Role.BM, second)
        assertThat(roles.holdersOn(block, day("2022-02-28"))).isEmpty()
        assertThat(roles.holdersOn(block, day("2023-06-01"))).containsExactlyInAnyOrder(ivanManager, RoleHolder(petar, Role.CTL, controller))
        assertThat(roles.holdersOn(block, day("2024-04-01"))).containsExactlyInAnyOrder(ivanManager, RoleHolder(petar, Role.CTL, controller))
        assertThat(roles.holdersOn(block, day("2024-05-10"))).containsExactlyInAnyOrder(mariaManager, RoleHolder(petar, Role.CTL, controller), RoleHolder(elena, Role.CSH, cashier))
        assertThat(roles.holdersOn(block, day("2025-01-01"))).containsExactlyInAnyOrder(mariaManager, RoleHolder(elena, Role.CSH, cashier))
        assertThat(roles.holdersOn(block, day("2026-05-09"))).containsExactlyInAnyOrder(mariaManager, RoleHolder(elena, Role.CSH, cashier))
        assertThat(roles.holdersOn(block, day("2026-05-10"))).isEmpty()                       // the board that follows her has no member on record

        assertThat(roles.held(Asking(null, ivan), block, day("2024-04-01"))).containsExactly(Held(Role.BM, block))
        assertThat(roles.held(Asking(null, ivan), block, day("2024-05-10"))).isEmpty()
        assertThat(roles.held(Asking(null, null), block, day("2024-04-01"))).isEmpty()
    }

    @Test
    fun `PM-SEC-011 a mandate in one entrance is no role in another`() {
        val block = entrance()
        val next = entrance()
        val ivan = party()
        mandate(block, "BM", ivan, "2025-01-01", "2027-01-01")
        assertThat(roles.held(Asking(null, ivan), next, day("2026-01-01"))).isEmpty()
        assertThat(roles.holdersOn(next, day("2026-01-01"))).isEmpty()
        assertThat(policy.decide(Asking(null, ivan), Action.READ_BOOK, Resource.OfEntrance(next), day("2026-01-01")).allowed).isFalse()
    }

    @Test
    fun `PM-SEC-011 the policy decides by the mandate in force on the date asked about — a manager reads the book then, and not before or after`() {
        val block = entrance()
        val ivan = party()
        val petar = party()
        mandate(block, "BM", ivan, "2022-03-01", "2024-03-01", succeededAt = "2024-05-10")
        mandate(block, "CSH", petar, "2022-03-01", "2024-03-01")
        fun reads(who: UUID, on: String) = policy.decide(Asking(null, who), Action.READ_BOOK, Resource.OfEntrance(block), day(on))
        assertThat(reads(ivan, "2022-02-28")).isEqualTo(Decision(false, "PM-BOOK-006", null, Policy.MATRIX_VERSION))
        assertThat(reads(ivan, "2023-06-01")).isEqualTo(Decision(true, "PM-BOOK-006", Role.BM, Policy.MATRIX_VERSION))
        assertThat(reads(ivan, "2024-04-01").allowed).isTrue()                                // past its end, no successor yet
        assertThat(reads(ivan, "2024-05-10").allowed).isFalse()
        assertThat(reads(petar, "2023-06-01").allowed).isFalse()                              // the cashier holds a role, and it reads no book
        assertThat(reads(party(), "2023-06-01").allowed).isFalse()
    }
}
