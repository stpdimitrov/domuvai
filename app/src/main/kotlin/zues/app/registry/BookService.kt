package zues.app.registry

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * Assembles the Book of the Condominium (PM-BOOK-001/002) as a read over the registry's own record —
 * units, titles/parties, household and non-use. Nothing is stored: the book is derived on demand, so
 * it can never drift from the data it reports (one fact, one home). Ownership and household resolve
 * **as of** the book's date, never today (PM-ORG-011); parties appear by name only (PM-BOOK-011).
 */
@Service
class BookService(
    private val units: PropertyUnitRepository,
    private val ownership: OwnershipService,
    private val household: HouseholdMemberRepository,
    private val absences: AbsenceDeclarationRepository,
    private val entrances: EntranceRepository,
) {
    @Transactional(readOnly = true)
    fun forEntrance(entranceId: UUID, on: LocalDate): CondominiumBook {
        if (!entrances.existsById(entranceId)) throw NoSuchElementException("no entrance $entranceId")
        val ownersByUnit = ownership.ownersAsOf(entranceId, on).groupBy { it.unitId }
        val entries = units.findByEntranceId(entranceId).map { unit ->
            val parties = (ownersByUnit[unit.id] ?: emptyList())
                .map { BookParty(it.partyName, it.titleRole, it.share.toPlainString()) }
            val ownerNames = parties.filter { it.role == TitleRole.OWN.name }.map { it.name }
            val residents = household.findByUnitId(unit.id).count { current(it.validFrom, it.validTo, on) }
            val nonUse = absences.findByUnitId(unit.id).map { BookNonUse(it.absentFrom, it.absentTo) }
            BookUnitEntry(
                unitId = unit.id,
                designation = unit.designation,
                builtAreaM2 = unit.areaM2?.toPlainString(),
                idealParts = unit.idealPartsPct.toPlainString(),
                parties = parties,
                householdCount = residents,
                nonUse = nonUse,
                complete = BookCompleteness.complete(unit.idealPartsPct.toPlainString(), ownerNames),
            )
        }
        return CondominiumBook(entranceId, on, entries, complete = entries.isNotEmpty() && entries.all { it.complete })
    }

    /** In residence on [on]: the half-open interval [validFrom, validTo) contains it. */
    private fun current(from: LocalDate, to: LocalDate?, on: LocalDate) = from <= on && (to == null || on < to)
}
