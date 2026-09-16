package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import java.util.UUID

/**
 * The fund-account service with the repository and the aggregate template mocked — no Spring, no
 * database. Proves the validation and the no-commingling guards run in the gate pack locally; the
 * table's own constraints are proved end to end by the Docker-gated IT.
 */
class FundAccountServiceTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val accounts: FundAccountRepository = mock()
    private val service = FundAccountService(aggregates, accounts)

    private val entranceId = UUID.randomUUID()
    private val iban = "BG80BNBG96611020345678"

    private fun command(
        iban: String = this.iban,
        purpose: String = "REPAIR_RENEWAL",
        holderName: String = "Иван Петров",
        holderKind: String = "MANAGER",
        holderPartyId: String? = null,
    ) = RegisterFundAccount(iban, purpose, holderName, holderKind, holderPartyId)

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
    fun `a malformed holder party id is rejected`() {
        assertThatThrownBy { service.register(entranceId, command(holderPartyId = "not-a-uuid")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
