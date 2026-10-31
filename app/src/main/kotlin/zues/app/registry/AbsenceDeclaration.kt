package zues.app.registry

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.time.LocalDate
import java.util.UUID

/**
 * A filed declaration that a unit's occupants were absent for a defined span — the record a
 * per-person exemption requires (Rule: PM-FEE-006, PM-FEE-007). Unlike a resident or an
 * animal, an absence is a closed interval `[absentFrom, absentTo)` (half-open; `absentTo` is
 * the first day back), because the owner declares a definite period of non-use.
 *
 * `filedOn` is the day the declaration was entered, stamped by the system, never supplied by the
 * caller. `handedInOn` is the day the paper declaration was handed in, which the manager states
 * and which is never after `filedOn`; with none stated it is `filedOn`. PM-FEE-007 forbids applying
 * the exemption retroactively beyond the configured window, and that window turns on `handedInOn`:
 * an owner who declared in time is not late because the entry was (the owner's answer on #215).
 */
@Table("absence_declaration")
data class AbsenceDeclaration(
    @Id val id: UUID,
    val entranceId: UUID,
    val unitId: UUID,
    val absentFrom: LocalDate,
    val absentTo: LocalDate,
    val filedOn: LocalDate,
    val handedInOn: LocalDate,
)

interface AbsenceDeclarationRepository : ListCrudRepository<AbsenceDeclaration, UUID> {
    fun findByUnitId(unitId: UUID): List<AbsenceDeclaration>
}
