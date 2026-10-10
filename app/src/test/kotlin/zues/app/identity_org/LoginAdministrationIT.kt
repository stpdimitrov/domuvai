package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Login
import zues.app.policy.Policy
import zues.app.policy.Resource
import java.time.LocalDate
import java.util.UUID

private const val REALM_IT = "https://id.example.test/realms/domuvai"

/**
 * Tying and untying on a real Postgres, the application wired as it runs with sign-in configured and one administrator
 * named: a tie reads back as the party, a re-tie is an untie and a tie, a refusal changes nothing, and the record of
 * it all is never changed or removed (PM-SEC-001, PM-SEC-004). Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["domuvai.auth.issuer-uri=$REALM_IT", "domuvai.auth.audience=domuvai-api", "domuvai.auth.mode=", "domuvai.auth.admins=operator-it"])
class LoginAdministrationIT {

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

    @Autowired lateinit var administration: LoginAdministration
    @Autowired lateinit var logins: Logins
    @Autowired lateinit var policy: Policy
    @Autowired lateinit var jdbc: JdbcTemplate
    @MockitoSpyBean lateinit var log: LoginActLog       // the real one; one test makes it fail

    private val operator = Asking(Login(REALM_IT, "operator-it"), null)
    private val run = UUID.randomUUID().toString().take(8)

    private fun party(name: String): UUID = UUID.randomUUID().also { jdbc.update("INSERT INTO registry.party (id, full_name) VALUES (?, ?)", it, name) }
    private fun acts(subject: String) = jdbc.queryForList("SELECT act FROM identity_org.login_act WHERE login_subject = ? ORDER BY at", String::class.java, subject)

    @Test
    fun `PM-SEC-001 a login is a party once the administrator ties it — and a recreated account is re-tied by an untie and a tie`() {
        val ivan = party("Иван Петров")
        val old = "login-$run-old"
        val new = "login-$run-new"
        assertThat(administration.tie(operator, old, ivan)).isEqualTo(LoginAnswer.Done(LoginTied(REALM_IT, old, ivan)))
        assertThat(logins.partyOf(REALM_IT, old)).isEqualTo(ivan)
        // the account was recreated: a new subject. The party has a login already, so the old one goes first.
        assertThatThrownBy { administration.tie(operator, new, ivan) }.isInstanceOf(LoginAlreadyTied::class.java)
        assertThat(administration.untie(operator, old, null)).isEqualTo(LoginAnswer.Done(LoginUntied(true, ivan)))
        assertThat(administration.tie(operator, new, ivan)).isInstanceOf(LoginAnswer.Done::class.java)
        assertThat(logins.partyOf(REALM_IT, old)).isNull()
        assertThat(logins.partyOf(REALM_IT, new)).isEqualTo(ivan)
        assertThat(acts(old)).containsExactly("TIED", "UNTIED")
        assertThat(acts(new)).containsExactly("TIED")                                             // the tie that was refused for being tied left no entry of a tie
        // a row an old issuer URL left behind is cleared by naming that issuer
        val before = "https://old.example.test/realms/domuvai"
        logins.tie(before, old, party("Мария Иванова"))
        assertThat((administration.untie(operator, old, before) as LoginAnswer.Done).value.untied).isTrue()
        assertThat(logins.partyOf(before, old)).isNull()
    }

    @Test
    fun `PM-SEC-001 nobody but the administrator ties a login — an owner cannot make themselves the manager — and the administrator reads no book`() {
        val owner = party("Георги Стоянов")
        val manager = party("Мария Иванова")
        val ownersLogin = "login-$run-owner"
        logins.tie(REALM_IT, ownersLogin, owner)
        val asOwner = Asking(Login(REALM_IT, ownersLogin), owner)
        assertThat(administration.untie(asOwner, ownersLogin, null)).isEqualTo(LoginAnswer.Refused("PM-SEC-001"))
        assertThat(administration.tie(asOwner, ownersLogin, manager)).isEqualTo(LoginAnswer.Refused("PM-SEC-001"))
        assertThat(administration.tie(Asking(Login("$REALM_IT-other", "operator-it"), null), "login-$run-x", manager)).isEqualTo(LoginAnswer.Refused("PM-SEC-001"))
        assertThat(administration.tie(Asking(null, null), "login-$run-x", manager)).isEqualTo(LoginAnswer.Refused("PM-SEC-001"))
        assertThat(logins.partyOf(REALM_IT, ownersLogin)).isEqualTo(owner)                        // nothing moved
        assertThat(logins.partyOf(REALM_IT, "login-$run-x")).isNull()
        assertThat(acts(ownersLogin)).containsExactlyInAnyOrder("UNTIE_REFUSED", "TIE_REFUSED")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_org.login_act WHERE login_subject = ?", Long::class.java, "login-$run-x")).isEqualTo(1)   // the other issuer's operator; nobody's attempt is no entry
        assertThat(jdbc.queryForMap("SELECT by_issuer, by_subject, party_id, rule_id FROM identity_org.login_act WHERE login_subject = ? AND act = 'TIE_REFUSED'", ownersLogin).values)
            .containsExactly(REALM_IT, ownersLogin, manager, "PM-SEC-001")

        val entrance = UUID.randomUUID()
        val today = LocalDate.parse("2026-06-01")
        assertThat(policy.decide(operator, Action.READ_BOOK, Resource.OfEntrance(entrance), today).allowed).isFalse()
        assertThat(policy.decide(operator, Action.TIE_LOGIN, Resource.Deployment, today).allowed).isTrue()
    }

    @Test
    fun `PM-SEC-004 the act and its entry stand or fall together — a tie or an untie whose entry cannot be written is not made`() {
        val ivan = party("Иван Петров")
        val subject = "login-$run-atomic"
        doThrow(IllegalStateException("the entry could not be written")).whenever(log).record(eq("TIED"), any(), eq(subject), anyOrNull(), any(), any())
        assertThatThrownBy { administration.tie(operator, subject, ivan) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(logins.partyOf(REALM_IT, subject)).isNull()
        reset(log)
        administration.tie(operator, subject, ivan)
        doThrow(IllegalStateException("the entry could not be written")).whenever(log).record(eq("UNTIED"), any(), eq(subject), anyOrNull(), any(), any())
        assertThatThrownBy { administration.untie(operator, subject, null) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(logins.partyOf(REALM_IT, subject)).isEqualTo(ivan)
        reset(log)
        // … and the administrator's own login is not tied to anybody
        assertThatThrownBy { administration.tie(operator, "operator-it", party("Мария Иванова")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(logins.partyOf(REALM_IT, "operator-it")).isNull()
    }

    @Test
    fun `PM-SEC-004 the record of who was said to be whom is never changed or removed, and takes no malformed entry`() {
        val ivan = party("Иван Петров")
        val subject = "login-$run-kept"
        administration.tie(operator, subject, ivan)
        jdbc.update("UPDATE identity_org.login_act SET act = 'UNTIED', by_subject = 'somebody-else' WHERE login_subject = ?", subject)   // ignored
        jdbc.update("DELETE FROM identity_org.login_act WHERE login_subject = ?", subject)                                                // ignored
        assertThatThrownBy { jdbc.execute("TRUNCATE identity_org.login_act") }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(jdbc.queryForMap("SELECT act, by_issuer, by_subject, party_id FROM identity_org.login_act WHERE login_subject = ?", subject).values)
            .containsExactly("TIED", REALM_IT, "operator-it", ivan)

        val row = "INSERT INTO identity_org.login_act (id, act, login_issuer, login_subject, party_id, by_issuer, by_subject, rule_id, at) VALUES (gen_random_uuid(), ?, 'i', 's', ?, ?, ?, ?, now())"
        for (bad in listOf(
            arrayOf("TIED", ivan, null, null, "PM-SEC-001"),            // done by nobody
            arrayOf("TIED", null, "i", "op", "PM-SEC-001"),             // a tie to no party
            arrayOf("UNTIED", null, "i", "op", "PM-SEC-001"),           // an untie from no party
            arrayOf("TIED", ivan, "i", null, "PM-SEC-001"),             // half a login
            arrayOf("PEEKED", ivan, "i", "op", "PM-SEC-001"),
            arrayOf("TIED", ivan, "i", "op", "some rule"),
        )) assertThatThrownBy { jdbc.update(row, *bad) }.describedAs(bad.joinToString()).isInstanceOf(DataIntegrityViolationException::class.java)
        jdbc.update(row, "TIE_REFUSED", null, null, null, "PM-SEC-001")                           // a refusal of nobody is an entry
    }
}
