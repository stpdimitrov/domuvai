package zues.app.registry

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A revert, carried out whole or not at all. Nested: called from the reaction to [zues.app.intake.ImportReverted]
 * it runs in a savepoint, so a refusal undoes every removal and leaves the reaction's own transaction able to
 * report it. The checks the schema defers to commit — an entrance's ideal parts adding up — are run here, before
 * the savepoint is released: a revert they refuse is refused inside it, where it can still be answered.
 */
@Component
class ImportRemoval(private val registry: RegistryService, private val jdbc: JdbcTemplate) {

    @Transactional(propagation = Propagation.NESTED)
    fun remove(importId: UUID) {
        registry.revertImport(importId)
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE")
    }
}
