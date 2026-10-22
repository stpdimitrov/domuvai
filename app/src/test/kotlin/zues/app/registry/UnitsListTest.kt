package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Login
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/** Who reads an entrance's units (PM-SEC-001; the owner's decision of 2026-10-21, #251): the real policy over stated roles; no database. */
class UnitsListTest {

    private val entrance = UUID.randomUUID()
    private val flat = UUID.randomUUID()
    private val held = mutableMapOf<UUID, Set<Held>>()
    private val policy = Policy(listOf(RoleSource { who, entranceId, _ -> held[who.party].orEmpty().filter { it.entranceId == entranceId }.toSet() }))
    private val registry: RegistryService = mock()
    private val book = BookEntries(registry, mock(), policy, Clock.systemUTC())
    private val open = BookEntries(registry, mock(), policy, Clock.systemUTC(), mode = "off")
    private val units = listOf(PropertyUnit(flat, entrance, "ап. 1", "APARTMENT", null, BigDecimal("100.0000"), separateEntrance = false))

    init {
        whenever(registry.listUnits(entrance)).thenReturn(units)
    }

    private fun person(vararg roles: Held): Asking {
        val party = UUID.randomUUID()
        held[party] = roles.toSet()
        return Asking(Login("https://id.example.test", "s-$party"), party)
    }

    private fun refused(who: Asking) =
        assertThatThrownBy { book.listUnits(entrance, who) }.isInstanceOfSatisfying(BookReadForbidden::class.java) { assertThat(it.ruleId).isEqualTo("PM-SEC-001") }

    @Test
    fun `PM-SEC-001 an owner, a holder of use, the manager, the board, the controller and the cashier read the entrance's units`() {
        for (role in listOf(Role.OWN, Role.USR, Role.BM, Role.MB, Role.CTL, Role.CSH)) {
            assertThat(book.listUnits(entrance, person(Held(role, entrance, setOf(flat))))).describedAs("$role").isEqualTo(units)
        }
    }

    @Test
    fun `PM-SEC-001 an occupant, a firm's staff, the municipality, a role elsewhere and a login tied to nobody read nothing — and the list is not read`() {
        for (role in listOf(Role.OCC, Role.PMC_STAFF, Role.MUN)) refused(person(Held(role, entrance, setOf(flat))))
        val elsewhere = UUID.randomUUID()
        refused(person(Held(Role.OWN, elsewhere, setOf(UUID.randomUUID())), Held(Role.BM, elsewhere)))
        refused(Asking(Login("https://id.example.test", "nobody-in-the-book"), null))
        verify(registry, never()).listUnits(entrance)
    }

    @Test
    fun `with sign-in switched off the list is served, and refused to nobody when it is on`() {
        assertThat(open.listUnits(entrance, Asking(null, null))).isEqualTo(units)
        refused(Asking(null, null))
    }
}
