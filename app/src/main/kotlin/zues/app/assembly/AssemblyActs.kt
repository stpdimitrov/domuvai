package zues.app.assembly

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.app.policy.Role
import java.time.Instant
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
 * An assembly convened in an office is run — its agenda, its date and place — by whoever holds an office of the
 * entrance today: offices change hands, and the successor carries on. An assembly the owners convened on their
 * petition is run by the owner who convened it, while they still own. The posting act is the convenor's own signature
 * in either case (Rule: PM-GA-007): nobody records it for them.
 */
@Service
class AssemblyActs(
    private val acting: Acting,
    private val assemblies: AssemblyRepository,
    private val convening: AssemblyService,
    private val notices: NoticeService,
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
    fun recordPosting(who: Asking, entranceId: UUID, assemblyId: UUID, postedAt: Instant, coSignatoryPartyId: UUID, photoHash: String): NoticePosting =
        notices.recordPosting(entranceId, assemblyId, running(who, entranceId, assemblyId), postedAt, coSignatoryPartyId, photoHash)

    /**
     * Who is running this assembly, or [ActRefused]. The policy is asked first, about the entrance in the path; only
     * somebody it allows something reaches the assembly, and then it must be theirs to run.
     */
    private fun running(who: Asking, entranceId: UUID, assemblyId: UUID): Actor {
        val allowed = acting.allowedAny(who, entranceId, Action.CONVENE_ASSEMBLY, Action.CONVENE_ON_PETITION)
        val assembly = assemblies.findById(assemblyId).filter { it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no assembly $assemblyId in entrance $entranceId") }
        val theirs = if (assembly.convenedAs == OWNERS) {
            Action.CONVENE_ON_PETITION in allowed.actions && allowed.actor.party == assembly.convenedBy
        } else {
            Action.CONVENE_ASSEMBLY in allowed.actions
        }
        if (!theirs) throw ActRefused(if (assembly.convenedAs == OWNERS) Action.CONVENE_ON_PETITION.governedBy else Action.CONVENE_ASSEMBLY.governedBy)
        return allowed.actor
    }

    private companion object {
        val OFFICES = ConvenorOffice.entries.map { it.name }.toSet()
    }
}
