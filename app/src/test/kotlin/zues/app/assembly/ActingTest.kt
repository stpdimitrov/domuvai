package zues.app.assembly

import zues.app.identity_org.Succession
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.mockito.kotlin.mock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import zues.app.policy.Action
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

/** Who acts, over the real policy and roles the test dates: the answer is the sign-in's party or a refusal — nothing between. */
class ActingTest {

    private val entrance = UUID.randomUUID()
    private val issuer = "https://id.example.test/realms/domuvai"

    private data class Dated(val party: UUID, val entranceId: UUID, val role: Role, val from: LocalDate, val until: LocalDate?)
    private val roles = mutableListOf<Dated>()
    private val policy = Policy(
        listOf(
            RoleSource { who, entranceId, asAt ->
                roles.filter { it.party == who.party && it.entranceId == entranceId && !asAt.isBefore(it.from) && (it.until == null || asAt.isBefore(it.until)) }
                    .mapTo(mutableSetOf()) { Held(it.role, entranceId, setOf(UUID.randomUUID())) }
            },
        ),
    )
    // 00:30 on 10 October in Sofia; still the 9th in UTC
    private val succession: Succession = mock()
    private val acting = Acting(policy, succession, Clock.fixed(Instant.parse("2026-10-09T21:30:00Z"), ZoneOffset.UTC))

    private fun person(role: Role? = null, at: UUID = entrance, from: String = "2026-01-01", until: String? = null): Asking {
        val party = UUID.randomUUID()
        if (role != null) roles += Dated(party, at, role, LocalDate.parse(from), until?.let(LocalDate::parse))
        return Asking(Login(issuer, "login-$party"), party)
    }

    private fun refused(who: Asking, action: Action = Action.SIGN_PETITION) =
        assertThatThrownBy { acting.actor(who, action, entrance) }.isInstanceOf(ActRefused::class.java).extracting { (it as ActRefused).ruleId }

    @Test
    fun `PM-SEC-001 the one who acts is the sign-in's party, when the policy allows it today in Sofia`() {
        val owner = person(Role.OWN)
        val actor = Actor(owner.party!!, issuer, owner.login!!.subject)             // the party, and the sign-in behind it
        assertThat(acting.actor(owner, Action.SIGN_PETITION, entrance)).isEqualTo(actor)
        assertThat(acting.actor(owner, Action.CONVENE_ON_PETITION, entrance)).isEqualTo(actor)

        refused(person(Role.OWN, until = "2026-10-10")).isEqualTo("PM-GA-003")       // sold on the 10th: not an owner today in Sofia
        assertThat(acting.actor(person(Role.OWN, from = "2026-10-10"), Action.SIGN_PETITION, entrance)).isNotNull()   // bought today in Sofia
    }

    @Test
    fun `PM-SEC-001 whoever the policy does not allow is refused with the rule - another role, another entrance, nobody`() {
        for (role in Role.entries - Role.OWN) refused(person(role)).isEqualTo("PM-GA-003")
        refused(person(Role.OWN, at = UUID.randomUUID())).isEqualTo("PM-GA-003")     // an owner next door
        refused(person()).isEqualTo("PM-GA-003")                                     // a party who holds nothing
    }

    @Test
    fun `PM-SEC-001 a login tied to no party, a party with no sign-in, and nobody at all with sign-in switched off, act on nothing`() {
        refused(Asking(Login(issuer, "login-of-nobody"), null)).isEqualTo("PM-SEC-001")
        refused(Asking(null, null)).isEqualTo("PM-SEC-001")
        refused(Asking(null, null), Action.CONVENE_ON_PETITION).isEqualTo("PM-SEC-001")
        refused(Asking(null, person(Role.OWN).party)).isEqualTo("PM-SEC-001")        // an owner's party with no sign-in behind it
        // even a source that would answer for a login with no party: an act is a party's
        val generous = Acting(Policy(listOf(RoleSource { _, entranceId, _ -> setOf(Held(Role.OWN, entranceId)) })), mock(), Clock.systemUTC())
        assertThatThrownBy { generous.actor(Asking(Login(issuer, "login-of-nobody"), null), Action.SIGN_PETITION, entrance) }.isInstanceOf(ActRefused::class.java)
    }

    @Test
    fun `PM-GA-002 the office stated is one the party holds in the entrance today - not another office, not yesterday's, not next door's`() {
        val manager = person(Role.BM)
        assertThat(acting.officeHolder(manager, entrance, Role.BM)).isEqualTo(Actor(manager.party!!, issuer, manager.login!!.subject))
        for (other in listOf(Role.MB, Role.CTL)) {
            assertThatThrownBy { acting.officeHolder(manager, entrance, other) }.isInstanceOf(ActRefused::class.java).extracting { (it as ActRefused).ruleId }.isEqualTo("PM-GA-002")
        }
        fun refusedAs(who: Asking, office: Role = Role.BM) = assertThatThrownBy { acting.officeHolder(who, entrance, office) }.isInstanceOf(ActRefused::class.java)
        refusedAs(person(Role.BM, until = "2026-10-10"))                              // the mandate ended on the 10th: not the manager today in Sofia
        refusedAs(person(Role.BM, at = UUID.randomUUID()))
        refusedAs(person(Role.OWN))
        refusedAs(person(Role.CSH), Role.CSH)                                        // an office that convenes nothing, though held
        val managerAndCashier = person(Role.BM).also { roles += Dated(it.party!!, entrance, Role.CSH, LocalDate.parse("2026-01-01"), null) }
        refusedAs(managerAndCashier, Role.CSH)                                       // … even by somebody who may convene as something else
        val managerAndOwner = person(Role.BM).also { roles += Dated(it.party!!, entrance, Role.OWN, LocalDate.parse("2026-01-01"), null) }
        refusedAs(managerAndOwner, Role.OWN)
        refusedAs(Asking(null, null)).extracting { (it as ActRefused).ruleId }.isEqualTo("PM-SEC-001")
        // somebody holding two offices convenes in either, and only in those
        val both = person(Role.BM).also { roles += Dated(it.party!!, entrance, Role.CTL, LocalDate.parse("2026-01-01"), null) }
        assertThat(acting.officeHolder(both, entrance, Role.CTL).party).isEqualTo(both.party)
        refusedAs(both, Role.MB)
    }

    @Test
    fun `PM-SEC-001 of several actions the answer says which this party may do, and refuses under the first one's rule when none`() {
        val manager = person(Role.BM)
        val owner = person(Role.OWN)
        fun asked(who: Asking) = acting.allowedAny(who, entrance, Action.CONVENE_ASSEMBLY, Action.CONVENE_ON_PETITION)
        assertThat(asked(manager).actions).containsExactly(Action.CONVENE_ASSEMBLY)
        assertThat(asked(owner).actions).containsExactly(Action.CONVENE_ON_PETITION)
        assertThat(asked(owner).actor.party).isEqualTo(owner.party)
        val ownerAndManager = person(Role.BM).also { roles += Dated(it.party!!, entrance, Role.OWN, LocalDate.parse("2026-01-01"), null) }
        assertThat(asked(ownerAndManager).actions).containsExactlyInAnyOrder(Action.CONVENE_ASSEMBLY, Action.CONVENE_ON_PETITION)
        assertThatThrownBy { asked(person(Role.CSH)) }.isInstanceOf(ActRefused::class.java).extracting { (it as ActRefused).ruleId }.isEqualTo("PM-GA-002")
        assertThatThrownBy { asked(Asking(Login(issuer, "login-of-nobody"), null)) }.isInstanceOf(ActRefused::class.java).extracting { (it as ActRefused).ruleId }.isEqualTo("PM-SEC-001")
    }

    @Test
    fun `PM-GOV-004 who succeeded whom is identity_org's answer, asked as at today in Sofia - never worked out here`() {
        val (candidate, convenor) = listOf(UUID.randomUUID(), UUID.randomUUID())
        whenever(succession.hasSucceeded(candidate, convenor, entrance, Role.BM, LocalDate.parse("2026-10-10"))).thenReturn(true)
        assertThat(acting.hasSucceeded(candidate, convenor, entrance, Role.BM)).isTrue()
        assertThat(acting.hasSucceeded(convenor, candidate, entrance, Role.BM)).isFalse()
        verify(succession).hasSucceeded(candidate, convenor, entrance, Role.BM, LocalDate.parse("2026-10-10"))
    }
}
