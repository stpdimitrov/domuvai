package zues.app.registry

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * The physical condominium. Rule: PM-ORG-001. The tenant key is the entrance, not the
 * building (ADR-005), so this carries only building-level identity — ownership, accounts
 * and assemblies hang off the entrance.
 *
 * Mapped unqualified; the connection search_path resolves `condominium` to
 * `registry.condominium` (see application.yml).
 */
@Table("condominium")
data class Condominium(
    @Id val id: UUID,
    val address: String,
)

/**
 * An entrance (вход) — the isolation unit and the only tenant key (ADR-005). Each
 * entrance may run its own assembly, manager and accounts. Rule: PM-ORG-001.
 */
@Table("entrance")
data class Entrance(
    @Id val id: UUID,
    val condominiumId: UUID,
    val label: String,
    val managementForm: String,
)

/**
 * Raised when an entrance is registered. Stored in the event publication registry (the
 * outbox) in the same transaction as the insert, then delivered to module listeners.
 */
data class EntranceRegistered(
    val entranceId: UUID,
    val condominiumId: UUID,
    val at: Instant,
)
