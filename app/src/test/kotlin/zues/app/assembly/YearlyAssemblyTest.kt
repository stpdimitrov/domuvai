package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import zues.law.constantOn
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The yearly assembly, from the record of sittings alone: the repository mocked, holding what each test says was weighed. */
class YearlyAssemblyTest {

    private val entranceId = UUID.randomUUID()
    private val weighed = mutableListOf<Sitting>()
    private val sittings: SittingRepository = mock {
        on { findByEntranceIdAndQuorumMetTrue(any()) } doAnswer { call -> weighed.filter { it.entranceId == call.arguments[0] && it.quorumMet } }
    }
    private val yearly = YearlyAssembly(sittings)

    private fun sitting(at: String, met: Boolean = true, n: Int = 1, assembly: UUID = UUID.randomUUID(), entrance: UUID = entranceId) = Sitting(
        UUID.randomUUID(), entrance, assembly, n, Instant.parse(at).takeIf { met }, BigDecimal("60"), met, Instant.parse(at), LocalDate.parse(at.take(10)),
        BigDecimal("51"), "GA_QUORUM_PCT@2009-01-01", true, "b", "ab".repeat(32), "v", "e", UUID.randomUUID(), "realm", "login",
    ).also { weighed += it }

    @Test
    fun `PM-GA-001 a year's assembly is held when a sitting opened on its quorum in it - none held is the flag's case`() {
        val required = constantOn("GA_ASSEMBLIES_PER_YEAR", "2026-12-31")
        assertThat(listOf(required.value, required.verified, required.source)).containsExactly("1", true, "чл. 12 ЗУЕС")
        assertThat(yearly.heldIn(entranceId, 2026).met).isFalse()                    // nothing on record: not held
        val assembly = UUID.randomUUID()
        sitting("2026-05-14T15:00:00Z", met = false, assembly = assembly)            // weighed short: not held
        sitting("2026-05-14T16:05:00Z", met = false, n = 2, assembly = assembly)
        assertThat(yearly.heldIn(entranceId, 2026).held).isZero()
        sitting("2026-05-15T15:00:00Z", n = 3, assembly = assembly)                  // the following day's sitting, at any share: held
        val year = yearly.heldIn(entranceId, 2026)
        assertThat(year.held).isEqualTo(1)
        assertThat(year.met).isTrue()
        assertThat(year.firstAssemblyId).isEqualTo(assembly)
        assertThat(year.firstHeldOn).isEqualTo(LocalDate.parse("2026-05-15"))
        assertThat(year.requiredSource).isEqualTo("чл. 12 ЗУЕС")
    }

    @Test
    fun `PM-GA-001 the year is the Sofia calendar year - a sitting opened at half past midnight on 1 January counts for the new one`() {
        val newYear = UUID.randomUUID()
        sitting("2026-12-31T22:30:00Z", assembly = newYear)                          // 00:30 on 1 January 2027 in Sofia
        assertThat(yearly.heldIn(entranceId, 2026).held).isZero()
        assertThat(yearly.heldIn(entranceId, 2027).firstHeldOn).isEqualTo(LocalDate.parse("2027-01-01"))
        val late = UUID.randomUUID()
        sitting("2026-12-31T21:30:00Z", assembly = late)                             // 23:30 on 31 December in Sofia
        assertThat(yearly.heldIn(entranceId, 2026).firstAssemblyId).isEqualTo(late)
    }

    @Test
    fun `PM-GA-001 two assemblies are counted as two, an assembly once however many of its sittings opened, and another entrance's never`() {
        val one = UUID.randomUUID()
        sitting("2026-03-01T15:00:00Z", assembly = one)
        sitting("2026-03-01T16:30:00Z", n = 2, assembly = one)                       // as no ladder allows, but an assembly counts once whatever the record holds
        sitting("2026-09-01T15:00:00Z", assembly = UUID.randomUUID())
        sitting("2026-10-01T15:00:00Z", entrance = UUID.randomUUID())
        val year = yearly.heldIn(entranceId, 2026)
        assertThat(year.held).isEqualTo(2)
        assertThat(year.firstAssemblyId).isEqualTo(one)                              // the first, by when it opened
        assertThat(yearly.heldIn(entranceId, 2025).held).isZero()
    }
}
