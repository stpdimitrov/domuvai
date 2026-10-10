package zues.app.policy

import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** The roles access is granted by (Rule: PM-SEC-001) — these and no others. */
enum class Role { OWN, USR, OCC, BM, MB, CTL, CSH, PMC_STAFF, MUN, SYS_ADMIN }

/** A sign-in: the issuer and the subject of its token (ADR-011). */
data class Login(val issuer: String, val subject: String)

/** Who is asking: the login, and the registered party it is tied to. Either may be missing; with neither, nobody is asking. */
data class Asking(val login: Login?, val party: UUID?)

/** What is asked for, each with the rule that governs it — the rule a refusal cites. */
enum class Action(val governedBy: String) {
    /** the whole Book of the Condominium of an entrance */
    READ_BOOK("PM-BOOK-006"),

    /** the book's entry for one unit — what its owner declared for it: its owners, its household */
    READ_UNIT_BOOK_DATA("PM-BOOK-006"),

    /** tying a login to a party, or untying it: saying who a signed-in person is */
    TIE_LOGIN("PM-SEC-001"),
}

/**
 * What it is asked of. An entrance is the isolation unit (ADR-005); a unit lies in one. The policy reads no table, so
 * it cannot tell whether [OfUnit.unitId] lies in [OfUnit.entranceId]: whoever builds an [OfUnit] takes the entrance
 * from the registry's own record of that unit, never from the caller.
 */
sealed interface Resource {
    /** The entrance it lies in — none for what belongs to the deployment as a whole. */
    val entranceId: UUID?

    /** The deployment itself: its logins. No entrance's role reaches it. */
    data object Deployment : Resource {
        override val entranceId: UUID? = null
    }

    data class OfEntrance(override val entranceId: UUID) : Resource
    data class OfUnit(override val entranceId: UUID, val unitId: UUID) : Resource
}

/** A role a person holds in an entrance on a date — for an owner, with the units of that entrance they hold. */
data class Held(val role: Role, val entranceId: UUID, val units: Set<UUID> = emptySet())

/**
 * Where roles come from. They are derived, not copied: the module that owns the fact — a title, a mandate — answers
 * what this person holds in this entrance on this date. The date is the caller's; a source never reads a clock.
 * Each answer names its entrance: one for another entrance than the one asked about is dropped, not trusted.
 */
fun interface RoleSource {
    fun held(who: Asking, entranceId: UUID, asAt: LocalDate): Set<Held>

    /**
     * Whether this person administers the deployment on that date (Rule: PM-SEC-001 — SYS_ADMIN). It is a role of no
     * entrance, so it has an answer of its own: nothing [held] says can make an administrator.
     */
    fun administers(who: Asking, asAt: LocalDate): Boolean = false
}

/** The answer, with the rule that produced it (ADR-002) — and, when allowed, the role that carried it. */
data class Decision(val allowed: Boolean, val ruleId: String, val role: Role?, val matrixVersion: Int)

/** One line of the matrix: this role may do this, by this rule — for an owner, only in a unit they hold. SYS_ADMIN's lines are the deployment's. */
data class Permit(val action: Action, val role: Role, val ruleId: String, val ownUnitOnly: Boolean = false)

/**
 * Who may do what (ADR-002): the чл. 7 ал. 4 matrix, once, evaluated over the roles held on a date. Nothing is allowed
 * that the matrix does not name. The date is a required argument: a past question is an ordinary call.
 */
@Service
class Policy(private val sources: List<RoleSource>) {

    /** The roles held in an entrance. SYS_ADMIN is never among them: a source answering it for an entrance is not believed. */
    // Rule: PM-SEC-001
    fun rolesOf(who: Asking, entranceId: UUID, asAt: LocalDate): Set<Held> =
        if (nobody(who)) emptySet()
        else sources.flatMapTo(mutableSetOf()) { source -> source.held(who, entranceId, asAt).filter { it.entranceId == entranceId && it.role != Role.SYS_ADMIN } }

    // Rule: PM-SEC-001
    fun administers(who: Asking, asAt: LocalDate): Boolean = !nobody(who) && sources.any { it.administers(who, asAt) }

    // Rule: PM-BOOK-006
    // Rule: PM-SEC-001
    fun decide(who: Asking, action: Action, resource: Resource, asAt: LocalDate): Decision {
        val held = resource.entranceId?.let { rolesOf(who, it, asAt) }.orEmpty()
        val permit = MATRIX.firstOrNull { line ->
            line.action == action &&
                (if (line.role == Role.SYS_ADMIN) resource is Resource.Deployment && administers(who, asAt) else held.any { carries(it, line, resource) })
        } ?: return Decision(false, action.governedBy, null, MATRIX_VERSION)
        return Decision(true, permit.ruleId, permit.role, MATRIX_VERSION)
    }

    private fun nobody(who: Asking) = who.login == null && who.party == null

    private fun carries(held: Held, line: Permit, resource: Resource): Boolean =
        held.role == line.role && (!line.ownUnitOnly || (resource is Resource.OfUnit && resource.unitId in held.units))

    companion object {
        /** Raised with every change to [MATRIX]; a test pins the content to the number (PM-SEC-001: testable and versioned). */
        const val MATRIX_VERSION = 2

        /**
         * The book: the management board or the manager, the control board or the controller, and the owner for their
         * own data — nobody else. Who a login is: the deployment's administrator — and nobody else.
         */
        val MATRIX: List<Permit> = listOf(
            Permit(Action.READ_BOOK, Role.BM, "PM-BOOK-006"),
            Permit(Action.READ_BOOK, Role.MB, "PM-BOOK-006"),
            Permit(Action.READ_BOOK, Role.CTL, "PM-BOOK-006"),
            Permit(Action.READ_UNIT_BOOK_DATA, Role.BM, "PM-BOOK-006"),
            Permit(Action.READ_UNIT_BOOK_DATA, Role.MB, "PM-BOOK-006"),
            Permit(Action.READ_UNIT_BOOK_DATA, Role.CTL, "PM-BOOK-006"),
            Permit(Action.READ_UNIT_BOOK_DATA, Role.OWN, "PM-BOOK-006", ownUnitOnly = true),
            Permit(Action.TIE_LOGIN, Role.SYS_ADMIN, "PM-SEC-001"),
        )
    }
}
