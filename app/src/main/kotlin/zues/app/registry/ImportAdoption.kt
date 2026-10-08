package zues.app.registry

import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import zues.app.intake.ImportCommitApplied
import zues.app.intake.ImportCommitBlocked
import zues.app.intake.ImportCommitted
import zues.app.intake.ImportRevertApplied
import zues.app.intake.ImportRevertBlocked
import zues.app.intake.ImportReverted
import java.math.BigDecimal
import java.sql.SQLException

/**
 * The consuming half of intake's commit seam. `intake` writes its own record and announces a
 * commit; the registry owns `registry.unit`, so the write lives here (ADR-003; MODULE-TEMPLATE
 * law 3 — cross-module reactions go through outbox events, never a shared transaction). The
 * dependency runs registry → intake only, and only on the event type: intake never calls the
 * registry, so there is no cycle.
 *
 * Delivery is at least once, so both reactions are idempotent on the import id (see
 * [RegistryService.adoptImport] and [RegistryService.revertImport]).
 *
 * Both reactions report back. The registry hears of a commit or a revert after intake's request has
 * answered, so it says what became of it with one of intake's own events: done, or not — and why — with
 * nothing written. It always answers: a failure it cannot name is still a commit or a revert that did
 * not happen, and the import must not wait on it.
 */
@Component
class ImportAdoption(
    private val registry: RegistryService,
    private val savepoint: ImportSavepoint,
    private val events: ApplicationEventPublisher,
) {

    @ApplicationModuleListener
    fun on(event: ImportCommitted) {
        val outcome = try {
            savepoint.adopt(                                           // in a savepoint: a refusal undoes the adoption, not this reaction
                event.entranceId,
                event.importId,
                event.effectiveFrom,
                event.units.map {
                    ImportedUnit(
                        RegisterUnit(
                            designation = it.designation, unitType = IMPORTED_UNIT_TYPE,
                            areaM2 = it.builtArea?.let(::BigDecimal), idealParts = it.idealParts,
                            businessUse = it.businessUse,   // Rule: PM-FEE-010 — a sheet has no separate-entrance column, so none is set
                        ),
                        occupants = it.occupants, childrenUnder6 = it.childrenUnder6, ownerName = it.ownerName,
                    )
                },
            )
            ImportCommitApplied(event.entranceId, event.importId)
        } catch (refused: RuntimeException) {
            ImportCommitBlocked(event.entranceId, event.importId, why(refused))
        }
        events.publishEvent(outcome)
    }

    @ApplicationModuleListener
    fun on(event: ImportReverted) {
        val outcome = try {
            savepoint.remove(event.importId)                           // in a savepoint: a refusal undoes the removal, not this reaction
            ImportRevertApplied(event.entranceId, event.importId)
        } catch (refused: RuntimeException) {   // a batched delete's refusal arrives wrapped (DbActionExecutionException), not as a DataAccessException
            ImportRevertBlocked(event.entranceId, event.importId, stillReferenced(refused) ?: why(refused))
        }
        events.publishEvent(outcome)
    }

    private fun database(refused: RuntimeException): SQLException? =
        generateSequence<Throwable>(refused) { it.cause }.filterIsInstance<SQLException>().firstOrNull()

    private fun firstLine(text: String?) = text.orEmpty().lineSequence().firstOrNull().orEmpty().removePrefix("ERROR: ").trim().take(300)

    /**
     * Why it did not happen: the database's own first line — an entrance's ideal parts no longer adding up — or, for a
     * failure that is not the database's, what the registry itself said. Any other failure is named by its kind only:
     * a framework's message can quote the row it was writing, an owner's name with it (PM-BOOK-011). Asking again is
     * always possible.
     */
    private fun why(refused: RuntimeException): String {
        val cause = database(refused)
        if (cause != null) return firstLine(cause.message).ifBlank { "the database refused it (${cause.sqlState})" }
        val ours = refused is NoSuchElementException || refused is IllegalArgumentException || refused is IllegalStateException
        return firstLine(refused.message.takeIf { ours }).ifBlank { "the registry could not carry it out (${refused.javaClass.simpleName})" }
    }

    /** For a revert: the table and constraint of a row that still points at an imported one — or null when that is not what refused it. */
    private fun stillReferenced(refused: RuntimeException): String? {
        val cause = database(refused)?.takeIf { it.sqlState == FOREIGN_KEY_VIOLATION } ?: return null
        val named = STILL_REFERENCED.find(firstLine(cause.message)) ?: return "a record added after the import was committed"
        return "${named.groupValues[2]} (${named.groupValues[1]})"
    }

    companion object {
        private const val FOREIGN_KEY_VIOLATION = "23503"
        private val STILL_REFERENCED = Regex("""violates foreign key constraint "([^"]+)" on table "([^"]+)"""")

        // TODO(pilot-sheet): a fee sheet carries no unit type; the real pilot spreadsheet does.
        // Until it arrives an adopted unit is typed UNSPECIFIED rather than guessed at.
        const val IMPORTED_UNIT_TYPE = "UNSPECIFIED"
    }
}
