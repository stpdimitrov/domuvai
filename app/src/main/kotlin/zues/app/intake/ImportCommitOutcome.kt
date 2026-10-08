package zues.app.intake

import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * The second step of a commit (STAGE1-ADDENDUM §1, step 6). The registry adopts the import's rows after the request
 * has answered, so the import waits as COMMITTING until the registry reports back — with one of intake's own two
 * events, so the dependency still runs registry → intake only. Delivery is at least once: an outcome for an import
 * that is no longer waiting for it changes nothing.
 */
@Component
class ImportCommitOutcome(private val imports: ImportService) {

    @ApplicationModuleListener
    fun on(event: ImportCommitApplied) = imports.commitApplied(event.importId)

    @ApplicationModuleListener
    fun on(event: ImportCommitBlocked) = imports.commitBlocked(event.importId, event.blockedBy)
}
