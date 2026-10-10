package zues.app.policy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** Who may do what (ADR-002), over roles a test hands in with their dates: the matrix, the default, the date and the unit. */
class PolicyTest {

    private val entrance = UUID.randomUUID()
    private val another = UUID.randomUUID()
    private val flat1 = UUID.randomUUID()
    private val flat2 = UUID.randomUUID()
    private val day = LocalDate.of(2026, 10, 9)

    private fun person() = Asking(Login("https://id.example.test/realms/domuvai", UUID.randomUUID().toString()), UUID.randomUUID())

    /** A holding as a test writes it: this party, this entrance, from a day up to and not including another. */
    private data class Dated(val party: UUID, val entranceId: UUID, val held: Held, val from: LocalDate, val until: LocalDate?)

    private val holdings = mutableListOf<Dated>()
    private val policy = Policy(
        listOf(
            RoleSource { who, entranceId, asAt ->
                holdings.filter { it.party == who.party && it.entranceId == entranceId && !asAt.isBefore(it.from) && (it.until == null || asAt.isBefore(it.until)) }
                    .mapTo(mutableSetOf()) { it.held }
            },
        ),
    )

    private fun holds(who: Asking, role: Role, units: Set<UUID> = emptySet(), from: LocalDate = day.minusYears(1), until: LocalDate? = null, at: UUID = entrance) {
        holdings += Dated(who.party!!, at, Held(role, at, units), from, until)
    }

    private fun holder(role: Role, units: Set<UUID> = emptySet()) = person().also { holds(it, role, units) }

    @Test
    fun `PM-SEC-001 the matrix is exactly these lines at this version — a change to it is a change to the number`() {
        val lines = Policy.MATRIX.map { "${it.action} ${it.role} ${it.ruleId}${if (it.ownUnitOnly) " own unit" else ""}" }
        assertThat(Policy.MATRIX_VERSION to lines).isEqualTo(
            2 to listOf(
                "READ_BOOK BM PM-BOOK-006",
                "READ_BOOK MB PM-BOOK-006",
                "READ_BOOK CTL PM-BOOK-006",
                "READ_UNIT_BOOK_DATA BM PM-BOOK-006",
                "READ_UNIT_BOOK_DATA MB PM-BOOK-006",
                "READ_UNIT_BOOK_DATA CTL PM-BOOK-006",
                "READ_UNIT_BOOK_DATA OWN PM-BOOK-006 own unit",
                "TIE_LOGIN SYS_ADMIN PM-SEC-001",
            ),
        )
        assertThat(Role.entries.map { it.name }).containsExactly("OWN", "USR", "OCC", "BM", "MB", "CTL", "CSH", "PMC_STAFF", "MUN", "SYS_ADMIN")
    }

    @Test
    fun `PM-SEC-001 every role and action the matrix does not name is refused, each decision naming a rule and the version`() {
        for (role in Role.entries) for (action in Action.entries) {
            val decision = policy.decide(holder(role), action, Resource.OfEntrance(entrance), day)
            // the administrator's lines are the deployment's: holding SYS_ADMIN "in an entrance" carries none of them
            val named = Policy.MATRIX.any { it.role == role && it.action == action && !it.ownUnitOnly && role != Role.SYS_ADMIN }
            assertThat(decision.allowed).describedAs("$role $action").isEqualTo(named)
            assertThat(decision.ruleId).describedAs("$role $action").matches("PM-[A-Z]+-\\d{3}")
            assertThat(decision.role).isEqualTo(if (named) role else null)
            assertThat(decision.matrixVersion).isEqualTo(Policy.MATRIX_VERSION)
        }
    }

    @Test
    fun `PM-BOOK-006 one unit's data is read by the manager, the board, the controller and its owner — no other role, though it holds that unit`() {
        val expected = mapOf(
            Role.OWN to true, Role.USR to false, Role.OCC to false, Role.BM to true, Role.MB to true, Role.CTL to true,
            Role.CSH to false, Role.PMC_STAFF to false, Role.MUN to false, Role.SYS_ADMIN to false,
        )
        for (role in Role.entries) {
            val decision = policy.decide(holder(role, setOf(flat1)), Action.READ_UNIT_BOOK_DATA, Resource.OfUnit(entrance, flat1), day)
            assertThat(decision).describedAs("$role").isEqualTo(Decision(expected.getValue(role), "PM-BOOK-006", role.takeIf { expected.getValue(it) }, 2))
        }
    }

    @Test
    fun `PM-BOOK-006 the manager, the board and the controller read the book — and nobody else does, the administrator included`() {
        for (role in listOf(Role.BM, Role.MB, Role.CTL))
            assertThat(policy.decide(holder(role), Action.READ_BOOK, Resource.OfEntrance(entrance), day)).isEqualTo(Decision(true, "PM-BOOK-006", role, 2))
        for (role in listOf(Role.OWN, Role.USR, Role.OCC, Role.CSH, Role.PMC_STAFF, Role.MUN, Role.SYS_ADMIN))
            assertThat(policy.decide(holder(role, setOf(flat1)), Action.READ_BOOK, Resource.OfEntrance(entrance), day)).isEqualTo(Decision(false, "PM-BOOK-006", null, 2))
    }

    @Test
    fun `PM-BOOK-006 a person who holds nothing, a login tied to no party and nobody at all are refused`() {
        holds(Asking(null, UUID.randomUUID()), Role.BM)                                 // somebody else is the manager
        val nobody = listOf(person(), Asking(Login("https://id.example.test/realms/domuvai", "untied"), null), Asking(null, null))
        for (who in nobody) for (action in Action.entries)
            assertThat(policy.decide(who, action, Resource.OfUnit(entrance, flat1), day)).isEqualTo(Decision(false, action.governedBy, null, 2))
        assertThat(Policy(emptyList()).decide(holder(Role.BM), Action.READ_BOOK, Resource.OfEntrance(entrance), day).allowed).isFalse()
    }

    @Test
    fun `nobody is asked about nobody — a source is not consulted when neither a login nor a party is asking`() {
        val anyone = Policy(listOf(RoleSource { _, at, _ -> setOf(Held(Role.BM, at)) }))
        assertThat(anyone.decide(Asking(null, null), Action.READ_BOOK, Resource.OfEntrance(entrance), day).allowed).isFalse()
        assertThat(anyone.rolesOf(Asking(null, null), entrance, day)).isEmpty()
    }

    @Test
    fun `PM-BOOK-006 a manager of one entrance reads nothing of another — even from a source that answers for the wrong entrance`() {
        val careless = Policy(listOf(RoleSource { _, _, _ -> setOf(Held(Role.BM, entrance), Held(Role.OWN, entrance, setOf(flat1))) }))
        assertThat(careless.decide(person(), Action.READ_BOOK, Resource.OfEntrance(entrance), day).allowed).isTrue()
        assertThat(careless.decide(person(), Action.READ_BOOK, Resource.OfEntrance(another), day)).isEqualTo(Decision(false, "PM-BOOK-006", null, 2))
        assertThat(careless.decide(person(), Action.READ_UNIT_BOOK_DATA, Resource.OfUnit(another, flat1), day).allowed).isFalse()
        assertThat(careless.rolesOf(person(), another, day)).isEmpty()
        val manager = holder(Role.BM)
        assertThat(policy.decide(manager, Action.READ_BOOK, Resource.OfEntrance(entrance), day).allowed).isTrue()
        assertThat(policy.decide(manager, Action.READ_BOOK, Resource.OfEntrance(another), day)).isEqualTo(Decision(false, "PM-BOOK-006", null, 2))
        assertThat(policy.decide(manager, Action.READ_UNIT_BOOK_DATA, Resource.OfUnit(another, flat1), day).allowed).isFalse()
    }

    @Test
    fun `PM-BOOK-006 an owner reads the data of a unit they hold — and not another owner's unit, nor the whole book`() {
        val ownerA = holder(Role.OWN, setOf(flat1))
        val ownerB = holder(Role.OWN, setOf(flat2))
        assertThat(policy.decide(ownerA, Action.READ_UNIT_BOOK_DATA, Resource.OfUnit(entrance, flat1), day)).isEqualTo(Decision(true, "PM-BOOK-006", Role.OWN, 2))
        assertThat(policy.decide(ownerA, Action.READ_UNIT_BOOK_DATA, Resource.OfUnit(entrance, flat2), day)).isEqualTo(Decision(false, "PM-BOOK-006", null, 2))
        assertThat(policy.decide(ownerB, Action.READ_UNIT_BOOK_DATA, Resource.OfUnit(entrance, flat2), day).allowed).isTrue()
        assertThat(policy.decide(ownerA, Action.READ_UNIT_BOOK_DATA, Resource.OfEntrance(entrance), day).allowed).isFalse()
        assertThat(policy.decide(ownerA, Action.READ_BOOK, Resource.OfEntrance(entrance), day).allowed).isFalse()
        // a user of a unit is not its owner: the rule names the owner
        assertThat(policy.decide(holder(Role.USR, setOf(flat1)), Action.READ_UNIT_BOOK_DATA, Resource.OfUnit(entrance, flat1), day).allowed).isFalse()
    }

    @Test
    fun `PM-BOOK-006 an owner who is also the manager reads every unit, their own as the manager`() {
        val both = holder(Role.OWN, setOf(flat1)).also { holds(it, Role.BM) }
        assertThat(policy.decide(both, Action.READ_UNIT_BOOK_DATA, Resource.OfUnit(entrance, flat2), day)).isEqualTo(Decision(true, "PM-BOOK-006", Role.BM, 2))
        assertThat(policy.decide(both, Action.READ_UNIT_BOOK_DATA, Resource.OfUnit(entrance, flat1), day)).isEqualTo(Decision(true, "PM-BOOK-006", Role.BM, 2))
        assertThat(policy.decide(both, Action.READ_BOOK, Resource.OfEntrance(entrance), day).allowed).isTrue()
    }

    @Test
    fun `the date asked about reaches the sources as given — a role a source dates is decided on that date, whatever today is`() {
        val manager = person().also { holds(it, Role.BM, from = LocalDate.of(2024, 3, 1), until = LocalDate.of(2025, 3, 1)) }
        fun on(date: LocalDate) = policy.decide(manager, Action.READ_BOOK, Resource.OfEntrance(entrance), date).allowed
        assertThat(on(LocalDate.of(2024, 2, 29))).isFalse()
        assertThat(on(LocalDate.of(2024, 3, 1))).isTrue()
        assertThat(on(LocalDate.of(2025, 2, 28))).isTrue()
        assertThat(on(LocalDate.of(2025, 3, 1))).isFalse()
        assertThat(on(day)).isFalse()
        assertThat(policy.rolesOf(manager, entrance, LocalDate.of(2024, 6, 1))).containsExactly(Held(Role.BM, entrance))
        assertThat(policy.rolesOf(manager, entrance, day)).isEmpty()
    }

    @Test
    fun `the roles of every source count — a role one source knows is not lost because another knows none`() {
        val who = person()
        val two = Policy(listOf(RoleSource { _, _, _ -> emptySet() }, RoleSource { asked, at, _ -> if (asked == who) setOf(Held(Role.CTL, at)) else emptySet() }))
        assertThat(two.decide(who, Action.READ_BOOK, Resource.OfEntrance(entrance), day)).isEqualTo(Decision(true, "PM-BOOK-006", Role.CTL, 2))
        assertThat(two.decide(person(), Action.READ_BOOK, Resource.OfEntrance(entrance), day).allowed).isFalse()
    }

    /** A deployment whose administrators are these logins, from a day on — beside the test's entrance holdings. */
    private fun withAdministrators(vararg admins: Asking, from: LocalDate = day.minusYears(1)): Policy = Policy(
        listOf(
            RoleSource { who, entranceId, asAt -> policy.rolesOf(who, entranceId, asAt) },
            object : RoleSource {
                override fun held(who: Asking, entranceId: UUID, asAt: LocalDate): Set<Held> = emptySet()
                override fun administers(who: Asking, asAt: LocalDate) = who in admins && !asAt.isBefore(from)
            },
        ),
    )

    @Test
    fun `PM-SEC-001 a login is tied by the deployment's administrator and by nobody else — whatever they hold in an entrance`() {
        val admin = Asking(Login("https://id.example.test/realms/domuvai", "operator"), null)      // a login; in nobody's book
        val deployment = withAdministrators(admin)
        assertThat(deployment.decide(admin, Action.TIE_LOGIN, Resource.Deployment, day)).isEqualTo(Decision(true, "PM-SEC-001", Role.SYS_ADMIN, 2))
        // the administrator's line is the deployment's: asked of an entrance or a unit, it carries nothing
        for (resource in listOf(Resource.OfEntrance(entrance), Resource.OfUnit(entrance, flat1)))
            assertThat(deployment.decide(admin, Action.TIE_LOGIN, resource, day)).isEqualTo(Decision(false, "PM-SEC-001", null, 2))
        for (role in Role.entries) {
            val holder = holder(role, setOf(flat1))
            for (resource in listOf(Resource.Deployment, Resource.OfEntrance(entrance), Resource.OfUnit(entrance, flat1)))
                assertThat(deployment.decide(holder, Action.TIE_LOGIN, resource, day)).describedAs("$role $resource").isEqualTo(Decision(false, "PM-SEC-001", null, 2))
        }
        for (nobody in listOf(person(), Asking(Login("https://id.example.test/realms/domuvai", "untied"), null), Asking(null, null)))
            assertThat(deployment.decide(nobody, Action.TIE_LOGIN, Resource.Deployment, day).allowed).isFalse()
        assertThat(deployment.decide(admin, Action.TIE_LOGIN, Resource.Deployment, day.minusYears(2)).allowed).isFalse()   // the date reaches this answer too
    }

    @Test
    fun `PM-SEC-001 an entrance's holding never makes an administrator — a source that answers SYS_ADMIN for an entrance is not believed`() {
        val careless = Policy(listOf(RoleSource { _, at, _ -> setOf(Held(Role.SYS_ADMIN, at), Held(Role.CSH, at)) }))
        val who = person()
        assertThat(careless.rolesOf(who, entrance, day)).containsExactly(Held(Role.CSH, entrance))
        assertThat(careless.administers(who, day)).isFalse()
        for (resource in listOf(Resource.Deployment, Resource.OfEntrance(entrance)))
            assertThat(careless.decide(who, Action.TIE_LOGIN, resource, day)).isEqualTo(Decision(false, "PM-SEC-001", null, 2))
    }

    @Test
    fun `PM-BOOK-006 the administrator reads nothing of the book, and nobody at all administers nothing`() {
        val admin = Asking(Login("https://id.example.test/realms/domuvai", "operator"), UUID.randomUUID())
        val deployment = withAdministrators(admin, Asking(null, null))
        assertThat(deployment.decide(admin, Action.READ_BOOK, Resource.OfEntrance(entrance), day)).isEqualTo(Decision(false, "PM-BOOK-006", null, 2))
        assertThat(deployment.decide(admin, Action.READ_UNIT_BOOK_DATA, Resource.OfUnit(entrance, flat1), day).allowed).isFalse()
        assertThat(deployment.decide(admin, Action.READ_BOOK, Resource.Deployment, day).allowed).isFalse()
        assertThat(deployment.decide(Asking(null, null), Action.TIE_LOGIN, Resource.Deployment, day).allowed).isFalse()
    }
}
