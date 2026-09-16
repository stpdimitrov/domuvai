package zues.app.registry

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.time.LocalDate
import java.util.UUID

/**
 * An animal kept in a unit — its own section of the book, entered from the owner's
 * declaration with veterinary passport data (Rule: PM-BOOK-005). Each counts as one
 * occupant-equivalent in a per-person charge (Rule: PM-FEE-009). Effective-dated like a
 * resident; one with no `validTo` is current.
 */
@Table("animal")
data class Animal(
    @Id val id: UUID,
    val entranceId: UUID,
    val unitId: UUID,
    val species: String,
    val vetPassportNo: String?,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
)

interface AnimalRepository : ListCrudRepository<Animal, UUID> {
    fun findByUnitId(unitId: UUID): List<Animal>
}
