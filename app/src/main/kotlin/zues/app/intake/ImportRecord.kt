package zues.app.intake

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

/**
 * A durable record of one fee-sheet import: its verdict — did the engine reproduce the firm's
 * figures to the cent (Gate 1)? — and the content hash of the source they gave us. It is the
 * envelope, not the contents: the column mapping and the rows a commit would create are not
 * modelled here, because a real pilot spreadsheet defines them (STAGE1-ADDENDUM §1). `created_at`
 * is left to its column default. Mapped unqualified; the search_path resolves `fee_import` to
 * `intake.fee_import`.
 */
@Table("fee_import")
data class ImportRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val status: String,        // REPRODUCED | NEEDS_REVIEW
    val sourceSha: String,
    val rowsParsed: Int,
    val differing: Int,
    val violations: Int,
)

interface ImportRepository : ListCrudRepository<ImportRow, UUID> {
    fun findByEntranceId(entranceId: UUID): List<ImportRow>
}
