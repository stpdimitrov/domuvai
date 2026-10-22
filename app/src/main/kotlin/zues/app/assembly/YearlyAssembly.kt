package zues.app.assembly

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.kernel.toSofiaDate
import zues.law.constantOn
import java.time.LocalDate
import java.util.UUID

/**
 * Whether an entrance held its general assemblies in a calendar year (Rule: PM-GA-001): how many it held, against how
 * many the law asks for, and the first. An assembly is *held* in a year when one of its sittings opened on its quorum
 * on a Sofia calendar day of that year — the owner's decision of 2026-10-21 (Y-a): the following day's sitting counts,
 * as it opens at any share; a notice alone, or sittings all weighed short, do not. Derived from the record of
 * sittings every time it is asked; nothing is stored.
 */
data class HeldInYear(
    val entranceId: UUID,
    val year: Int,
    val held: Int,
    val required: Int,
    val requiredSource: String,
    val firstAssemblyId: UUID?,
    val firstHeldOn: LocalDate?,
) {
    val met: Boolean get() = held >= required
}

/**
 * `assembly`'s published answer to "was an assembly held in this year" — for `compliance` to raise its flag on, and
 * for anyone to read. It answers; raising a flag, and when, is `compliance`'s.
 */
@Service
class YearlyAssembly(private val sittings: SittingRepository) {

    // Rule: PM-GA-001
    @Transactional(readOnly = true)
    fun heldIn(entranceId: UUID, year: Int): HeldInYear {
        val opened = sittings.findByEntranceIdAndQuorumMetTrue(entranceId)
            .mapNotNull { s -> s.openedAt?.let { s to LocalDate.parse(toSofiaDate(it)) } }      // a Sofia calendar day (PM-SYS-004)
            .filter { (_, day) -> day.year == year }
        val first = opened.minByOrNull { (s, _) -> s.openedAt!! }
        // the count in force on the last day of the year asked about: what the law asked of that year
        val required = constantOn("GA_ASSEMBLIES_PER_YEAR", LocalDate.of(year, 12, 31).toString())
        return HeldInYear(
            entranceId, year, opened.map { (s, _) -> s.assemblyId }.distinct().size, required.value.toInt(), required.source,
            first?.first?.assemblyId, first?.second,
        )
    }
}
