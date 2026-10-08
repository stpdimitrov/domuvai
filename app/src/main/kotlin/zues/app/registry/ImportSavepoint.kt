package zues.app.registry

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * An import's adoption, or its removal, carried out whole or not at all. Nested: called from the reaction to
 * [zues.app.intake.ImportCommitted] or [zues.app.intake.ImportReverted] it runs in a savepoint, so a refusal undoes
 * every write and leaves the reaction's own transaction able to report it. The checks the schema defers to commit —
 * an entrance's ideal parts adding up (PM-ORG-002) — are run here, before the savepoint is released: what they
 * refuse is refused inside it, where it can still be answered.
 */
@Component
class ImportSavepoint(private val registry: RegistryService, private val jdbc: JdbcTemplate) {

    @Transactional(propagation = Propagation.NESTED)
    fun adopt(entranceId: UUID, importId: UUID, effectiveFrom: LocalDate, imported: List<ImportedUnit>) {
        registry.adoptImport(entranceId, importId, effectiveFrom, imported)
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE")
    }

    @Transactional(propagation = Propagation.NESTED)
    fun remove(importId: UUID) {
        registry.revertImport(importId)
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE")
    }
}
