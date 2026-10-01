package zues.app.registry

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

/**
 * Reads over the entrance table. Writes go through `JdbcAggregateTemplate.insert` in the
 * service, because ids are app-assigned UUIDs and `insert` states the intent directly
 * rather than inferring new-vs-existing from the id.
 */
interface EntranceRepository : ListCrudRepository<Entrance, UUID> {
    /**
     * Locks the entrance row for the rest of the transaction, so two writes of a unit set into
     * one entrance run one after the other: the second sees the first's units (PM-ORG-002, PM-ORG-003).
     * NO KEY UPDATE, not UPDATE: it does not block other modules' inserts that only reference the
     * entrance (their foreign-key check takes KEY SHARE).
     */
    @Query("SELECT id FROM entrance WHERE id = :id FOR NO KEY UPDATE")
    fun lockById(id: UUID): UUID?
}
