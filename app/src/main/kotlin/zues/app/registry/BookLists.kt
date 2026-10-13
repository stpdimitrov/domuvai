package zues.app.registry

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import zues.app.policy.Action
import zues.app.policy.Asking
import java.time.LocalDate
import java.util.UUID

/**
 * The names a console screen draws from the book, and the retention pass, as an HTTP caller is given them
 * (Rule: PM-BOOK-006): the manager, the board and the controller read the lists; the manager and the board anonymise
 * (Rule: PM-BOOK-010) — the controller reads and does not write. Every answer is entered (Rule: PM-BOOK-007). The
 * services behind them are untouched: charges, arrears and the book itself use those in-process and are not callers.
 */
@Service
class BookLists(
    private val access: BookAccessService,
    private val ownership: OwnershipService,
    private val declarations: DeclarationService,
    private val retention: BookRetentionService,
) {
    // Rule: PM-BOOK-006
    fun owners(entranceId: UUID, on: LocalDate, who: Asking): BookAnswer<List<OwnerView>> =
        access.standing(entranceId, who, BookAccessKind.OWNERS_LIST, Action.READ_BOOK, on, OWNERS_PURPOSE) { ownership.ownersAsOf(entranceId, on) }

    // Rule: PM-BOOK-006
    fun overdue(entranceId: UUID, on: LocalDate, who: Asking): BookAnswer<List<OverdueDeclaration>> =
        access.standing(entranceId, who, BookAccessKind.OVERDUE_LIST, Action.READ_BOOK, on, OVERDUE_PURPOSE) { declarations.overdue(entranceId, on) }

    // Rule: PM-BOOK-010
    fun anonymiseDue(entranceId: UUID, on: LocalDate, who: Asking): BookAnswer<RetentionApplied> =
        access.standing(entranceId, who, BookAccessKind.RETENTION, Action.ANONYMISE_BOOK, on, RETENTION_PURPOSE) { retention.anonymiseDue(entranceId, on) }

    companion object {
        const val OWNERS_PURPOSE = "the console's list of owners and users"
        const val OVERDUE_PURPOSE = "the console's list of overdue declarations"
        const val RETENTION_PURPOSE = "the retention pass"
    }
}

/** A caller the policy refuses, in any of the registry's controllers → 403, naming the rule. Nobody signed in is refused the same way. */
@RestControllerAdvice(basePackageClasses = [BookController::class])
class BookRefusals {
    @ExceptionHandler(BookReadForbidden::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onForbidden(e: BookReadForbidden): Map<String, String> = mapOf("error" to (REFUSALS[e.ruleId] ?: "refused"), "rule" to e.ruleId)

    private companion object {
        val REFUSALS = mapOf(
            "PM-BOOK-006" to "the book's data is for the manager, the board and the controller of this entrance",
            "PM-BOOK-010" to "what is past its retention window is anonymised by the manager and the board of this entrance",
            "PM-BOOK-003" to "a declaration is filed by its declarant, an owner or a user of the unit, or recorded in their name by the manager or the board",
        )
    }
}

/** What the caller is given, or the 403 the refusal becomes — only here, so its entry is kept. */
fun <T> BookAnswer<T>.orForbidden(): T = when (this) {
    is BookAnswer.Served -> value
    is BookAnswer.Refused -> throw BookReadForbidden(ruleId)
}
