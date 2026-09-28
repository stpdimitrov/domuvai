package zues.app.registry

import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.law.numberOn
import java.time.LocalDate
import java.util.UUID

/** What one retention pass anonymised, per field group (Rule: PM-BOOK-010). */
data class RetentionApplied(val on: LocalDate, val householdUnlinked: Int, val animalPassportsCleared: Int)

/**
 * The book keeps personal data only while its legal basis lasts, then anonymises it, with a window
 * per field group (Rule: PM-BOOK-010). For a resident or an animal the basis is the stay, so the
 * window runs from the day it ended (PM-BOOK-008), read from `:law` as of the day of the pass.
 * Anonymising removes the identifier and keeps the facts a charge was computed from — unit, dates,
 * the child-under-six flag, the species — so a past charge still reproduces (PM-FEE-014).
 *
 * Former owners and users are not anonymised here: a claim for charges they owe outlives their
 * title, and for how long is counsel's to say, not a default. Nor is the person record itself —
 * other modules refer to it.
 */
@Service
class BookRetentionService(
    private val aggregates: JdbcAggregateTemplate,
    private val household: HouseholdMemberRepository,
    private val animals: AnimalRepository,
) {
    @Transactional
    fun anonymiseDue(entranceId: UUID, on: LocalDate): RetentionApplied {
        if (!aggregates.existsById(entranceId, Entrance::class.java)) throw NoSuchElementException("no entrance $entranceId")
        // TODO(legal): PM-BOOK-010 — both windows are the owner's 3-month default; counsel confirms no statute needs longer.
        val householdMonths = months("BOOK_RETENTION_HOUSEHOLD_MONTHS", on)
        val animalMonths = months("BOOK_RETENTION_ANIMAL_MONTHS", on)
        val unlinked = household.findByEntranceId(entranceId)
            .filter { it.partyId != null && past(it.validTo, householdMonths, on) }
            .onEach { aggregates.update(it.copy(partyId = null)) }
        val cleared = animals.findByEntranceId(entranceId)
            .filter { it.vetPassportNo != null && past(it.validTo, animalMonths, on) }
            .onEach { aggregates.update(it.copy(vetPassportNo = null)) }
        return RetentionApplied(on, unlinked.size, cleared.size)
    }

    private fun months(code: String, on: LocalDate): Long = numberOn(code, on.toString()).toLong()

    /** A stay that ended on [ended] is past a window of [months] on [on]; a current stay never is. */
    private fun past(ended: LocalDate?, months: Long, on: LocalDate): Boolean =
        ended != null && !on.isBefore(ended.plusMonths(months))
}
