package zues.app.registry

import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import zues.app.intake.ImportCommitted
import zues.app.intake.ImportReverted

/**
 * The consuming half of intake's commit seam. `intake` writes its own record and announces a
 * commit; the registry owns `registry.unit`, so the write lives here (ADR-003; MODULE-TEMPLATE
 * law 3 — cross-module reactions go through outbox events, never a shared transaction). The
 * dependency runs registry → intake only, and only on the event type: intake never calls the
 * registry, so there is no cycle.
 *
 * Delivery is at least once, so both reactions are idempotent on the import id (see
 * [RegistryService.adoptImport] and [RegistryService.revertImport]).
 */
@Component
class ImportAdoption(private val registry: RegistryService) {

    @ApplicationModuleListener
    fun on(event: ImportCommitted) {
        registry.adoptImport(
            event.entranceId,
            event.importId,
            event.units.map {
                RegisterUnit(designation = it.designation, unitType = IMPORTED_UNIT_TYPE, idealParts = it.idealParts)
            },
        )
    }

    @ApplicationModuleListener
    fun on(event: ImportReverted) {
        registry.revertImport(event.importId)
    }

    companion object {
        // TODO(pilot-sheet): a fee sheet carries no unit type; the real pilot spreadsheet does.
        // Until it arrives an adopted unit is typed UNSPECIFIED rather than guessed at.
        const val IMPORTED_UNIT_TYPE = "UNSPECIFIED"
    }
}
