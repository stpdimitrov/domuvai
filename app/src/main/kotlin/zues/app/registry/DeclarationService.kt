package zues.app.registry

import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.law.constantOn
import zues.law.numberOn
import zues.law.statutoryDeadline
import zues.kernel.toSofiaDate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** A title holder past their declaration deadline — by name only, never an identity number (PM-BOOK-011). */
data class OverdueDeclaration(
    val unitId: UUID,
    val designation: String,
    val partyName: String,
    val titleRole: String,
    val acquiredOn: LocalDate,
    val dueOn: LocalDate,
)

/**
 * Declarations for entry in the book (Rule: PM-BOOK-003) and who owes one. Filing records the
 * template version in force that day (Rule: PM-BOOK-004). Overdue is derived on demand from titles
 * and filings, never stored, so it cannot drift from them; raising a task for the manager from it
 * waits for a task module (PM-SYS-011).
 */
@Service
class DeclarationService(
    private val aggregates: JdbcAggregateTemplate,
    private val declarations: BookDeclarationRepository,
    private val units: PropertyUnitRepository,
    private val titles: TitleRepository,
    private val parties: PartyRepository,
    private val clock: Clock,
) {
    @Transactional
    fun file(entranceId: UUID, unitId: UUID, partyId: UUID, kind: String): BookDeclaration {
        val unit = units.findById(unitId).orElseThrow { NoSuchElementException("no unit $unitId") }
        if (unit.entranceId != entranceId) throw NoSuchElementException("unit $unitId is not in entrance $entranceId")
        if (!parties.existsById(partyId)) throw NoSuchElementException("no party $partyId")
        val declared = enumValueOf<DeclarationKind>(kind)                        // unknown kind -> 400
        val filedOn = LocalDate.parse(toSofiaDate(clock.instant()))              // a Sofia calendar day (PM-SYS-004)
        return aggregates.insert(
            BookDeclaration(
                id = UUID.randomUUID(),
                entranceId = entranceId,
                unitId = unitId,
                partyId = partyId,
                kind = declared.name,
                filedOn = filedOn,
                templateVersion = constantOn("BOOK_DECLARATION_TEMPLATE", filedOn.toString()).value,   // PM-BOOK-004
            ),
        )
    }

    /**
     * Owners and users whose title is in force on [on] and whose declaration deadline has passed
     * with none of theirs filed for that unit between acquiring it and [on] — a filing after [on]
     * does not rewrite what was owed on [on]. The deadline is
     * BOOK_DECLARATION_DAYS after acquisition through the one statutory deadline utility, so it
     * rolls off a weekend or holiday (PM-SYS-005), under the law in force on the acquisition day.
     * A declaration filed late still clears it: it is filed.
     */
    @Transactional(readOnly = true)
    fun overdue(entranceId: UUID, on: LocalDate): List<OverdueDeclaration> {
        val filed = declarations.findByEntranceId(entranceId)
        val owing = titles.findByEntranceId(entranceId)
            .filter { it.validFrom <= on && (it.validTo == null || on < it.validTo) }
            .filter { title -> filed.none { it.unitId == title.unitId && it.partyId == title.partyId && it.filedOn in title.validFrom..on } }
            .map { title -> title to dueOn(title.validFrom) }
            .filter { (_, due) -> on > due }
        val designation = units.findByEntranceId(entranceId).associate { it.id to it.designation }
        val name = parties.findAllById(owing.map { it.first.partyId }.distinct()).associate { it.id to it.fullName }
        return owing
            .map { (title, due) ->
                OverdueDeclaration(title.unitId, designation[title.unitId] ?: "?", name[title.partyId] ?: "?", title.titleRole, title.validFrom, due)
            }
            .sortedWith(compareBy({ it.dueOn }, { it.designation }))
    }

    private fun dueOn(acquired: LocalDate): LocalDate {
        val on = acquired.toString()
        return LocalDate.parse(statutoryDeadline(on, numberOn("BOOK_DECLARATION_DAYS", on).toInt(), on))
    }
}
