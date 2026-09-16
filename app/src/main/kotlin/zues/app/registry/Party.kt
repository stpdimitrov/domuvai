package zues.app.registry

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

/**
 * A person or legal entity the book records — the owner or user a charge is ultimately owed by
 * (Rule: PM-BOOK-002). Identity (ЕГН, БУЛСТАТ, …) is optional here and, when held, is **never**
 * placed in a list visible to other residents (Rule: PM-BOOK-011); the owners read exposes the
 * name only. `contact` is left to its column default, so it is not mapped here.
 */
@Table("party")
data class Party(
    @Id val id: UUID,
    val fullName: String,
    val idType: String?,       // IdType, or null
    val idValue: String?,      // ЕГН / БУЛСТАТ / passport no — PM-BOOK-011: not for resident-visible lists
)

/** The identifier kinds чл. 7 keeps for a party. */
enum class IdType { EGN, LNCH, BULSTAT, PASSPORT }

interface PartyRepository : ListCrudRepository<Party, UUID>
