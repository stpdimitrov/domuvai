package zues.app.registry

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.time.LocalDate
import java.util.UUID

/**
 * A declaration for entry in the Book of the Condominium (Rule: PM-BOOK-003), on the template in
 * force when it was filed (Rule: PM-BOOK-004). [filedOn] is the system's date, never the caller's —
 * the filing date decides timeliness.
 */
@Table("book_declaration")
data class BookDeclaration(
    @Id val id: UUID,
    val entranceId: UUID,
    val unitId: UUID,
    val partyId: UUID,
    val kind: String,              // DeclarationKind
    val filedOn: LocalDate,
    val templateVersion: String,
)

/** Why a declaration is filed: title or use newly acquired, or a change of the declared data. */
enum class DeclarationKind { ACQUISITION, CHANGE }

interface BookDeclarationRepository : ListCrudRepository<BookDeclaration, UUID> {
    fun findByEntranceId(entranceId: UUID): List<BookDeclaration>
}
