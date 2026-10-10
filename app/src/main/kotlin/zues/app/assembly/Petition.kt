package zues.app.assembly

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.conversion.DbActionExecutionException
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.registry.Holdings
import zues.app.registry.TitleRole
import zues.kernel.toSofiaDate
import zues.law.CATALOGUE_VERSION
import zues.law.ENGINE_VERSION
import zues.law.constantOn
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Owners' demand that an assembly be convened (Rule: PM-GA-003). Its weight is read, never stored. */
@Table("petition")
data class Petition(@Id val id: UUID, val entranceId: UUID, val openedBy: UUID, val subject: String, val openedAt: Instant)

/** One owner's signature under a petition. Append-only. */
@Table("petition_signature")
data class PetitionSignature(@Id val id: UUID, val entranceId: UUID, val petitionId: UUID, val partyId: UUID, val signedAt: Instant)

interface PetitionRepository : ListCrudRepository<Petition, UUID> {
    /** The petition, its row locked until the transaction ends — signing and convening on it take turns. */
    @Query("SELECT * FROM assembly.petition WHERE id = :id AND entrance_id = :entranceId FOR UPDATE")
    fun lock(id: UUID, entranceId: UUID): Petition?
}

interface PetitionSignatureRepository : ListCrudRepository<PetitionSignature, UUID> {
    fun findByPetitionIdOrderBySignedAt(petitionId: UUID): List<PetitionSignature>
}

/**
 * A petition weighed on a day: the ideal parts its signatories own that day against the threshold the law
 * has in force that day. [cannotWeigh] is why it cannot be weighed at all; a petition that cannot be
 * weighed unlocks nothing, whatever [heldPct] says.
 */
data class PetitionWeight(
    val on: LocalDate,
    val heldPct: BigDecimal,
    val thresholdPct: BigDecimal,
    val thresholdSource: String,
    val thresholdVerified: Boolean,
    val cannotWeigh: List<String>,
    val derivedParts: Boolean,
) {
    /** "At least" the threshold (PM-GA-003), and nothing in the way of weighing it. */
    val unlocked: Boolean get() = cannotWeigh.isEmpty() && heldPct >= thresholdPct
}

/** What a signatory states when the owners convene on their petition. */
data class ConveneOnPetition(
    val convenedBy: UUID,
    val scheduledAt: Instant,
    val place: String,
    val mode: String,
    val demandUnmet: String,
    val urgent: Boolean = false,
    val urgencyReason: String? = null,
)

/** The petition does not unlock self-convening: it cannot be weighed, or it holds too little. */
class PetitionLocked(message: String) : IllegalStateException(message)

@Service
class PetitionService(
    private val aggregates: JdbcAggregateTemplate,
    private val petitions: PetitionRepository,
    private val signatures: PetitionSignatureRepository,
    private val assemblies: AssemblyRepository,
    private val convening: AssemblyService,
    private val holdings: Holdings,
    private val clock: Clock,
) {
    // Rule: PM-GA-003
    @Transactional
    fun open(entranceId: UUID, openedBy: UUID, subject: String): Petition {
        require(subject.isNotBlank()) { "a petition says what the assembly is demanded for" }
        requireOwner(entranceId, openedBy)
        val now = clock.instant()
        val petition = aggregates.insert(Petition(UUID.randomUUID(), entranceId, openedBy, subject.trim(), now))
        aggregates.insert(PetitionSignature(UUID.randomUUID(), entranceId, petition.id, openedBy, now))   // who opens it signs it
        return petition
    }

    // Rule: PM-GA-003
    @Transactional
    fun sign(entranceId: UUID, petitionId: UUID, partyId: UUID) {
        val petition = petitions.lock(petitionId, entranceId) ?: throw NoSuchElementException("no petition $petitionId in entrance $entranceId")
        check(assemblies.findByPetitionId(petition.id) == null) { "an assembly is already convened on this petition" }
        check(signatures.findByPetitionIdOrderBySignedAt(petitionId).none { it.partyId == partyId }) { "this owner has already signed the petition" }
        requireOwner(entranceId, partyId)
        aggregates.insert(PetitionSignature(UUID.randomUUID(), entranceId, petitionId, partyId, clock.instant()))
    }

    /** The petition, who signed it, what it weighs today in Sofia, and the assembly convened on it if any. */
    @Transactional(readOnly = true)
    fun read(entranceId: UUID, petitionId: UUID): PetitionRead {
        val petition = petitions.findById(petitionId).filter { it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no petition $petitionId in entrance $entranceId") }
        val signed = signatures.findByPetitionIdOrderBySignedAt(petitionId).map { it.partyId }
        return PetitionRead(petition, signed, weigh(entranceId, signed.toSet(), today()), assemblies.findByPetitionId(petitionId)?.id)
    }

    /**
     * The owners convene on their petition: one of its signatories, once, when it is unlocked today, stating
     * that the demand was made and not met. The catalogue gives no period after which a demand is unmet, so
     * the statement is recorded and no clock is run.
     */
    // Rule: PM-GA-003
    // TODO(legal): PM-GA-003 — the period after which an unmet demand lets the owners convene
    @Transactional
    fun convene(entranceId: UUID, petitionId: UUID, request: ConveneOnPetition): Assembly {
        val petition = petitions.lock(petitionId, entranceId) ?: throw NoSuchElementException("no petition $petitionId in entrance $entranceId")
        check(assemblies.findByPetitionId(petition.id) == null) { "an assembly is already convened on this petition" }
        require(request.demandUnmet.isNotBlank()) { "the owners convene only when their demand was not met: say how it was made and that it was not (PM-GA-003)" }
        val signed = signatures.findByPetitionIdOrderBySignedAt(petitionId).map { it.partyId }.toSet()
        require(request.convenedBy in signed) { "the owners' assembly is convened by one of the petition's signatories (PM-GA-003)" }
        val weight = weigh(entranceId, signed, today())
        if (weight.cannotWeigh.isNotEmpty()) throw PetitionLocked("the petition cannot be weighed: " + weight.cannotWeigh.joinToString("; "))
        if (!weight.unlocked) {
            throw PetitionLocked(
                "the petition's signatories own ${weight.heldPct.percent()}% of the ideal parts; " +
                    "${weight.thresholdPct.percent()}% are needed (${weight.thresholdSource}) — PM-GA-003",
            )
        }
        val convene = Convene(request.convenedBy, OWNERS, request.scheduledAt, request.place, request.mode, request.urgent, request.urgencyReason)
        return convening.conveneByOwners(entranceId, convene) {
            it.copy(
                petitionId = petition.id, demandUnmetNote = request.demandUnmet.trim(), petitionHeldPct = weight.heldPct,
                petitionThresholdPct = weight.thresholdPct, petitionWeighedOn = weight.on,
                lawVersion = CATALOGUE_VERSION, engineVersion = ENGINE_VERSION,
            )
        }
    }

    /**
     * What the signatories own on [on], by title share and never by a count of units (PM-ORG-004, PM-ORG-005),
     * against the threshold in force that day (PM-SYS-002). A unit that cannot be weighed without guessing is
     * not weighed, and the petition says so.
     */
    // Rule: PM-GA-003
    internal fun weigh(entranceId: UUID, signatories: Set<UUID>, on: LocalDate): PetitionWeight {
        val owned = holdings.inForce(entranceId, on, TitleRole.OWN)
        val theirs = owned.holdings.filter { it.partyId in signatories }
        val units = theirs.map { it.unitId }.toSet()
        val alsoUsed = holdings.inForce(entranceId, on, TitleRole.USR).holdings.map { it.unitId }.toSet()
        // TODO(legal): PM-GA-003 — whose ideal parts count for a unit with both an owner and a holder of a right of
        // use is with counsel (the owner's decision of 2026-10-10): until answered such a unit is not weighed.
        val cannotWeigh = units.filter { it in alsoUsed }.sortedBy { it.toString() }.map {
            "unit $it has both an owner and a holder of a right of use — whose ideal parts count is not settled (TODO(legal): PM-GA-003)"
        } + units.filter { it in owned.overHeldUnitIds }.sortedBy { it.toString() }.map {
            "unit $it is owned for more than the whole of it — weighing it would count it twice (PM-ORG-005)"
        }
        val threshold = constantOn("GA_PETITION_MIN_PCT", on.toString())           // TODO(legal): PM-GA-003 — unconfirmed
        return PetitionWeight(
            on = on,
            heldPct = theirs.fold(BigDecimal.ZERO) { sum, held -> sum + BigDecimal(held.idealParts) },
            thresholdPct = BigDecimal(threshold.value),
            thresholdSource = threshold.source,
            thresholdVerified = threshold.verified,
            cannotWeigh = cannotWeigh,
            derivedParts = theirs.any { it.idealPartsSource != "DECLARED" },
        )
    }

    /** "Owners … MUST be able to demand": a party who owns nothing in the entrance today does not sign. */
    private fun requireOwner(entranceId: UUID, partyId: UUID) {
        val owns = holdings.inForce(entranceId, today(), TitleRole.OWN).holdings.any { it.partyId == partyId }
        require(owns) { "only an owner in the entrance signs a petition to convene (PM-GA-003)" }
    }

    private fun today(): LocalDate = LocalDate.parse(toSofiaDate(clock.instant()))   // a Sofia calendar day (PM-SYS-004)
}

/** An exact percent as it is shown: no padding zeros, no exponent — 20.0 is "20". */
fun BigDecimal.percent(): String = stripTrailingZeros().toPlainString()

data class PetitionRead(val petition: Petition, val signatories: List<UUID>, val weight: PetitionWeight, val assemblyId: UUID?)
