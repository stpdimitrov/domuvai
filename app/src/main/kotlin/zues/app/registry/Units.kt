package zues.app.registry

import org.springframework.stereotype.Component
import zues.law.numberOn
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The registry's published view of a unit for charging — the subset another module needs
 * to compute a run. Money depends on this port, never on `registry`'s tables (ADR-003).
 *
 * `occupants` is the current headcount and `childrenUnder6` the subset that a management or
 * maintenance charge excludes (Rules PM-FEE-008, PM-FEE-005); `animals` are the current pets,
 * each an occupant-equivalent (Rule PM-FEE-009). `absentDays` is the qualifying non-use the
 * unit has on record for the run's calendar year (Rule PM-FEE-006) — from filed declarations
 * only, and only those filed in time (Rule PM-FEE-007). The engine decides what that count
 * exempts; the registry only reports what its book of declarations holds.
 */
data class UnitForCharging(
    val unitId: UUID,
    val designation: String,
    val idealParts: String,        // exact decimal percent, e.g. "60.0000"
    val separateEntrance: Boolean,
    val occupants: Int = 0,
    val childrenUnder6: Int = 0,
    val animals: Int = 0,
    val absentDays: Int = 0,
)

/** The registry module's API for reading units. Implemented in-module by [UnitsAdapter]. */
interface Units {
    /**
     * The entrance's units with occupancy **as of [on]** — the billing period's legal date,
     * not today. A charge for a past period must use that period's headcount, or the bill is
     * both wrong and irreproducible (PM-FEE-014).
     */
    fun forEntrance(entranceId: UUID, on: LocalDate): List<UnitForCharging>
}

@Component
class UnitsAdapter(
    private val units: PropertyUnitRepository,
    private val household: HouseholdMemberRepository,
    private val animals: AnimalRepository,
    private val absences: AbsenceDeclarationRepository,
) : Units {
    override fun forEntrance(entranceId: UUID, on: LocalDate): List<UnitForCharging> {
        // PM-FEE-006 counts absence "in a calendar year"; the run's legal date fixes the year.
        val yearStart = LocalDate.of(on.year, 1, 1)
        val yearEnd = yearStart.plusYears(1)   // exclusive
        val graceDays = numberOn("ABSENCE_DECLARATION_GRACE_DAYS", on.toString()).toLong()
        return units.findByEntranceId(entranceId).map { unit ->
            val residents = household.findByUnitId(unit.id).filter { current(it.validFrom, it.validTo, on) }
            val pets = animals.findByUnitId(unit.id).filter { current(it.validFrom, it.validTo, on) }
            val absentDays = absences.findByUnitId(unit.id)
                .filter { timely(it, graceDays) }
                .sumOf { daysInYear(it, yearStart, yearEnd) }
            UnitForCharging(
                unitId = unit.id,
                designation = unit.designation,
                idealParts = unit.idealPartsPct.toPlainString(),
                separateEntrance = unit.separateEntrance,
                occupants = residents.size,
                childrenUnder6 = residents.count { it.isChildUnder6 },
                animals = pets.size,
                absentDays = absentDays.toInt(),
            )
        }
    }

    /** In residence on [on]: the half-open interval [validFrom, validTo) contains it. */
    private fun current(from: LocalDate, to: LocalDate?, on: LocalDate) = from <= on && (to == null || on < to)

    /**
     * PM-FEE-007 — the exemption "MUST NOT [be applied] retroactively beyond the configured
     * window." A declaration filed more than [graceDays] after the absence ends is late and is
     * not applied. Timeliness turns on `filedOn`, which the system stamps, not the caller.
     */
    private fun timely(d: AbsenceDeclaration, graceDays: Long) =
        !d.filedOn.isAfter(d.absentTo.plusDays(graceDays))

    /** The declared days that fall in the run's calendar year, clipped to it (PM-FEE-006). */
    private fun daysInYear(d: AbsenceDeclaration, yearStart: LocalDate, yearEnd: LocalDate): Long {
        val from = maxOf(d.absentFrom, yearStart)
        val to = minOf(d.absentTo, yearEnd)
        return if (to.isAfter(from)) ChronoUnit.DAYS.between(from, to) else 0
    }
}
