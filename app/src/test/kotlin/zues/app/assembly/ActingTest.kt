package zues.app.assembly

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
    private val acting = Acting(policy, Clock.fixed(Instant.parse("2026-10-09T21:30:00Z"), ZoneOffset.UTC))

    private fun person(role: Role? = null, at: UUID = entrance, from: String = "2026-01-01", until: String? = null): Asking {
        val party = UUID.randomUUID()
        if (role != null) roles += Dated(party, at, role, LocalDate.parse(from), until?.let(LocalDate::parse))
        return Asking(Login(issuer, "login-$party"), party)
    }

    private fun refused(who: Asking, action: Action = Action.SIGN_PETITION) =
        assertThatThrownBy { acting.party(who, action, entrance) }.isInstanceOf(ActRefused::class.java).extracting { (it as ActRefused).ruleId }

    @Test
    fun `PM-SEC-001 the one who acts is the sign-in's party, when the policy allows it today in Sofia`() {
        val owner = person(Role.OWN)
        assertThat(acting.party(owner, Action.SIGN_PETITION, entrance)).isEqualTo(owner.party)
        assertThat(acting.party(owner, Action.CONVENE_ON_PETITION, entrance)).isEqualTo(owner.party)

        refused(person(Role.OWN, until = "2026-10-10")).isEqualTo("PM-GA-003")       // sold on the 10th: not an owner today in Sofia
        assertThat(acting.party(person(Role.OWN, from = "2026-10-10"), Action.SIGN_PETITION, entrance)).isNotNull()   // bought today in Sofia
    }

    @Test
    fun `PM-SEC-001 whoever the policy does not allow is refused with the rule - another role, another entrance, nobody`() {
        for (role in Role.entries - Role.OWN) refused(person(role)).isEqualTo("PM-GA-003")
        refused(person(Role.OWN, at = UUID.randomUUID())).isEqualTo("PM-GA-003")     // an owner next door
        refused(person()).isEqualTo("PM-GA-003")                                     // a party who holds nothing
    }

    @Test
    fun `PM-SEC-001 a login tied to no party, and nobody at all with sign-in switched off, act on nothing`() {
        refused(Asking(Login(issuer, "login-of-nobody"), null)).isEqualTo("PM-GA-003")
        refused(Asking(null, null)).isEqualTo("PM-GA-003")
        refused(Asking(null, null), Action.CONVENE_ON_PETITION).isEqualTo("PM-GA-003")
        // even a source that would answer for a login with no party: an act is a party's
        val generous = Acting(Policy(listOf(RoleSource { _, entranceId, _ -> setOf(Held(Role.OWN, entranceId)) })), Clock.systemUTC())
        assertThatThrownBy { generous.party(Asking(Login(issuer, "login-of-nobody"), null), Action.SIGN_PETITION, entrance) }.isInstanceOf(ActRefused::class.java)
    }
}
