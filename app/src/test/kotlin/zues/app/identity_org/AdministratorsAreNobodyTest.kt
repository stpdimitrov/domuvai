package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Decision
import zues.app.policy.Held
import zues.app.policy.Login
import zues.app.policy.Policy
import zues.app.policy.Resource
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private const val AN_ISSUER = "https://id.example.test/realms/domuvai"

/**
 * An administrator's login is nobody in the book, whichever came first (the audit's finding 6): a deployment that names
 * a tied login does not start, and a tied login never administers — it keeps its party's roles and no more. No
 * database: the record of ties is a mock, and the application context is a real one, small.
 */
class AdministratorsAreNobodyTest {

    private val today = LocalDate.parse("2026-06-01")
    private val entrance = UUID.randomUUID()
    private val operator = Login(AN_ISSUER, "operator")
    private val managersParty = UUID.randomUUID()

    /** The record of ties the last deployment was given — its own, made for it. */
    private lateinit var ties: Logins

    private fun deployment(admins: String, issuer: String = AN_ISSUER, tied: Map<String, UUID> = emptyMap()): ApplicationContextRunner {
        ties = mock()
        tied.forEach { (subject, party) -> whenever(ties.partyOf(AN_ISSUER, subject)).thenReturn(party) }
        return ApplicationContextRunner().withBean(Logins::class.java, { ties }).withUserConfiguration(Administrators::class.java, AdministratorsAreNobody::class.java)
            .withPropertyValues("domuvai.auth.issuer-uri=$issuer", "domuvai.auth.admins=$admins")
    }

    @Test
    fun `a deployment that names a login tied to a party does not start — and says which, and what to do`() {
        // the manager's subject was added to the list: one login would read the book and say who everybody is
        deployment("operator, login-manager, second-operator", tied = mapOf("login-manager" to managersParty)).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).isInstanceOf(AdministratorIsTied::class.java)
                .hasMessageContaining("login-manager").hasMessageContaining("DOMUVAI_AUTH_ADMINS").hasMessageContaining("Untie")
            assertThat(context.startupFailure!!.message).doesNotContain("second-operator").doesNotContain(managersParty.toString())   // only the login at fault, and nobody's id in the book
        }
        deployment("b-tied,a-tied,free", tied = mapOf("a-tied" to UUID.randomUUID(), "b-tied" to UUID.randomUUID())).run { context ->
            assertThat(context.startupFailure).hasMessageContaining("2 login(s)").hasMessageContaining("a-tied, b-tied")
        }
    }

    @Test
    fun `a deployment whose administrators are tied to nobody starts — each asked of the api's own record, under its own issuer`() {
        deployment("operator, second-operator").run { context ->
            assertThat(context).hasNotFailed()
            verify(ties).partyOf(AN_ISSUER, "operator")
            verify(ties).partyOf(AN_ISSUER, "second-operator")
            assertThat(context.getBean(Administrators::class.java).administers(Asking(operator, null), today)).isTrue()
        }
        // nobody named: nothing to ask. Sign-in switched off: no issuer, no administrators, nothing to ask.
        deployment("").run { context -> assertThat(context).hasNotFailed(); verifyNoInteractions(ties) }
        deployment("", issuer = "").run { context -> assertThat(context).hasNotFailed(); verify(ties, never()).partyOf(any(), any()) }
    }

    @Test
    fun `a deployment that cannot read its record of ties does not start either`() {
        val runner = deployment("operator")
        whenever(ties.partyOf(AN_ISSUER, "operator")).thenThrow(IllegalStateException("the database is away"))
        runner.run { context -> assertThat(context).hasFailed(); assertThat(context.startupFailure).hasMessageContaining("the database is away") }
    }

    @Test
    fun `PM-SEC-001 a login tied to a party never administers, named or not — it is refused both of the administrator's acts`() {
        // The tie was made after start, by a path that does not ask: the start-up check has already run.
        val administrators = Administrators(AN_ISSUER, "operator")
        val mandates = RoleSource { who, at, _ -> if (who.party == managersParty) setOf(Held(Role.BM, at)) else emptySet() }
        val policy = Policy(listOf(mandates, administrators))
        val untied = Asking(operator, null)
        val tied = Asking(operator, managersParty)

        assertThat(administrators.administers(untied, today)).isTrue()
        assertThat(administrators.administers(tied, today)).isFalse()
        assertThat(policy.administers(tied, today)).isFalse()
        for ((action, resource) in listOf(Action.TIE_LOGIN to Resource.Deployment, Action.RECORD_MANDATE to Resource.OfEntrance(entrance))) {
            assertThat(policy.decide(untied, action, resource, today).allowed).describedAs("$action, untied").isTrue()
            assertThat(policy.decide(tied, action, resource, today)).describedAs("$action, tied").isEqualTo(Decision(false, "PM-SEC-001", null, Policy.MATRIX_VERSION))
        }
    }

    @Test
    fun `PM-BOOK-006 one login, one kind of power — tied, it reads the book as the manager it is and administers nothing, untied, the reverse`() {
        val administrators = Administrators(AN_ISSUER, "operator")
        val mandates = RoleSource { who, at, _ -> if (who.party == managersParty) setOf(Held(Role.BM, at)) else emptySet() }
        val policy = Policy(listOf(mandates, administrators))
        val untied = Asking(operator, null)
        val tied = Asking(operator, managersParty)
        assertThat(policy.decide(tied, Action.TIE_LOGIN, Resource.Deployment, today).allowed).isFalse()
        assertThat(policy.decide(untied, Action.TIE_LOGIN, Resource.Deployment, today).allowed).isTrue()
        assertThat(policy.decide(tied, Action.READ_BOOK, Resource.OfEntrance(entrance), today)).isEqualTo(Decision(true, "PM-BOOK-006", Role.BM, Policy.MATRIX_VERSION))
        assertThat(policy.decide(untied, Action.READ_BOOK, Resource.OfEntrance(entrance), today).allowed).isFalse()
    }

    @Test
    fun `PM-SEC-001 the services refuse a named login that carries a party — nothing is tied and no mandate recorded by it`() {
        val administrators = Administrators(AN_ISSUER, "operator")
        val policy = Policy(listOf(administrators))
        val clock = Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC)
        val logins: Logins = mock(); val loginActs: LoginActLog = mock(); val store: MandateStore = mock(); val mandateActs: MandateActLog = mock()
        val tied = Asking(operator, managersParty)
        assertThat(LoginAdministration(logins, policy, administrators, loginActs, clock).tie(tied, "login-x", UUID.randomUUID())).isEqualTo(LoginAnswer.Refused("PM-SEC-001"))
        assertThat(LoginAdministration(logins, policy, administrators, loginActs, clock).untie(tied, "login-manager", null)).isEqualTo(LoginAnswer.Refused("PM-SEC-001"))
        assertThat(MandateAdministration(store, policy, mandateActs, clock).record(tied, entrance, RecordMandate("BM", managersParty, today, today.plusMonths(6), "Протокол")))
            .isEqualTo(MandateAnswer.Refused("PM-SEC-001"))
        verifyNoInteractions(logins, store)
    }
}
