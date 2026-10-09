package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import zues.app.policy.Role
import java.time.LocalDate
import java.util.UUID

/** When a mandate counts (PM-GOV-004): from its first day, up to its end — and past it, for the manager and the board, until a successor. */
class MandateTest {

    private val from = LocalDate.of(2024, 3, 1)
    private val to = LocalDate.of(2026, 3, 1)

    private val block = UUID.randomUUID()

    private fun mandate(body: String, succeededAt: LocalDate? = null, from: LocalDate = this.from, to: LocalDate = this.to, at: UUID = block) =
        Mandate(UUID.randomUUID(), at, body, UUID.randomUUID(), from, to, succeededAt)

    private fun Mandate.inForceOn(date: LocalDate) = inForceOn(date, listOf(this))

    @Test
    fun `PM-GOV-004 a mandate counts from its first day and through its last, never before`() {
        for (body in listOf("BM", "MB", "CTL", "CSH")) {
            val m = mandate(body)
            assertThat(m.inForceOn(from.minusDays(1))).describedAs(body).isFalse()
            assertThat(m.inForceOn(from)).describedAs(body).isTrue()
            assertThat(m.inForceOn(to.minusDays(1))).describedAs(body).isTrue()
        }
    }

    @Test
    fun `PM-GOV-004 the manager and the board continue in office past the mandate's end until a successor takes over`() {
        for (body in listOf("BM", "MB")) {
            assertThat(mandate(body).inForceOn(to)).describedAs(body).isTrue()
            assertThat(mandate(body).inForceOn(to.plusYears(3))).describedAs(body).isTrue()
            val succeeded = mandate(body, succeededAt = to.plusMonths(2))
            assertThat(succeeded.inForceOn(to.plusMonths(2).minusDays(1))).describedAs(body).isTrue()
            assertThat(succeeded.inForceOn(to.plusMonths(2))).describedAs(body).isFalse()
            assertThat(succeeded.inForceOn(to.plusYears(3))).describedAs(body).isFalse()
        }
    }

    @Test
    fun `the controller and the cashier do not continue — the rule names the board and the manager`() {
        for (body in listOf("CTL", "CSH")) {
            assertThat(mandate(body).inForceOn(to)).describedAs(body).isFalse()
            assertThat(mandate(body).inForceOn(to.plusYears(1))).describedAs(body).isFalse()
        }
    }

    @Test
    fun `a successor recorded before the mandate's end ends it that day, whatever the office`() {
        val early = from.plusMonths(6)
        for (body in listOf("BM", "MB", "CTL", "CSH")) {
            val m = mandate(body, succeededAt = early)
            assertThat(m.inForceOn(early.minusDays(1))).describedAs(body).isTrue()
            assertThat(m.inForceOn(early)).describedAs(body).isFalse()
            assertThat(m.inForceOn(to.minusDays(1))).describedAs(body).isFalse()
        }
    }

    @Test
    fun `PM-GOV-004 a later executive mandate of the entrance is the successor, though nobody recorded it on the old one`() {
        for (old in listOf("BM", "MB")) for (next in listOf("BM", "MB", "PMC")) {
            val incumbent = mandate(old)
            val successor = mandate(next, from = to.plusMonths(2), to = to.plusMonths(26))     // a gap of two months: the incumbent fills it
            val all = listOf(incumbent, successor)
            assertThat(incumbent.inForceOn(to.plusMonths(2).minusDays(1), all)).describedAs("$old then $next").isTrue()
            assertThat(incumbent.inForceOn(to.plusMonths(2), all)).describedAs("$old then $next").isFalse()
            assertThat(incumbent.inForceOn(to.plusYears(5), all)).describedAs("$old then $next").isFalse()
        }
    }

    @Test
    fun `PM-GOV-004 only a successor ends the stay — not a controller's or a cashier's mandate, another entrance's, an earlier one or one not yet started`() {
        val incumbent = mandate("BM")
        val later = to.plusMonths(2)
        val notSuccessors = listOf(
            mandate("CTL", from = later, to = later.plusYears(2)), mandate("CSH", from = later, to = later.plusYears(2)),
            mandate("BM", from = later, to = later.plusYears(2), at = UUID.randomUUID()),
            mandate("BM", from = from.minusYears(2), to = from), mandate("MB"),               // before it, and beside it
            mandate("BM", from = to.plusYears(1), to = to.plusYears(3)),                       // not yet started on the day asked about
        )
        assertThat(incumbent.inForceOn(to.plusMonths(6), notSuccessors + incumbent)).isTrue()
    }

    @Test
    fun `a successor elected early does not cut a mandate short — within its dates only a recorded day does`() {
        val incumbent = mandate("BM")
        val early = mandate("BM", from = from.plusYears(1), to = from.plusYears(3))
        assertThat(incumbent.inForceOn(to.minusDays(1), listOf(incumbent, early))).isTrue()
        assertThat(incumbent.inForceOn(to, listOf(incumbent, early))).isFalse()                // and it does not continue past its end
    }

    @Test
    fun `an office is its role, and the firm's own mandate is nobody's role`() {
        assertThat(listOf("BM", "MB", "CTL", "CSH").map { mandate(it).role }).containsExactly(Role.BM, Role.MB, Role.CTL, Role.CSH)
        assertThat(mandate("PMC").role).isNull()
        assertThat(mandate("PMC").inForceOn(to)).isFalse()                              // and it does not continue either
    }
}
