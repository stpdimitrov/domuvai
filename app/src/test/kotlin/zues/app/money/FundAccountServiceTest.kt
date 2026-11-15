package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.app.identity_org.MandateStanding
import zues.app.identity_org.Standing
import zues.app.identity_org.StandingKind
import zues.app.identity_org.StandingMandate
import zues.app.policy.Role
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The fund-account service with the repository and the aggregate template mocked — no Spring, no
 * database. Proves the validation and the no-commingling guards run in the gate pack locally; the
 * table's own constraints are proved end to end by the Docker-gated IT.
 */
class FundAccountServiceTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val accounts: FundAccountRepository = mock()
    private val standing: MandateStanding = mock()
    // Past midnight in Sofia, and still the day before in UTC.
    private val clock = Clock.fixed(Instant.parse("2026-08-31T21:30:00Z"), ZoneOffset.UTC)
    private val service = FundAccountService(aggregates, accounts, standing, clock)

    private val entranceId = UUID.randomUUID()
    private val iban = "BG80BNBG96611020345678"
    private val sofiaDay = LocalDate.parse("2026-09-01")
    private val holder = UUID.randomUUID()

    private fun command(
        iban: String = this.iban,
        purpose: String = "REPAIR_RENEWAL",
        holderName: String = "Иван Петров",
        holderKind: String = "ASSOCIATION",
        holderPartyId: String? = null,
    ) = RegisterFundAccount(iban, purpose, holderName, holderKind, holderPartyId)

    private val theManagers = command(holderKind = "MANAGER", holderPartyId = holder.toString())

    private fun office(role: Role, party: UUID? = holder, id: UUID = UUID.randomUUID()) =
        StandingMandate(id, party, role, sofiaDay.minusYears(1), sofiaDay.plusYears(1), Standing(StandingKind.IN_TERM))

    private fun stored(command: RegisterFundAccount): FundAccountRow {
        val captor = argumentCaptor<FundAccountRow>()
        whenever(aggregates.insert(captor.capture())).thenAnswer { it.getArgument<FundAccountRow>(0) }
        service.register(entranceId, command)
        return captor.firstValue
    }

    private fun assertRefusedByName(command: RegisterFundAccount) {
        assertThatThrownBy { service.register(entranceId, command) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-FUND-004")
        verify(aggregates, never()).insert(any<FundAccountRow>())
    }

    @Test
    fun `PM-FUND-001 a repair-and-renewal account is registered with a normalised IBAN`() {
        whenever(aggregates.insert(any<FundAccountRow>())).thenAnswer { it.getArgument<FundAccountRow>(0) }
        val result = service.register(entranceId, command(iban = "bg80 bnbg 9661 1020 3456 78"))
        assertThat(result.iban).isEqualTo(iban)                 // spaces stripped, upper-cased
        assertThat(result.purpose).isEqualTo("REPAIR_RENEWAL")
        assertThat(result.entranceId).isEqualTo(entranceId)
    }

    @Test
    fun `PM-FUND-005 a duplicate IBAN is refused, monies not commingled`() {
        whenever(accounts.existsByIban(iban)).thenReturn(true)
        assertThatThrownBy { service.register(entranceId, command()) }
            .isInstanceOf(FundAccountConflict::class.java)
    }

    @Test
    fun `PM-FUND-004 a second account of the same purpose is refused`() {
        whenever(accounts.existsByEntranceIdAndPurpose(entranceId, "REPAIR_RENEWAL")).thenReturn(true)
        assertThatThrownBy { service.register(entranceId, command()) }
            .isInstanceOf(FundAccountConflict::class.java)
    }

    @Test
    fun `a malformed IBAN is rejected before any write`() {
        assertThatThrownBy { service.register(entranceId, command(iban = "nonsense")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an unknown holder kind is rejected`() {
        assertThatThrownBy { service.register(entranceId, command(holderKind = "LANDLORD")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `PM-FUND-004 the holder is linked to a book party when one is given`() {
        val captor = argumentCaptor<FundAccountRow>()
        whenever(aggregates.insert(captor.capture())).thenAnswer { it.getArgument<FundAccountRow>(0) }
        val party = UUID.randomUUID()
        service.register(entranceId, command(holderPartyId = party.toString()))
        assertThat(captor.firstValue.holderParty).isEqualTo(party)
    }

    @Test
    fun `PM-FUND-004 the fund's account stores the mandate its manager stands on`() {
        val mandate = office(Role.BM)
        whenever(standing.of(entranceId, sofiaDay)).thenReturn(listOf(office(Role.CTL, UUID.randomUUID()), mandate))
        assertThat(stored(theManagers).mandateId).isEqualTo(mandate.mandateId)
    }

    @Test
    fun `PM-FUND-004 a member of the board counts as the chair, since no office marks one`() {
        val mandate = office(Role.MB)
        whenever(standing.of(entranceId, sofiaDay)).thenReturn(listOf(mandate))
        assertThat(stored(theManagers).mandateId).isEqualTo(mandate.mandateId)
    }

    @Test
    fun `PM-FUND-004 the newest of the holder's standing mandates is the one stored`() {
        val newest = office(Role.MB)
        whenever(standing.of(entranceId, sofiaDay)).thenReturn(listOf(office(Role.MB), newest))
        assertThat(stored(theManagers).mandateId).isEqualTo(newest.mandateId)
    }

    @Test
    fun `PM-FUND-004 the standing is read on the Sofia day, not the UTC one`() {
        val mandate = office(Role.BM)
        whenever(standing.of(entranceId, LocalDate.parse("2026-08-31"))).thenReturn(emptyList())
        whenever(standing.of(entranceId, sofiaDay)).thenReturn(listOf(mandate))
        assertThat(stored(theManagers).mandateId).isEqualTo(mandate.mandateId)
    }

    @Test
    fun `PM-FUND-004 a holder with no standing mandate is refused by name, nothing stored`() {
        whenever(standing.of(entranceId, sofiaDay)).thenReturn(emptyList())
        assertRefusedByName(theManagers)
    }

    @Test
    fun `PM-FUND-004 another party's mandate does not make the holder a manager`() {
        whenever(standing.of(entranceId, sofiaDay)).thenReturn(listOf(office(Role.BM, UUID.randomUUID())))
        assertRefusedByName(theManagers)
    }

    @Test
    fun `PM-FUND-004 a mandate of another entrance does not count here`() {
        val elsewhere = UUID.randomUUID()
        whenever(standing.of(elsewhere, sofiaDay)).thenReturn(listOf(office(Role.BM)))
        whenever(standing.of(entranceId, sofiaDay)).thenReturn(emptyList())
        assertRefusedByName(theManagers)
    }

    @Test
    fun `PM-FUND-004 a controller's or cashier's mandate does not hold the fund`() {
        whenever(standing.of(entranceId, sofiaDay)).thenReturn(listOf(office(Role.CTL), office(Role.CSH)))
        assertRefusedByName(theManagers)
    }

    @Test
    fun `PM-FUND-004 a manager's fund account naming nobody is refused, a mandate naming nobody beside it`() {
        whenever(standing.of(entranceId, sofiaDay)).thenReturn(listOf(office(Role.BM, party = null)))
        assertRefusedByName(command(holderKind = "MANAGER"))
    }

    @Test
    fun `PM-FUND-004 an association's fund account stores no mandate, and none is read`() {
        assertThat(stored(command(holderPartyId = holder.toString())).mandateId).isNull()
        verifyNoInteractions(standing)
    }

    @Test
    fun `PM-FUND-004 an operating account stores no mandate, and none is read`() {
        assertThat(stored(command(purpose = "OPERATING", holderKind = "MANAGER", holderPartyId = holder.toString())).mandateId).isNull()
        verifyNoInteractions(standing)
    }

    @Test
    fun `a malformed holder party id is rejected`() {
        assertThatThrownBy { service.register(entranceId, command(holderPartyId = "not-a-uuid")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
