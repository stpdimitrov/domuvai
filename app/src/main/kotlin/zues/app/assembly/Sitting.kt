package zues.app.assembly

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
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
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A sitting of an assembly: the record of one weighing of its quorum (Rule: PM-GA-012) — what share of the ideal
 * parts was represented, against which threshold, on which day, from what ([basis]) — and whether the sitting opened
 * on it. Kept with the catalogue and engine versions that weighed it (PM-LAW-002); never rewritten.
 */
@Table("session")
data class Sitting(
    @Id val id: UUID,
    val entranceId: UUID,
    val assemblyId: UUID,
    val sessionNo: Int,
    val openedAt: Instant?,
    val represented: BigDecimal,
    val quorumMet: Boolean,
    val weighedAt: Instant,
    val weighedOn: LocalDate,
    val thresholdPct: BigDecimal,
    val thresholdConstant: String,
    val thresholdVerified: Boolean,
    val basis: String,
    val basisHash: String,
    val lawVersion: String,
    val engineVersion: String,
    val weighedBy: UUID,
    val loginIssuer: String,
    val loginSubject: String,
)

interface SittingRepository : ListCrudRepository<Sitting, UUID> {
    fun findByAssemblyIdOrderBySessionNo(assemblyId: UUID): List<Sitting>
}

/**
 * Quorum as it stands: the ideal parts of the owners the register makes present, by title share, against the
 * threshold in force on the day of the sitting. [cannotWeigh] is why it cannot be weighed at all; then nothing is met.
 * [basis] says what was weighed against what, then one line per holding counted — the owner, the unit, the parts, and
 * the line of the register that made them present — and [basisHash] is its SHA-256.
 */
data class Quorum(
    val on: LocalDate,
    val representedPct: BigDecimal,
    val thresholdPct: BigDecimal,
    val thresholdSource: String,
    val thresholdVerified: Boolean,
    val thresholdConstant: String,
    val cannotWeigh: List<String>,
    val derivedParts: Boolean,
    val basis: String,
) {
    /** "At least" the threshold, of all the ideal parts (PM-GA-012) — and nothing in the way of weighing. */
    val met: Boolean get() = cannotWeigh.isEmpty() && representedPct >= thresholdPct
    val basisHash: String get() = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** The quorum of an assembly now, and the sittings already weighed. */
data class QuorumRead(val quorum: Quorum, val sittings: List<Sitting>)

/** Quorum cannot be weighed: a sitting is neither opened nor recorded as failed on a figure nobody can stand behind. */
class QuorumNotWeighable(message: String) : IllegalStateException(message)

@Service
class SittingService(
    private val aggregates: JdbcAggregateTemplate,
    private val assemblies: AssemblyRepository,
    private val sittings: SittingRepository,
    private val attendance: AttendanceService,
    private val holdings: Holdings,
    private val clock: Clock,
) {
    /** The meter: quorum as the register stands, weighed as of the day of the sitting. */
    // Rule: PM-GA-012
    @Transactional(readOnly = true)
    fun read(entranceId: UUID, assemblyId: UUID): QuorumRead {
        val assembly = assemblies.findById(assemblyId).filter { it.entranceId == entranceId }
            .orElseThrow { NoSuchElementException("no assembly $assemblyId in entrance $entranceId") }
        return QuorumRead(weigh(assembly), sittings.findByAssemblyIdOrderBySessionNo(assemblyId))
    }

    /**
     * Weighs the quorum of the first sitting, once, and opens the assembly if it is met. Not before the hour the
     * notice stated, and on the day of the sitting. A weighing that falls short is recorded too: it is what the next
     * sitting follows from. One that cannot be made at all is refused, and records nothing.
     */
    // Rule: PM-GA-012
    @Transactional
    fun open(entranceId: UUID, assemblyId: UUID, by: Actor): Sitting {
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        check(assembly.status == AssemblyStatus.NOTICED.name) { "a sitting opens for an assembly whose notice is posted and that is not yet open; this one is ${assembly.status}" }
        val now = clock.instant()
        check(!now.isBefore(assembly.scheduledAt)) { "a sitting does not open before the hour its notice stated, ${assembly.scheduledAt}" }
        check(LocalDate.parse(toSofiaDate(now)) == sittingDay(assembly)) { "the first sitting is on the day the assembly meets, ${sittingDay(assembly)}" }
        check(sittings.findByAssemblyIdOrderBySessionNo(assemblyId).none { it.sessionNo == FIRST_SITTING }) { "the quorum of the first sitting is already weighed" }
        val quorum = weigh(assembly)
        if (quorum.cannotWeigh.isNotEmpty()) throw QuorumNotWeighable("the quorum cannot be weighed: " + quorum.cannotWeigh.joinToString("; "))
        val sitting = aggregates.insert(
            Sitting(
                id = UUID.randomUUID(), entranceId = entranceId, assemblyId = assemblyId, sessionNo = FIRST_SITTING,
                openedAt = now.takeIf { quorum.met }, represented = quorum.representedPct, quorumMet = quorum.met,
                weighedAt = now, weighedOn = quorum.on, thresholdPct = quorum.thresholdPct, thresholdConstant = quorum.thresholdConstant,
                thresholdVerified = quorum.thresholdVerified, basis = quorum.basis, basisHash = quorum.basisHash,
                lawVersion = CATALOGUE_VERSION, engineVersion = ENGINE_VERSION,
                weighedBy = by.party, loginIssuer = by.loginIssuer, loginSubject = by.loginSubject,
            ),
        )
        if (quorum.met) assemblies.save(assembly.copy(status = AssemblyStatus.OPEN.name))
        return sitting
    }

    /**
     * The ideal parts of the owners the register makes present, on the day of the sitting (PM-ORG-011), by title
     * share and never by a count of units (PM-ORG-004, PM-ORG-005), against the law's threshold that day (PM-SYS-002).
     *
     * The owner's standing answer: while counsel has not said whose ideal parts count for a unit with both an owner
     * and a holder of a right of use, such a unit is not weighed — and with one in the entrance the quorum cannot be
     * weighed at all. Nor is a represented owner's unit that is owned for more than the whole of it.
     */
    // Rule: PM-GA-012
    // TODO(legal): PM-GA-012 — whose ideal parts count for a unit with both an owner and a holder of a right of use
    internal fun weigh(assembly: Assembly): Quorum {
        val on = sittingDay(assembly)
        val register = attendance.register(assembly.entranceId, assembly.id)
        val represented = register.flatMapTo(mutableSetOf()) { it.represented }
        val owned = holdings.inForce(assembly.entranceId, on, TitleRole.OWN)
        val theirs = owned.holdings.filter { it.partyId in represented }
        val ownedUnits = owned.holdings.map { it.unitId }.toSet()
        val bothRoles = holdings.inForce(assembly.entranceId, on, TitleRole.USR).holdings.map { it.unitId }.filter { it in ownedUnits }.toSet()
        val overHeld = theirs.map { it.unitId }.filter { it in owned.overHeldUnitIds }.toSet()
        val cannotWeigh = bothRoles.sortedBy { it.toString() }.map {
            "unit $it has both an owner and a holder of a right of use — whose ideal parts count is not settled (TODO(legal): PM-GA-012)"
        } + overHeld.sortedBy { it.toString() }.map {
            "unit $it is owned for more than the whole of it — weighing it would count it twice (PM-ORG-005)"
        }
        val weighed = theirs.filter { it.unitId !in bothRoles && it.unitId !in overHeld }
        val threshold = constantOn("GA_QUORUM_PCT", on.toString())
        // A unit never weighs more than its own ideal parts, however its co-owners' rounded shares add up (PM-ORG-005).
        val representedPct = weighed.groupBy { it.unitId }.values.fold(BigDecimal.ZERO) { sum, ofUnit ->
            sum + ofUnit.fold(BigDecimal.ZERO) { parts, held -> parts + BigDecimal(held.idealParts) }.min(BigDecimal(ofUnit.first().unitIdealParts))
        }
        val thresholdConstant = "${threshold.code}@${threshold.inForceFrom}"
        // why each owner counted: the first line of the register that brings them, how they were present, and when identified
        val through = weighed.map { it.partyId }.distinct().associateWith { party -> register.first { party in it.represented } }
        return Quorum(
            on = on,
            representedPct = representedPct,
            thresholdPct = BigDecimal(threshold.value),
            thresholdSource = threshold.source,
            thresholdVerified = threshold.verified,
            thresholdConstant = thresholdConstant,
            cannotWeigh = cannotWeigh,
            derivedParts = weighed.any { it.idealPartsSource != "DECLARED" },
            basis = (
                listOf("on $on against $thresholdConstant ${threshold.value}: ${representedPct.stripTrailingZeros().toPlainString()}") +
                    weighed.map {
                        val line = through.getValue(it.partyId)
                        "${it.partyId} ${it.unitId} ${it.idealParts} of ${it.unitIdealParts} by ${line.attendance.id} ${line.attendance.channel} ${line.identityVerifiedAt ?: "-"}"
                    }.sorted()
                ).joinToString("\n"),
        )
    }

    /** Whether a quorum of this assembly has been weighed: from then on what its notice stated stays as it is. */
    @Transactional(readOnly = true)
    fun weighed(assemblyId: UUID): Boolean = sittings.findByAssemblyIdOrderBySessionNo(assemblyId).isNotEmpty()

    private fun sittingDay(assembly: Assembly): LocalDate = LocalDate.parse(toSofiaDate(assembly.scheduledAt))   // a Sofia calendar day (PM-SYS-004)
}
