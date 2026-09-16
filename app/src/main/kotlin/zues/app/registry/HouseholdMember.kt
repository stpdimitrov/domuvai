package zues.app.registry

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.time.LocalDate
import java.util.UUID

/**
 * A person residing in a unit — the headcount a per-person charge is built on (Rule:
 * PM-FEE-008). Children under six are flagged so they can be separated from the count
 * (Rule: PM-FEE-005). Residence is effective-dated; a member with no `validTo` is current.
 * Party identity is optional here (parties are not modelled yet), because the count does
 * not need a name.
 */
@Table("household_member")
data class HouseholdMember(
    @Id val id: UUID,
    val entranceId: UUID,
    val unitId: UUID,
    val partyId: UUID?,
    val isChildUnder6: Boolean,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
)

interface HouseholdMemberRepository : ListCrudRepository<HouseholdMember, UUID> {
    fun findByUnitId(unitId: UUID): List<HouseholdMember>
}
