package zues.app.assembly

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Role
import zues.kernel.toSofiaDate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What a convenor states when convening. Who convenes is the sign-in's party; the office stated must be theirs today. */
data class ConveneRequest(
    val convenedAs: String,
    val scheduledAt: Instant,
    val place: String,
    val mode: String,
    val urgent: Boolean = false,
    val urgencyReason: String? = null,
)

/**
 * Every act on an assembly, asked by somebody (ADR-002): who acts is the party the sign-in is tied to, and whether
 * they may is the policy's to say today, before anything is looked up — a caller the policy allows nothing learns
 * nothing, not even whether the assembly exists.
 *
 * An assembly convened in an office is run — its agenda, its date and place, its posting act — by that office (the
 * owner's decision of 2026-10-11): by the convenor while they hold the office they convened in, and once they no longer
 * do, by whoever holds that same office in the entrance today — their successor. Another office never touches it:
 * it could otherwise void the notice for ever. An assembly the owners convened on their petition stays with the owner
 * who convened it, while they own. Whoever runs an assembly may also record a written proxy they were handed.
 */
@Service
class AssemblyActs(
    private val acting: Acting,
    private val assemblies: AssemblyRepository,
    private val convening: AssemblyService,
    private val notices: NoticeService,
    private val proxies: ProxyService,
) {
    // Rule: PM-GA-002
    // Rule: PM-SEC-001
    @Transactional
    fun convene(who: Asking, entranceId: UUID, request: ConveneRequest): Assembly {
        // an office the policy does not know convenes nothing; one it knows must be this party's today
        val office = Role.entries.firstOrNull { it.name == request.convenedAs && it.name in OFFICES }
            ?: run { acting.actor(who, Action.CONVENE_ASSEMBLY, entranceId); throw IllegalArgumentException("an assembly is convened as MB, BM or CTL (PM-GA-002), not as \"${request.convenedAs}\"") }
        val by = acting.officeHolder(who, entranceId, office)
        return convening.convene(
            entranceId, Convene(by.party, office.name, request.scheduledAt, request.place, request.mode, request.urgent, request.urgencyReason), by,
        )
    }

    // Rule: PM-GA-002
    // Rule: PM-GA-006
    @Transactional
    fun addAgendaItem(who: Asking, entranceId: UUID, assemblyId: UUID, text: String, itemType: String): AgendaItemBound {
        running(who, entranceId, assemblyId)
        return convening.addAgendaItem(entranceId, assemblyId, text, itemType)
    }

    // Rule: PM-GA-002
    // Rule: PM-GA-004
    @Transactional
    fun reschedule(who: Asking, entranceId: UUID, assemblyId: UUID, scheduledAt: Instant, place: String): Assembly {
        running(who, entranceId, assemblyId)
        return convening.reschedule(entranceId, assemblyId, scheduledAt, place)
    }

    // Rule: PM-GA-007
    @Transactional
    fun recordPosting(who: Asking, entranceId: UUID, assemblyId: UUID, postedAt: Instant, coSignatoryPartyId: UUID, photoHash: String): NoticePosting {
        val (signer, assembly) = runningIt(who, entranceId, assemblyId)
        // Whoever certifies that a notice went up held the office that day too: a successor does not sign for a posting
        // from before their time. TODO(legal): PM-GA-007 — the strictest reading, pending the owner's answer.
        if (assembly.convenedAs in OFFICES &&
            !acting.heldOn(signer.party, entranceId, Role.valueOf(assembly.convenedAs), LocalDate.parse(toSofiaDate(postedAt)))
        ) {
            throw ActRefused("PM-GA-007")
        }
        return notices.recordPosting(entranceId, assemblyId, signer, postedAt, coSignatoryPartyId, photoHash)
    }

    /**
     * A proxy (the owner's decision of 2026-10-11): appointed by the principal signed in, for themselves; or recorded
     * by whoever runs the assembly, for the principal a written authorisation names, with that document's SHA-256.
     * Nobody else names a principal. The checks on who the principal and the agent are apply to both ways.
     */
    // Rule: PM-GA-009
    // Rule: PM-SEC-001
    @Transactional
    fun registerProxy(who: Asking, entranceId: UUID, assemblyId: UUID, request: AppointProxyRequest): Proxy {
        val named = request.principalPartyId
        if (named == null || named == who.party) {
            val principal = acting.actor(who, Action.APPOINT_PROXY, entranceId)
            require(request.authorisationHash == null) { "a proxy appointed in person carries no document: the sign-in is the appointment" }
            return proxies.register(entranceId, assemblyId, command(principal.party, request), principal, authorisationHash = null)
        }
        // the policy's own line first (PM-SEC-001: the grant is in the matrix), then: is this assembly theirs to run
        acting.actor(who, Action.RECORD_PROXY, entranceId)
        val recorder = try {
            running(who, entranceId, assemblyId)
        } catch (e: ActRefused) {
            throw ActRefused(Action.RECORD_PROXY.governedBy)
        }
        val document = request.authorisationHash
            ?: throw IllegalArgumentException("a proxy recorded for somebody else is a written authorisation: its SHA-256 is required (PM-GA-009)")
        // Nobody records an authorisation that names themself: the one who wants them as proxy signs in and appoints them.
        // TODO(legal): PM-GA-009 — the strictest reading, pending the owner's answer.
        require(request.agentPartyId != recorder.party) { "whoever records a written proxy is not the proxy it names (PM-GA-009)" }
        return proxies.register(entranceId, assemblyId, command(named, request), recorder, document)
    }

    private fun command(principal: UUID, request: AppointProxyRequest) =
        RegisterProxy(principal, request.agentPartyId, request.agentKind, request.scope, request.items, request.form)

    /**
     * Who is running this assembly, or [ActRefused]. The policy is asked first, about the entrance in the path; only
     * somebody it allows something reaches the assembly, and then it must be theirs to run: they hold today the office
     * it was convened in, and the convenor no longer does — or they are the convenor.
     */
    // Rule: PM-GA-002
    private fun running(who: Asking, entranceId: UUID, assemblyId: UUID): Actor = runningIt(who, entranceId, assemblyId).first

    private fun runningIt(who: Asking, entranceId: UUID, assemblyId: UUID): Pair<Actor, Assembly> {
        val allowed = acting.allowedAny(who, entranceId, Action.CONVENE_ASSEMBLY, Action.CONVENE_ON_PETITION)
        val assembly = assemblies.findById(assemblyId).filter { it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no assembly $assemblyId in entrance $entranceId") }
        val rule = if (assembly.convenedAs == OWNERS) Action.CONVENE_ON_PETITION.governedBy else Action.CONVENE_ASSEMBLY.governedBy
        val convenor = allowed.actor.party == assembly.convenedBy
        when (assembly.convenedAs) {
            OWNERS -> if (!convenor || Action.CONVENE_ON_PETITION !in allowed.actions) throw ActRefused(rule)
            in OFFICES -> {
                val office = Role.valueOf(assembly.convenedAs)
                acting.officeHolder(who, entranceId, office)                                           // the office it was convened in, theirs today
                // while the convenor holds that office the assembly is theirs alone; a successor carries it on only after
                if (!convenor && acting.holdsToday(assembly.convenedBy, entranceId, office)) throw ActRefused(rule)
            }
            else -> throw ActRefused(rule)                                                             // a capacity this code does not know runs nothing
        }
        // An assembly from before a convening named its sign-in has nobody accountable behind it: it is not carried on.
        check(assembly.convenedLoginIssuer != null && assembly.convenedLoginSubject != null) {
            "this assembly was convened before a convening recorded who signed in; convene it anew"
        }
        return allowed.actor to assembly
    }

    private companion object {
        val OFFICES = ConvenorOffice.entries.map { it.name }.toSet()
    }
}
