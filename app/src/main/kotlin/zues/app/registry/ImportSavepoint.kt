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
 * refuse is refused inside it, where it can still be answered. A removal leaves its mark, and an adoption for an
 * import that bears it is refused: a reverted import is never adopted again.
 */
@Component
class ImportSavepoint(private val registry: RegistryService, private val jdbc: JdbcTemplate) {

    @Transactional(propagation = Propagation.NESTED)
    fun adopt(entranceId: UUID, importId: UUID, effectiveFrom: LocalDate, imported: List<ImportedUnit>) {
        oneAtATime(importId)
        // A commit is delivered at least once. One delivered again after its import was reverted finds no row of it —
        // the revert removed them — so the rows' absence cannot be the guard: the revert's own mark is.
        val reverted = jdbc.queryForObject("SELECT count(*) FROM registry.reverted_import WHERE import_id = ?", Long::class.java, importId) ?: 0
        check(reverted == 0L) { "import $importId was reverted, and is not adopted again" }
        registry.adoptImport(entranceId, importId, effectiveFrom, imported)
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE")
    }

    @Transactional(propagation = Propagation.NESTED)
    fun remove(importId: UUID) {
        oneAtATime(importId)
        // The mark first, once — the table takes inserts only, so "on conflict" is not available to it. A removal that is
        // then refused takes the mark back with it: the savepoint undoes both.
        jdbc.update(
            "INSERT INTO registry.reverted_import (import_id) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM registry.reverted_import WHERE import_id = ?)",
            importId, importId,
        )
        registry.revertImport(importId)
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE")
    }

    /** An adoption and a removal of one import, one at a time: neither slips between the other's check and its writes. */
    private fun oneAtATime(importId: UUID) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))", Int::class.java, "import:$importId")
    }
}
