package zues.app.registry

import java.time.LocalDate
import java.util.UUID

/**
 * The Book of the Condominium (домова книга, чл. 7 ЗУЕС) — PM-BOOK-001/002. It is a **read** over
 * the registry's own record: units, titles, household and non-use, assembled on demand, never a
 * second copy of any of them (one fact, one home). Party identity numbers never appear — names
 * only, as a resident may read them (PM-BOOK-011); the whole book resolves **as of** a date, never
 * today (PM-ORG-011).
 */

/** A party recorded against a unit — name, role (OWN | USR) and share; never an identity number. */
data class BookParty(val name: String, val role: String, val share: String)

/** A closed span of declared non-use (PM-BOOK-002 "periods of non-use"). */
data class BookNonUse(val from: LocalDate, val to: LocalDate)

/**
 * One unit's line in the book (PM-BOOK-002). [complete] is the rule's acceptance: a unit missing
 * ideal parts or an owner name cannot be marked book-complete. Built area is recorded when a survey
 * has it and is null otherwise — its absence does not, by itself, make a unit incomplete.
 */
data class BookUnitEntry(
    val unitId: UUID,
    val designation: String,
    val builtAreaM2: String?,
    val idealParts: String,
    val parties: List<BookParty>,
    val householdCount: Int,
    val nonUse: List<BookNonUse>,
    val complete: Boolean,
)

/**
 * The book for one entrance as of [asOf] (PM-BOOK-001 — the electronic book is the system of
 * record; this is its read/export). [complete] is true only when the entrance has units and every
 * one of them is book-complete.
 */
data class CondominiumBook(
    val entranceId: UUID,
    val asOf: LocalDate,
    val units: List<BookUnitEntry>,
    val complete: Boolean,
)

/**
 * PM-BOOK-002 completeness, pure so it is proved without a database: a unit is book-complete only
 * with ideal parts recorded and at least one owner named.
 */
object BookCompleteness {
    fun complete(idealParts: String?, ownerNames: List<String>): Boolean =
        !idealParts.isNullOrBlank() && ownerNames.isNotEmpty()
}
