package zues.app.intake

import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * The second step of a revert (STAGE1-ADDENDUM §1). A revert is carried out by the registry after the request has
 * answered, so the import waits as REVERTING until the registry reports back — with one of intake's own two events,
 * so the dependency still runs registry → intake only. Delivery is at least once: an outcome for an import that is
 * no longer REVERTING changes nothing.
 */
@Component
class ImportRevertOutcome(private val imports: ImportService) {

    @ApplicationModuleListener
    fun on(event: ImportRevertApplied) = imports.revertApplied(event.importId)

    @ApplicationModuleListener
    fun on(event: ImportRevertBlocked) = imports.revertBlocked(event.importId, event.blockedBy)
}
