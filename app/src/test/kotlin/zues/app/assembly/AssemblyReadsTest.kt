package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Login
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.time.Clock
import java.util.UUID

/** Who reads an entrance's assemblies (the owner's decision of 2026-10-22, R1 on #247): the real policy over stated roles; no database. */
class AssemblyReadsTest {

    private val entrance = UUID.randomUUID()
    private val held = mutableMapOf<UUID, Set<Held>>()
    private val policy = Policy(listOf(RoleSource { who, entranceId, _ -> held[who.party].orEmpty().filter { it.entranceId == entranceId }.toSet() }))
    private val reads = AssemblyReads(policy, Clock.systemUTC())
    private val open = AssemblyReads(policy, Clock.systemUTC(), mode = "off")

    private fun person(vararg roles: Held): Asking {
        val party = UUID.randomUUID()
        held[party] = roles.toSet()
        return Asking(Login("https://id.example.test", "s-$party"), party)
    }

    private fun holding(role: Role) = person(Held(role, entrance, setOf(UUID.randomUUID())))

    private fun refused(who: Asking) =
        assertThatThrownBy { reads.require(entrance, who) }.isInstanceOfSatisfying(ActRefused::class.java) { assertThat(it.ruleId).isEqualTo("PM-SEC-001") }

    @Test
    fun `PM-SEC-001 an owner, a holder of use, the manager, the board, the controller and the cashier read the entrance's assemblies`() {
        for (role in listOf(Role.OWN, Role.USR, Role.BM, Role.MB, Role.CTL, Role.CSH)) reads.require(entrance, holding(role))
    }

    @Test
    fun `PM-SEC-001 an occupant, a firm's staff, the municipality, a role elsewhere, a login tied to nobody and nobody at all read none`() {
        for (role in listOf(Role.OCC, Role.PMC_STAFF, Role.MUN)) refused(holding(role))
        val elsewhere = UUID.randomUUID()
        refused(person(Held(Role.OWN, elsewhere, setOf(UUID.randomUUID())), Held(Role.BM, elsewhere)))
        refused(Asking(Login("https://id.example.test", "nobody-in-the-book"), null))
        refused(Asking(null, null))
    }

    @Test
    fun `PM-SEC-002 the owner behind a higher quorum is named to the manager, the board and the controller - to no owner, holder of use or cashier`() {
        assertThat(listOf(Role.BM, Role.MB, Role.CTL).map { reads.seesConcentration(entrance, holding(it)) }).containsOnly(true)
        assertThat(listOf(Role.OWN, Role.USR, Role.CSH, Role.OCC).map { reads.seesConcentration(entrance, holding(it)) }).containsOnly(false)
        assertThat(reads.seesConcentration(entrance, person(Held(Role.BM, UUID.randomUUID())))).isFalse()      // an office elsewhere names nobody here
    }

    @Test
    fun `PM-GOV-008 the controller reads the assemblies and who holds the share behind a higher quorum`() {
        val controller = holding(Role.CTL)
        reads.require(entrance, controller)
        assertThat(reads.seesConcentration(entrance, controller)).isTrue()
    }

    @Test
    fun `with sign-in switched off the reads are served as before - the concentration named - and refused to nobody when it is on`() {
        open.require(entrance, Asking(null, null))
        assertThat(open.seesConcentration(entrance, Asking(null, null))).isTrue()
        assertThat(reads.seesConcentration(entrance, Asking(null, null))).isFalse()
        assertThatThrownBy { open.require(entrance, holding(Role.OCC)) }.isInstanceOf(ActRefused::class.java)    // off is nobody asking, not anybody allowed
    }
}
