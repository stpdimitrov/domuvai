package zues.app.registry

import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
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
 * A revert reports back. The registry hears of it after intake's request has answered, so it says what
 * became of it with one of intake's own two events: the rows are gone, or something added since still
 * points at them and none was removed.
 */
@Component
class ImportAdoption(private val registry: RegistryService, private val events: ApplicationEventPublisher) {

    @ApplicationModuleListener
    fun on(event: ImportCommitted) {
        registry.adoptImport(
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
    }

    @ApplicationModuleListener
    fun on(event: ImportReverted) {
        val outcome = try {
            registry.revertImport(event.importId)                      // in a savepoint: a refusal undoes the removal, not this reaction
            ImportRevertApplied(event.entranceId, event.importId)
        } catch (refused: RuntimeException) {   // a batched delete's refusal arrives wrapped (DbActionExecutionException), not as a DataAccessException
            // Only a row that still points at the import's rows is an answer. Anything else is a failure, and is retried.
            ImportRevertBlocked(event.entranceId, event.importId, blockedBy(refused) ?: throw refused)
        }
        events.publishEvent(outcome)
    }

    /** What still points at a row the revert tried to remove, from the database's own refusal — or null when it refused for another reason. */
    private fun blockedBy(refused: RuntimeException): String? {
        val cause = generateSequence<Throwable>(refused) { it.cause }.filterIsInstance<SQLException>().firstOrNull() ?: return null
        if (cause.sqlState != FOREIGN_KEY_VIOLATION) return null
        val named = STILL_REFERENCED.find(cause.message.orEmpty()) ?: return "a record added after the import was committed"
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
