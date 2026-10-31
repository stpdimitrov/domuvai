package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Login
import zues.app.policy.Policy
import zues.app.policy.Role
import zues.app.policy.RoleSource
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Who reads money's personal data (PM-SEC-002, ADR-002): the real policy over a stated set of roles; no database. */
class MoneyAccessTest {

    private val entrance = UUID.randomUUID()
    private val flat1 = UUID.randomUUID()
    private val flat2 = UUID.randomUUID()
    private val held = mutableMapOf<UUID, Set<Held>>()
    private val policy = Policy(listOf(RoleSource { who, entranceId, _ -> held[who.party].orEmpty().filter { it.entranceId == entranceId }.toSet() }))
    private val postings: PostingRepository = mock()
    private val access = MoneyAccess(policy, postings, Clock.systemUTC())
    private val open = MoneyAccess(policy, postings, Clock.systemUTC(), mode = "off")

    init {
        listOf(flat1, flat2).forEach { unit ->
            whenever(postings.findFirstByUnitId(unit)).thenReturn(PostingRow(UUID.randomUUID(), entrance, UUID.randomUUID(), Ledger.RECEIVABLE, unit, 100, "EUR", LocalDate.parse("2026-05-01")))
        }
    }

    private fun person(vararg roles: Held): Asking {
        val party = UUID.randomUUID()
        held[party] = roles.toSet()
        return Asking(Login("https://id.example.test", "s-$party"), party)
    }

    private fun refusedBy(rule: String, block: () -> Unit) =
        assertThatThrownBy(block).isInstanceOfSatisfying(MoneyReadForbidden::class.java) { assert(it.ruleId == rule) { it.ruleId } }

    @Test
    fun `PM-SEC-002 an owner reads their own unit's charges and nobody else's`() {
        val owner = person(Held(Role.OWN, entrance, setOf(flat1)))
        assertThatCode { access.unitCharges(flat1, owner) }.doesNotThrowAnyException()
        refusedBy("PM-SEC-002") { access.unitCharges(flat2, owner) }
    }

    @Test
    fun `PM-SEC-002 an owner is refused the entrance's arrears — other persons' debts`() {
        refusedBy("PM-SEC-002") { access.entranceArrears(entrance, person(Held(Role.OWN, entrance, setOf(flat1, flat2)))) }
    }

    @Test
    fun `PM-DEBT-007 PM-DEBT-003 the manager and the board read every unit's charges and the entrance's arrears`() {
        for (office in listOf(Role.BM, Role.MB)) {
            val who = person(Held(office, entrance))
            assertThatCode { access.unitCharges(flat2, who); access.entranceArrears(entrance, who) }.describedAs("$office").doesNotThrowAnyException()
        }
    }

    @Test
    fun `PM-GOV-008 the controller reads them too`() {
        val controller = person(Held(Role.CTL, entrance))
        assertThatCode { access.unitCharges(flat1, controller); access.entranceArrears(entrance, controller) }.doesNotThrowAnyException()
    }

    @Test
    fun `PM-SEC-002 no rule gives the cashier, a holder of use, an occupant or a firm's staff anything — and an office elsewhere gives nothing here`() {
        for (role in listOf(Role.CSH, Role.USR, Role.OCC, Role.PMC_STAFF)) {
            val who = person(Held(role, entrance, setOf(flat1)))
            refusedBy("PM-SEC-002") { access.unitCharges(flat1, who) }
            refusedBy("PM-SEC-002") { access.entranceArrears(entrance, who) }
        }
        val elsewhere = person(Held(Role.BM, UUID.randomUUID()))
        refusedBy("PM-SEC-002") { access.unitCharges(flat1, elsewhere) }
        refusedBy("PM-SEC-002") { access.unitCharges(flat1, Asking(Login("https://id.example.test", "nobody-in-the-book"), null)) }
    }

    @Test
    fun `PM-SEC-002 a unit with nothing posted is refused, even to the manager — there is no entrance to decide on`() {
        refusedBy("PM-SEC-002") { access.unitCharges(UUID.randomUUID(), person(Held(Role.BM, entrance))) }
    }

    @Test
    fun `PM-SEC-002 a payment is decided on its unit, and one not there on the entrance alone`() {
        val owner = person(Held(Role.OWN, entrance, setOf(flat1)))
        fun paid(unit: UUID) = PaymentRow(UUID.randomUUID(), entrance, unit, 100, "EUR", LocalDate.parse("2026-05-20"), "CASH", "OLDEST_FIRST", null, JsonbValue("{}"), "h", "1.3", "0.1.0", "k")
        assertThatCode { access.payment(entrance, paid(flat1), owner) }.doesNotThrowAnyException()
        refusedBy("PM-SEC-002") { access.payment(entrance, paid(flat2), owner) }
        refusedBy("PM-SEC-002") { access.payment(entrance, null, owner) }                               // learns nothing of whether it exists
        assertThatCode { access.payment(entrance, null, person(Held(Role.BM, entrance))) }.doesNotThrowAnyException()   // the manager is told it is not there
    }

    @Test
    fun `with sign-in switched off nobody is asking and the reads are served, as everything else is in that mode`() {
        assertThatCode { open.unitCharges(flat1, Asking(null, null)); open.entranceArrears(entrance, Asking(null, null)) }.doesNotThrowAnyException()
        refusedBy("PM-SEC-002") { access.unitCharges(flat1, Asking(null, null)) }
    }

    @Test
    fun `PM-SEC-002 PM-SEC-001 PM-GOV-008 the fund is read by the manager, the board, the controller and the entrance's owners — a report of the condominium`() {
        for (who in listOf(Held(Role.BM, entrance), Held(Role.MB, entrance), Held(Role.CTL, entrance), Held(Role.OWN, entrance, setOf(flat2)))) {
            assertThatCode { access.fund(entrance, person(who)) }.describedAs("${who.role}").doesNotThrowAnyException()
        }
    }

    @Test
    fun `PM-SEC-002 nobody else reads the fund — the holder of use, an occupant, the cashier, a firm's staff, an owner or office elsewhere`() {
        for (role in listOf(Role.USR, Role.OCC, Role.CSH, Role.PMC_STAFF, Role.MUN)) {
            refusedBy("PM-SEC-002") { access.fund(entrance, person(Held(role, entrance, setOf(flat1)))) }
        }
        val elsewhere = UUID.randomUUID()
        refusedBy("PM-SEC-002") { access.fund(entrance, person(Held(Role.OWN, elsewhere, setOf(UUID.randomUUID())), Held(Role.BM, elsewhere))) }
        refusedBy("PM-SEC-002") { access.fund(entrance, Asking(Login("https://id.example.test", "nobody-in-the-book"), null)) }
    }

    @Test
    fun `PM-BOOK-006 what the book declares of each unit's persons is read as the book is — the offices and the controller, not an owner`() {
        for (office in listOf(Role.BM, Role.MB, Role.CTL)) {
            assertThatCode { access.book(entrance, person(Held(office, entrance))) }.describedAs("$office").doesNotThrowAnyException()
        }
        for (role in listOf(Role.OWN, Role.USR, Role.CSH)) refusedBy("PM-BOOK-006") { access.book(entrance, person(Held(role, entrance, setOf(flat1)))) }
        refusedBy("PM-BOOK-006") { access.book(entrance, person(Held(Role.BM, UUID.randomUUID()))) }
    }

    @Test
    fun `PM-SEC-002 who the fund's records name is seen by the manager, the board and the controller, not by an owner — unless the owner holds the office`() {
        for (office in listOf(Role.BM, Role.MB, Role.CTL)) assertThat(access.seesFundParties(entrance, person(Held(office, entrance)))).describedAs("$office").isTrue()
        assertThat(access.seesFundParties(entrance, person(Held(Role.OWN, entrance, setOf(flat1))))).isFalse()
        assertThat(access.seesFundParties(entrance, person(Held(Role.OWN, entrance, setOf(flat1)), Held(Role.MB, entrance)))).isTrue()
        assertThat(access.seesFundParties(entrance, person(Held(Role.BM, UUID.randomUUID())))).isFalse()
        assertThat(open.seesFundParties(entrance, Asking(null, null))).isTrue()
        assertThat(access.seesFundParties(entrance, Asking(null, null))).isFalse()
    }

    @Test
    fun `PM-FUND-007 who keeps the accounts is the sign-in's party, nobody with sign-in off, and a refusal when neither can be said`() {
        val manager = person(Held(Role.BM, entrance))
        assertThat(access.keeper(entrance, manager)).isEqualTo(manager.party)
        assertThat(open.keeper(entrance, Asking(null, null))).isNull()
        assertThatThrownBy { access.keeper(entrance, Asking(null, null)) }.isInstanceOf(MoneyWriteForbidden::class.java)
        assertThatThrownBy { access.keeper(entrance, person(Held(Role.CTL, entrance))) }.isInstanceOf(MoneyWriteForbidden::class.java)
    }

    @Test
    fun `with sign-in switched off the fund and the headcount are served too — and refused to nobody when it is on`() {
        assertThatCode { open.fund(entrance, Asking(null, null)); open.book(entrance, Asking(null, null)) }.doesNotThrowAnyException()
        refusedBy("PM-SEC-002") { access.fund(entrance, Asking(null, null)) }
        refusedBy("PM-BOOK-006") { access.book(entrance, Asking(null, null)) }
    }

    private fun keepingRefused(block: () -> Unit) =
        assertThatThrownBy(block).isInstanceOfSatisfying(MoneyWriteForbidden::class.java) { assert(it.ruleId == "PM-SEC-001") { it.ruleId } }

    @Test
    fun `PM-SEC-001 the entrance's accounts are kept by its manager and its board`() {
        for (office in listOf(Role.BM, Role.MB)) {
            val who = person(Held(office, entrance))
            assertThatCode { access.keep(entrance, who); access.keep(entrance.toString(), who) }.describedAs("$office").doesNotThrowAnyException()
        }
    }

    @Test
    fun `PM-SEC-001 nobody else keeps them — not the controller, the cashier, an owner, a holder of use, an occupant, a firm's staff, nor an office elsewhere`() {
        for (role in listOf(Role.CTL, Role.CSH, Role.OWN, Role.USR, Role.OCC, Role.PMC_STAFF, Role.MUN)) {
            val who = person(Held(role, entrance, setOf(flat1)))
            keepingRefused { access.keep(entrance, who) }
            keepingRefused { access.keep(entrance.toString(), who) }
        }
        keepingRefused { access.keep(entrance, person(Held(Role.BM, UUID.randomUUID()))) }
        keepingRefused { access.keep(entrance, Asking(Login("https://id.example.test", "nobody-in-the-book"), null)) }
    }

    @Test
    fun `PM-SEC-001 a run previewed for a body that names no entrance is kept by nobody — not even a manager`() {
        keepingRefused { access.keep("entrance-1", person(Held(Role.BM, entrance))) }
    }

    @Test
    fun `with sign-in switched off the accounts are kept as before — any label previewed — and refused to nobody when it is on`() {
        assertThatCode { open.keep(entrance, Asking(null, null)); open.keep("entrance-1", Asking(null, null)) }.doesNotThrowAnyException()
        keepingRefused { access.keep(entrance, Asking(null, null)) }
    }
}
