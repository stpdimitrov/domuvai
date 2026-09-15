package zues.app.registry

import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** What the caller asks for. Validation of the enum lives at the edge (DB CHECK is the backstop). */
data class RegisterEntrance(
    val address: String,
    val label: String,
    val managementForm: String,
)

/** What the caller gets back — the ids the two inserts produced. */
data class EntranceCreated(
    val entranceId: UUID,
    val condominiumId: UUID,
)

/**
 * The registry module's one public operation for the walking skeleton: create a
 * condominium and its first entrance, then raise [EntranceRegistered]. The insert and the
 * event share one transaction, so the outbox row cannot outlive a rolled-back write.
 */
@Service
class RegistryService(
    private val aggregates: JdbcAggregateTemplate,
    private val entrances: EntranceRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun registerEntrance(command: RegisterEntrance): EntranceCreated {
        val condominium = aggregates.insert(Condominium(UUID.randomUUID(), command.address))
        val entrance = aggregates.insert(
            Entrance(UUID.randomUUID(), condominium.id, command.label, command.managementForm),
        )
        events.publishEvent(EntranceRegistered(entrance.id, condominium.id, clock.instant()))
        return EntranceCreated(entrance.id, condominium.id)
    }

    @Transactional(readOnly = true)
    fun listEntrances(): List<Entrance> = entrances.findAll()
}
