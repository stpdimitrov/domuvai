package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.util.UUID

/**
 * The tie between a login and a party on a real Postgres (ADR-011): made once, read back by issuer and subject, and
 * refused for a login already tied, a party already tied under that issuer, or a party nobody registered. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class LoginsIT {

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

    @Autowired lateinit var logins: Logins
    @Autowired lateinit var jdbc: JdbcTemplate

    private val realm = "https://id.example.test/realms/${UUID.randomUUID()}"

    private fun party(): UUID = UUID.randomUUID().also { jdbc.update("INSERT INTO registry.party (id, full_name) VALUES (?, ?)", it, "Иван Петров") }

    @Test
    fun `a login tied to a party reads back as that party — and only under its own issuer and subject`() {
        val ivan = party()
        assertThat(logins.partyOf(realm, "login-a")).isNull()
        logins.tie(realm, "login-a", ivan)
        assertThat(logins.partyOf(realm, "login-a")).isEqualTo(ivan)
        assertThat(logins.partyOf(realm, "login-A")).isNull()
        assertThat(logins.partyOf("$realm-other", "login-a")).isNull()                 // the same subject from another issuer is another login
        logins.tie("$realm-other", "login-a", party())                                // and may be tied to someone else
        assertThat(logins.partyOf(realm, "login-a")).isEqualTo(ivan)
    }

    @Test
    fun `a tie is made once — a login is not tied twice, nor a party to two logins of one issuer, nor to nobody`() {
        val ivan = party()
        val maria = party()
        logins.tie(realm, "login-a", ivan)
        assertThatThrownBy { logins.tie(realm, "login-a", maria) }.isInstanceOf(LoginAlreadyTied::class.java)
        assertThatThrownBy { logins.tie(realm, "login-b", ivan) }.isInstanceOf(LoginAlreadyTied::class.java)
        assertThatThrownBy { logins.tie(realm, "login-c", UUID.randomUUID()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { logins.tie(realm, " ", maria) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(logins.partyOf(realm, "login-a")).isEqualTo(ivan)                   // nothing moved
        assertThat(logins.partyOf(realm, "login-b")).isNull()
        assertThat(jdbc.queryForObject("SELECT tied_at IS NOT NULL FROM identity_org.login WHERE issuer = ? AND subject = 'login-a'", Boolean::class.java, realm)).isTrue()
    }
}
