package zues.app.money

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

/**
 * A condominium's external bank account (Rule: PM-FUND-004). The **REPAIR_RENEWAL** fund is
 * the mandatory "Ремонт и обновяване" fund every entrance must keep (Rule: PM-FUND-001); an
 * **OPERATING** account is the everyday one it must stay distinct from.
 *
 * The platform never holds the money (ADR-007): this row carries no balance, only the account's
 * identity — its IBAN and who holds it. `holder_kind` is the two the statute permits, the chair
 * of the management board (**MANAGER**) or the **ASSOCIATION**. Mapped unqualified; the
 * search_path resolves `fund_account` to `money.fund_account`.
 */
@Table("fund_account")
data class FundAccountRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val iban: String,
    val purpose: String,        // FundPurpose
    val holderName: String,
    val holderKind: String,     // HolderKind
    // The book party that holds the account, when it is a modelled party (Rule: PM-FUND-004).
    val holderParty: UUID? = null,
)

/** The account's purpose. The fund is REPAIR_RENEWAL; it must not equal the OPERATING account. */
enum class FundPurpose { REPAIR_RENEWAL, OPERATING }

/** Who чл. 50 permits to hold the account. */
enum class HolderKind { MANAGER, ASSOCIATION }

interface FundAccountRepository : ListCrudRepository<FundAccountRow, UUID> {
    fun findByEntranceId(entranceId: UUID): List<FundAccountRow>
    fun existsByIban(iban: String): Boolean
    fun existsByEntranceIdAndPurpose(entranceId: UUID, purpose: String): Boolean
}
