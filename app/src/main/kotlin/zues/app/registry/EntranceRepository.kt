package zues.app.registry

import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

/**
 * Reads over the entrance table. Writes go through `JdbcAggregateTemplate.insert` in the
 * service, because ids are app-assigned UUIDs and `insert` states the intent directly
 * rather than inferring new-vs-existing from the id.
 */
interface EntranceRepository : ListCrudRepository<Entrance, UUID>
