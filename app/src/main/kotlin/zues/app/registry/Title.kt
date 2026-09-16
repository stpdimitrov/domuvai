package zues.app.registry

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * A party's ownership (**OWN**) or use (**USR**) of a unit, for a share of it (Rule: PM-ORG-005 —
 * a co-owned unit splits by share). Effective-dated: a sale ends one title and opens another, and
 * every fee, vote and arrears question resolves the party **as of** the relevant date (Rule:
 * PM-ORG-011). A title in force on a date has `[validFrom, validTo)` containing it.
 */
@Table("title")
data class Title(
    @Id val id: UUID,
    val entranceId: UUID,
    val unitId: UUID,
    val partyId: UUID,
    val titleRole: String,     // TitleRole
    val share: BigDecimal,     // 0 < share <= 1
    val validFrom: LocalDate,
    val validTo: LocalDate?,
)

/** Whether the party owns the unit or only uses it. */
enum class TitleRole { OWN, USR }

interface TitleRepository : ListCrudRepository<Title, UUID> {
    fun findByEntranceId(entranceId: UUID): List<Title>
    fun findByUnitId(unitId: UUID): List<Title>
}
