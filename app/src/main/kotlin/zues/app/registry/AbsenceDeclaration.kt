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
 * `filedOn` is stamped by the system when the declaration is recorded, never supplied by the
 * caller: PM-FEE-007 forbids applying the exemption retroactively beyond the configured
 * window, and only the true filing date can decide whether a filing is timely.
 */
@Table("absence_declaration")
data class AbsenceDeclaration(
    @Id val id: UUID,
    val entranceId: UUID,
    val unitId: UUID,
    val absentFrom: LocalDate,
    val absentTo: LocalDate,
    val filedOn: LocalDate,
)

interface AbsenceDeclarationRepository : ListCrudRepository<AbsenceDeclaration, UUID> {
    fun findByUnitId(unitId: UUID): List<AbsenceDeclaration>
}
