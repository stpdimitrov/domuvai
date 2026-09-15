package zues.app.registry

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * Consumes [EntranceRegistered] from the outbox. The listener is what makes the event
 * durable: Spring Modulith writes an incomplete publication for it inside the registering
 * transaction and marks it complete only once this returns — so a crash mid-delivery
 * leaves a row to retry, never a lost event.
 */
@Component
class RegistryNotifications {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun on(event: EntranceRegistered) {
        log.info("entrance {} registered for condominium {}", event.entranceId, event.condominiumId)
    }
}
