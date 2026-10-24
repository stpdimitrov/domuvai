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
    // An action is asked of a kind of resource; [Policy.ADMINISTERED] says which, for the administrator's.
    /** the whole Book of the Condominium of an entrance */
    READ_BOOK("PM-BOOK-006"),

    /** the book's entry for one unit — what its owner declared for it: its owners, its household */
    READ_UNIT_BOOK_DATA("PM-BOOK-006"),

    /** anonymising what the book holds past its retention window — irreversible */
    ANONYMISE_BOOK("PM-BOOK-010"),

    /** tying a login to a party, or untying it: saying who a signed-in person is */
    TIE_LOGIN("PM-SEC-001"),

    /** recording a mandate of an entrance, or the day it ended: saying who its manager, board, controller or cashier is */
    RECORD_MANDATE("PM-SEC-001"),

    /** signing the owners' petition to convene an assembly — opening one signs it. A signature is never withdrawn */
    SIGN_PETITION("PM-GA-003"),

    /** convening the owners' assembly on their petition, when the demand was not met */
    CONVENE_ON_PETITION("PM-GA-003"),

    /** convening a general assembly in an office, and what belongs to convening it: its agenda, its date and place, its notice */
    CONVENE_ASSEMBLY("PM-GA-002"),

    /** appointing one's own proxy for an assembly */
    APPOINT_PROXY("PM-GA-009"),

    /** recording, for a principal who does not sign in, the written authorisation they handed over — by whoever runs that assembly */
    RECORD_PROXY("PM-GA-009"),

    /** keeping the register of who is present at an assembly, and that a remote participant was identified — by whoever runs it */
    KEEP_REGISTER("PM-GA-012"),

    /** one unit's charges: its statement, its arrears, how a payment for it was allocated */
    READ_UNIT_CHARGES("PM-SEC-002"),

    /** every unit of an entrance and what each was charged, paid and owes — its arrears, its journal: other persons' debts */
    READ_ENTRANCE_ARREARS("PM-SEC-002"),

    /** filing one's own declaration for the book, for a unit one holds */
    FILE_DECLARATION("PM-BOOK-003"),

    /** recording a declaration a declarant handed in, in their name */
    RECORD_DECLARATION("PM-BOOK-003"),

    /** an entry of the book, from its declarant's declaration: an entrance's units, a unit's owners and users, its household, its animals, its absences, the day one left */
    ENTER_BOOK("PM-BOOK-005"),

    /** an entrance's mandates, and how each stands — in term, an election due, acting past term */
    READ_MANDATES("PM-SEC-001"),

    /** weighing the quorum of a sitting and opening it — by whoever runs the assembly */
    OPEN_SITTING("PM-GA-012"),

    /** an entrance's fund — its balance, its accounts, its handover statements — and its operating account: a condominium-level report */
    READ_FUND("PM-SEC-002"),

    /** the entrance's accounts kept: a charge run issued or previewed, a payment recorded, the fund's account registered, a disbursement signed off, paid or cancelled, a handover statement issued */
    KEEP_ACCOUNTS("PM-SEC-001"),

    /** an entrance's units: their designations, types, areas and ideal parts */
    READ_UNITS("PM-SEC-001"),

    /** an entrance's assemblies: their list, one with its agenda, its quorum meter, its register, its proxies, whether a year's was held — and the owners' petitions to convene one */
    READ_ASSEMBLY("PM-SEC-001"),

    /** recording how an owner present at a sitting voted on an item of the agenda, or correcting it — by whoever runs the assembly */
    RECORD_VOTE("PM-SEC-001"),

    /** who holds more than the law's share of an entrance's ideal parts, behind a higher quorum — another person's holding */
    READ_CONCENTRATION("PM-SEC-002"),
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
                (if (line.role == Role.SYS_ADMIN) ADMINISTERED[action]?.isInstance(resource) == true && administers(who, asAt) else held.any { carries(it, line, resource) })
        } ?: return Decision(false, action.governedBy, null, MATRIX_VERSION)
        return Decision(true, permit.ruleId, permit.role, MATRIX_VERSION)
    }

    private fun nobody(who: Asking) = who.login == null && who.party == null

    private fun carries(held: Held, line: Permit, resource: Resource): Boolean =
        held.role == line.role && (!line.ownUnitOnly || (resource is Resource.OfUnit && resource.unitId in held.units))

    companion object {
        /** Raised with every change to [MATRIX]; a test pins the content to the number (PM-SEC-001: testable and versioned). */
        const val MATRIX_VERSION = 19

        /**
         * The book: the management board or the manager, the control board or the controller, and the owner for their
         * own data — nobody else. Anonymising it: the manager and the board, who keep it — the controller reads and
         * does not write (PM-GOV-008). Who a login is, and who holds an entrance's offices: the deployment's administrator —
         * and nobody else. The owners' petition: an owner of the entrance on the day, signing for themselves, and no
         * office as such (PM-GA-003). A holder of a right of use is not among them pending counsel: whether they may
         * demand an assembly is the same open question as whose ideal parts count. Convening in an office: the board,
         * the manager, the control board or controller — and no cashier, firm or owner as such (PM-GA-002). A proxy:
         * an owner or a holder of use appoints their own; a written authorisation handed over is recorded by whoever
         * runs the assembly it is for — an office, or the owner who convened on a petition — which of them it is being the
         * assembly's to say (PM-GA-009). The register of who is present is kept the same way, by whoever runs the
         * assembly, and by them its quorum is weighed and a sitting opened (PM-GA-012). A unit's charges: its owner, for their own unit (PM-SEC-002), the manager and the board, who
         * certify a unit's obligations (PM-DEBT-007), and the controller, who reads everything (PM-GOV-008). An entrance's
         * arrears — other persons' debts: the manager and the board, who pursue them (PM-DEBT-003), and the controller —
         * no owner as such. The cashier, a holder of use, an occupant and a firm's staff are given neither: no rule says so,
         * pending counsel. A declaration for the book: an owner or a user files their own, for a unit they hold
         * (PM-BOOK-003); one handed in on paper is recorded in its declarant's name by the manager or the board, who enter
         * the book from the owner's declaration (PM-BOOK-005) — not the controller, who writes nothing (PM-GOV-008). The
         * book's entries — an entrance's units, a unit's owners and users, its household, its animals, its absences — are
         * entered the same way, by the manager or the board (PM-BOOK-005, PM-BOOK-002): an owner or a user changes them by
         * declaring (PM-BOOK-003), never by writing the book, nor recording their own title, themselves. An entrance's
         * mandates and how each stands: the manager and the board, whose election comes due (PM-GOV-005), the controller,
         * who reads everything (PM-GOV-008), and the administrator, who records them — no owner as such. An entrance's
         * assemblies and the owners' petitions to convene one: anyone holding a role in it — an owner, for whom they are
         * condominium-level matters (PM-SEC-002), a holder of use, the offices and the cashier; who owns more than the
         * law's share behind a higher quorum is another person's holding, for the manager, the board and the controller
         * only (the owner's decisions R1 on #247, 2026-10-22, and P1, 2026-10-23). A vote at a sitting is recorded by
         * whoever runs the assembly, as its register is kept — an office, or the owner who convened on a petition (the
         * owner's decision of 2026-10-24).
         */
        /**
         * What each of the administrator's actions is asked of. A line of theirs is carried on that kind of resource
         * and on no other: a login is the deployment's, a mandate an entrance's.
         */
        val ADMINISTERED: Map<Action, Class<out Resource>> = mapOf(
            Action.TIE_LOGIN to Resource.Deployment::class.java,
            Action.RECORD_MANDATE to Resource.OfEntrance::class.java,
            Action.READ_MANDATES to Resource.OfEntrance::class.java,
        )

        val MATRIX: List<Permit> = listOf(
            Permit(Action.READ_BOOK, Role.BM, "PM-BOOK-006"),
            Permit(Action.READ_BOOK, Role.MB, "PM-BOOK-006"),
            Permit(Action.READ_BOOK, Role.CTL, "PM-BOOK-006"),
            Permit(Action.READ_UNIT_BOOK_DATA, Role.BM, "PM-BOOK-006"),
            Permit(Action.READ_UNIT_BOOK_DATA, Role.MB, "PM-BOOK-006"),
            Permit(Action.READ_UNIT_BOOK_DATA, Role.CTL, "PM-BOOK-006"),
            Permit(Action.READ_UNIT_BOOK_DATA, Role.OWN, "PM-BOOK-006", ownUnitOnly = true),
            Permit(Action.ANONYMISE_BOOK, Role.BM, "PM-BOOK-010"),
            Permit(Action.ANONYMISE_BOOK, Role.MB, "PM-BOOK-010"),
            Permit(Action.TIE_LOGIN, Role.SYS_ADMIN, "PM-SEC-001"),
            Permit(Action.RECORD_MANDATE, Role.SYS_ADMIN, "PM-SEC-001"),
            // TODO(legal): PM-GA-003 — whether a holder of a right of use may demand an assembly (the two lines below)
            Permit(Action.SIGN_PETITION, Role.OWN, "PM-GA-003"),
            Permit(Action.CONVENE_ON_PETITION, Role.OWN, "PM-GA-003"),
            Permit(Action.CONVENE_ASSEMBLY, Role.BM, "PM-GA-002"),
            Permit(Action.CONVENE_ASSEMBLY, Role.MB, "PM-GA-002"),
            Permit(Action.CONVENE_ASSEMBLY, Role.CTL, "PM-GA-002"),
            Permit(Action.APPOINT_PROXY, Role.OWN, "PM-GA-009"),
            Permit(Action.APPOINT_PROXY, Role.USR, "PM-GA-009"),
            Permit(Action.RECORD_PROXY, Role.BM, "PM-GA-009"),
            Permit(Action.RECORD_PROXY, Role.MB, "PM-GA-009"),
            Permit(Action.RECORD_PROXY, Role.CTL, "PM-GA-009"),
            Permit(Action.RECORD_PROXY, Role.OWN, "PM-GA-009"),
            Permit(Action.KEEP_REGISTER, Role.BM, "PM-GA-012"),
            Permit(Action.KEEP_REGISTER, Role.MB, "PM-GA-012"),
            Permit(Action.KEEP_REGISTER, Role.CTL, "PM-GA-012"),
            Permit(Action.KEEP_REGISTER, Role.OWN, "PM-GA-012"),
            Permit(Action.READ_UNIT_CHARGES, Role.OWN, "PM-SEC-002", ownUnitOnly = true),
            Permit(Action.READ_UNIT_CHARGES, Role.BM, "PM-DEBT-007"),
            Permit(Action.READ_UNIT_CHARGES, Role.MB, "PM-DEBT-007"),
            Permit(Action.READ_UNIT_CHARGES, Role.CTL, "PM-GOV-008"),
            Permit(Action.READ_ENTRANCE_ARREARS, Role.BM, "PM-DEBT-003"),
            Permit(Action.READ_ENTRANCE_ARREARS, Role.MB, "PM-DEBT-003"),
            Permit(Action.READ_ENTRANCE_ARREARS, Role.CTL, "PM-GOV-008"),
            Permit(Action.FILE_DECLARATION, Role.OWN, "PM-BOOK-003", ownUnitOnly = true),
            Permit(Action.FILE_DECLARATION, Role.USR, "PM-BOOK-003", ownUnitOnly = true),
            Permit(Action.RECORD_DECLARATION, Role.BM, "PM-BOOK-005"),
            Permit(Action.RECORD_DECLARATION, Role.MB, "PM-BOOK-005"),
            Permit(Action.ENTER_BOOK, Role.BM, "PM-BOOK-005"),
            Permit(Action.ENTER_BOOK, Role.MB, "PM-BOOK-005"),
            Permit(Action.READ_MANDATES, Role.BM, "PM-GOV-005"),
            Permit(Action.READ_MANDATES, Role.MB, "PM-GOV-005"),
            Permit(Action.READ_MANDATES, Role.CTL, "PM-GOV-008"),
            Permit(Action.READ_MANDATES, Role.SYS_ADMIN, "PM-SEC-001"),
            Permit(Action.OPEN_SITTING, Role.BM, "PM-GA-012"),
            Permit(Action.OPEN_SITTING, Role.MB, "PM-GA-012"),
            Permit(Action.OPEN_SITTING, Role.CTL, "PM-GA-012"),
            Permit(Action.OPEN_SITTING, Role.OWN, "PM-GA-012"),
            Permit(Action.READ_FUND, Role.BM, "PM-SEC-001"),
            Permit(Action.READ_FUND, Role.MB, "PM-SEC-001"),
            Permit(Action.READ_FUND, Role.CTL, "PM-GOV-008"),
            Permit(Action.READ_FUND, Role.OWN, "PM-SEC-002"),
            Permit(Action.KEEP_ACCOUNTS, Role.BM, "PM-SEC-001"),
            Permit(Action.KEEP_ACCOUNTS, Role.MB, "PM-SEC-001"),
            Permit(Action.READ_UNITS, Role.OWN, "PM-SEC-001"),
            Permit(Action.READ_UNITS, Role.USR, "PM-SEC-001"),
            Permit(Action.READ_UNITS, Role.BM, "PM-SEC-001"),
            Permit(Action.READ_UNITS, Role.MB, "PM-SEC-001"),
            Permit(Action.READ_UNITS, Role.CTL, "PM-SEC-001"),
            Permit(Action.READ_UNITS, Role.CSH, "PM-SEC-001"),
            Permit(Action.READ_ASSEMBLY, Role.OWN, "PM-SEC-002"),
            Permit(Action.READ_ASSEMBLY, Role.USR, "PM-SEC-001"),
            Permit(Action.READ_ASSEMBLY, Role.BM, "PM-SEC-001"),
            Permit(Action.READ_ASSEMBLY, Role.MB, "PM-SEC-001"),
            Permit(Action.READ_ASSEMBLY, Role.CTL, "PM-GOV-008"),
            Permit(Action.READ_ASSEMBLY, Role.CSH, "PM-SEC-001"),
            Permit(Action.READ_CONCENTRATION, Role.BM, "PM-SEC-001"),
            Permit(Action.READ_CONCENTRATION, Role.MB, "PM-SEC-001"),
            Permit(Action.READ_CONCENTRATION, Role.CTL, "PM-GOV-008"),
            Permit(Action.RECORD_VOTE, Role.BM, "PM-SEC-001"),
            Permit(Action.RECORD_VOTE, Role.MB, "PM-SEC-001"),
            Permit(Action.RECORD_VOTE, Role.CTL, "PM-SEC-001"),
            Permit(Action.RECORD_VOTE, Role.OWN, "PM-SEC-001"),
        )
    }
}
