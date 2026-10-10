package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Login
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private const val ISSUER = "https://id.example.test/realms/domuvai"

/**
 * Tying and untying a login, with the real policy and the real administrators over a configuration the test gives —
 * no Spring, no database. Proves only a login the deployment names does either, that everybody else is refused before
 * anything is looked at or changed, and that every act and every refusal is recorded (PM-SEC-001, PM-SEC-004).
 */
class LoginAdministrationTest {

    private val logins: Logins = mock()
    private val acts: LoginActLog = mock()
    private val entrance = UUID.randomUUID()
    private val manager = Asking(Login(ISSUER, "manager"), UUID.randomUUID())
    private val owner = Asking(Login(ISSUER, "owner"), UUID.randomUUID())
    private val asked = mutableListOf<LocalDate>()

    /** The entrance's own roles: a manager and an owner — neither is the deployment's administrator. */
    private val inTheEntrance = RoleSource { who, at, asAt ->
        asked += asAt
        when (who) { manager -> setOf(Held(Role.BM, at)); owner -> setOf(Held(Role.OWN, at, setOf(UUID.randomUUID()))); else -> emptySet() }
    }
    private val administrators = Administrators(ISSUER, " operator , second-operator ,")
    private val policy = Policy(listOf(inTheEntrance, administrators))
    // 00:30 on 1 June in Sofia; still 31 May in UTC
    private val administration = LoginAdministration(logins, policy, administrators, acts, Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC))

    private val operator = Asking(Login(ISSUER, "operator"), null)
    private val ivan = UUID.randomUUID()

    @Test
    fun `PM-SEC-001 the administrator the deployment names ties a login of the api's own issuer to a party, and the act is recorded`() {
        assertThat(administration.tie(operator, "login-ivan", ivan)).isEqualTo(LoginAnswer.Done(LoginTied(ISSUER, "login-ivan", ivan)))
        verify(logins).tie(ISSUER, "login-ivan", ivan)
        verify(acts).record("TIED", ISSUER, "login-ivan", ivan, operator, "PM-SEC-001")
        assertThat(administration.tie(Asking(Login(ISSUER, "second-operator"), UUID.randomUUID()), "login-b", ivan)).isInstanceOf(LoginAnswer.Done::class.java)
    }

    @Test
    fun `PM-SEC-001 nobody else ties or unties a login — refused before anything is looked at or changed, each attempt recorded`() {
        val others = listOf(
            manager, owner,
            Asking(Login(ISSUER, "stranger"), UUID.randomUUID()),
            Asking(Login(ISSUER, "untied"), null),
            Asking(Login("$ISSUER-other", "operator"), null),                                      // the same subject under another issuer
            Asking(Login(ISSUER, "Operator"), null), Asking(Login(ISSUER, ""), null),
            Asking(null, UUID.randomUUID()),                                                       // a party with no login: nobody to put on the record
            Asking(null, null),                                                                    // sign-in switched off
        )
        for (who in others) {
            assertThat(administration.tie(who, "operator", who.party ?: ivan)).describedAs("$who").isEqualTo(LoginAnswer.Refused("PM-SEC-001"))
            assertThat(administration.untie(who, "login-ivan", null)).describedAs("$who").isEqualTo(LoginAnswer.Refused("PM-SEC-001"))
            assertThat(administration.tie(who, " ", ivan)).describedAs("$who").isEqualTo(LoginAnswer.Refused("PM-SEC-001"))   // not told what is wrong with it
        }
        verifyNoInteractions(logins)
        verify(acts).record("TIE_REFUSED", ISSUER, "operator", manager.party, manager, "PM-SEC-001")
        verify(acts).record("UNTIE_REFUSED", ISSUER, "login-ivan", null, owner, "PM-SEC-001")
        // with nobody signed in there is nobody to enter: nothing is written for a party with no login, nor for nobody at all
        verify(acts, never()).record(any(), any(), any(), anyOrNull(), org.mockito.kotlin.argThat { login == null }, any())
        // what a refused caller sent is kept as a line of ordinary characters, no longer than a subject
        administration.tie(owner, "x\u0000y\n" + "z".repeat(SUBJECT_MAX), ivan)
        verify(acts).record("TIE_REFUSED", ISSUER, ("xy" + "z".repeat(SUBJECT_MAX)).take(SUBJECT_MAX), ivan, owner, "PM-SEC-001")
        verify(acts, never()).record(org.mockito.kotlin.eq("TIED"), any(), any(), anyOrNull(), any(), any())
        verify(acts, never()).record(org.mockito.kotlin.eq("UNTIED"), any(), any(), anyOrNull(), any(), any())
    }

    @Test
    fun `PM-SEC-001 an administrator is whoever the configuration names — the answer does not come from an entrance's roles`() {
        val admins = Administrators(ISSUER, "operator")
        assertThat(admins.administers(operator, LocalDate.parse("2026-06-01"))).isTrue()
        assertThat(admins.held(operator, entrance, LocalDate.parse("2026-06-01"))).isEmpty()
        assertThat(policy.rolesOf(operator, entrance, LocalDate.parse("2026-06-01"))).isEmpty()
        assertThat(Administrators(ISSUER, "").administers(operator, LocalDate.parse("2026-06-01"))).isFalse()
        assertThat(Administrators("", "").administers(Asking(Login("", "operator"), null), LocalDate.parse("2026-06-01"))).isFalse()
        assertThatThrownBy { Administrators(" ", "operator") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("DOMUVAI_AUTH_ADMINS")
    }

    @Test
    fun `PM-SEC-004 an untie is recorded with the party the login was, under the issuer named — and one tied to nobody is no act`() {
        whenever(logins.untie(ISSUER, "login-ivan")).thenReturn(ivan)
        whenever(logins.untie("https://old.example.test/realms/domuvai", "login-ivan")).thenReturn(ivan)
        assertThat(administration.untie(operator, "login-ivan", null)).isEqualTo(LoginAnswer.Done(LoginUntied(true, ivan)))
        assertThat(administration.untie(operator, "login-ivan", " https://old.example.test/realms/domuvai ")).isEqualTo(LoginAnswer.Done(LoginUntied(true, ivan)))
        assertThat(administration.untie(operator, "login-nobody", " ")).isEqualTo(LoginAnswer.Done(LoginUntied(false, null)))
        verify(acts).record("UNTIED", ISSUER, "login-ivan", ivan, operator, "PM-SEC-001")
        verify(acts).record("UNTIED", "https://old.example.test/realms/domuvai", "login-ivan", ivan, operator, "PM-SEC-001")
        verifyNoMoreInteractions(acts)
    }

    @Test
    fun `PM-SEC-004 a tie that is not made is not recorded as made — a subject that is none, a login tied already`() {
        assertThatThrownBy { administration.tie(operator, " ", ivan) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { administration.tie(operator, "x".repeat(SUBJECT_MAX + 1), ivan) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { administration.tie(operator, "login-ivan", null) }.isInstanceOf(IllegalArgumentException::class.java)
        verifyNoInteractions(logins)
        whenever(logins.tie(ISSUER, "login-ivan", ivan)).thenThrow(LoginAlreadyTied("tied already"))
        assertThatThrownBy { administration.tie(operator, "login-ivan", ivan) }.isInstanceOf(LoginAlreadyTied::class.java)
        verifyNoInteractions(acts)
    }

    @Test
    fun `PM-SEC-001 an administrator's login is never tied to a party — not their own, not another administrator's — so it never holds a party's roles`() {
        for (subject in listOf("operator", "second-operator"))
            assertThatThrownBy { administration.tie(operator, subject, ivan) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("administrator")
        verifyNoInteractions(logins, acts)
    }

    @Test
    fun `PM-SEC-004 an act is a login's — an allowance with no login to put on the record is a refusal`() {
        val anyone = object : RoleSource {
            override fun held(who: Asking, entranceId: UUID, asAt: LocalDate): Set<Held> = emptySet()
            override fun administers(who: Asking, asAt: LocalDate) = true
        }
        val trusting = LoginAdministration(logins, Policy(listOf(anyone)), administrators, acts, Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC))
        val noLogin = Asking(null, UUID.randomUUID())
        assertThat(trusting.tie(noLogin, "login-ivan", ivan)).isEqualTo(LoginAnswer.Refused("PM-SEC-001"))
        assertThat(trusting.untie(noLogin, "login-ivan", null)).isEqualTo(LoginAnswer.Refused("PM-SEC-001"))
        verifyNoInteractions(logins)
    }

    @Test
    fun `the decision is asked as at today in Sofia`() {
        administration.tie(manager, "login-ivan", ivan)
        assertThat(asked).isEmpty()                                                                // a deployment's act: no entrance is asked about
        val dated = object : RoleSource {
            override fun held(who: Asking, entranceId: UUID, asAt: LocalDate): Set<Held> = emptySet()
            override fun administers(who: Asking, asAt: LocalDate) = (asAt == LocalDate.parse("2026-06-01")).also { asked += asAt }
        }
        val sofia = LoginAdministration(logins, Policy(listOf(dated)), administrators, acts, Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC))
        assertThat(sofia.tie(operator, "login-ivan", ivan)).isInstanceOf(LoginAnswer.Done::class.java)
        assertThat(asked).containsOnly(LocalDate.parse("2026-06-01"))
    }
}
