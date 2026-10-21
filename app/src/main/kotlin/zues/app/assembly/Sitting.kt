package zues.app.assembly

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.app.registry.Holdings
import zues.app.registry.TitleRole
import zues.kernel.addCalendarDays
import zues.kernel.toSofiaDate
import zues.law.CATALOGUE_VERSION
import zues.law.Constant
import zues.law.ENGINE_VERSION
import zues.law.constantOn
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    /** The one owner of more than the law's share at the notice date who raises the threshold (PM-GA-015), and that share. */
    val concentratedBy: UUID? = null,
    val concentratedPct: BigDecimal? = null,
) {
    /** "At least" the threshold, of all the ideal parts (PM-GA-012) — and nothing in the way of weighing. */
    val met: Boolean get() = cannotWeigh.isEmpty() && representedPct >= thresholdPct
    /** How many holdings were weighed: the basis's lines after the first, which says what was weighed against what. */
    val counted: Int get() = basis.lines().size - 1
    val basisHash: String get() = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** The quorum of the sitting an assembly is at ([sessionNo], which opens from [opensFrom]), and the sittings already weighed. */
data class QuorumRead(val quorum: Quorum, val sittings: List<Sitting>, val sessionNo: Int, val opensFrom: Instant)

/**
 * The sittings an assembly may hold (Rule: PM-GA-013, PM-GA-014): the first at the hour its notice stated; if its quorum
 * falls short, the next postponed by the law's interval, against the reduced threshold; if that falls short too, the
 * last on the following day, held whatever the share represented. How many there are and their intervals are `law`'s
 * as in force on the day the assembly was noticed to meet; each sitting's threshold is the one in force on its own day
 * (PM-SYS-002), as the book is that day's.
 */
// Rule: PM-GA-013
// Rule: PM-GA-014
// The kernel keeps its own Sofia zone private; it publishes calendar days, not a wall-clock hour a day later.
private val SOFIA: ZoneId = ZoneId.of("Europe/Sofia")

internal class Ladder(private val assembly: Assembly, private val weighed: List<Sitting>) {
    private val firstDay = LocalDate.parse(toSofiaDate(assembly.scheduledAt))              // a Sofia calendar day (PM-SYS-004)
    private fun law(code: String) = constantOn(code, firstDay.toString())
    private val afterDays = law("GA_LAST_SITTING_AFTER_DAYS").value.toInt()
    val last: Int = law("GA_SITTINGS").value.toInt()

    /** The sitting the assembly is at: the one that opened, else the one after the last weighed short — null when none is left. */
    val atHand: Int? = weighed.firstOrNull { it.quorumMet }?.sessionNo ?: (weighed.size + 1).takeIf { it <= last }

    fun day(n: Int): LocalDate = if (n == last && n != FIRST_SITTING) LocalDate.parse(addCalendarDays(firstDay.toString(), afterDays)) else firstDay

    // TODO(legal): PM-GA-014 — the hour of the following day's sitting is not in the catalogue; the strictest reading is
    // taken: not before the hour the notice stated, on the clock in Sofia, the law's count of calendar days later — the
    // same hour across a change of summer time, never an hour early.
    fun opensFrom(n: Int): Instant = when {
        n == FIRST_SITTING -> assembly.scheduledAt
        n == last -> assembly.scheduledAt.atZone(SOFIA).plusDays(afterDays.toLong()).toInstant()
        else -> weighed.single { it.sessionNo == n - 1 }.weighedAt.plus(Duration.ofMinutes(law("GA_POSTPONE_MINUTES").value.toLong()))
    }

    fun thresholdCode(n: Int): String = when (n) {
        FIRST_SITTING -> "GA_QUORUM_PCT"
        last -> "GA_LAST_SITTING_QUORUM_PCT"
        else -> "GA_REDUCED_QUORUM_PCT"
    }

    /** The sittings whose register is this one's: those held the same day, up to it — the postponed sitting is the same gathering, an hour on. */
    fun gathering(n: Int): Set<Int> = (FIRST_SITTING..n).filterTo(mutableSetOf()) { day(it) == day(n) }
}

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
        val weighed = sittings.findByAssemblyIdOrderBySessionNo(assemblyId)
        val ladder = Ladder(assembly, weighed)
        val n = ladder.atHand ?: weighed.last().sessionNo
        return QuorumRead(weigh(assembly, ladder, n), weighed, n, ladder.opensFrom(n))
    }

    /**
     * Weighs the quorum of the sitting the assembly is at, once, and opens the assembly if it is met: the first not
     * before the hour the notice stated; the next not before the law's interval after the first was weighed short; the
     * last on the following day. A weighing that falls short is recorded too: it is what the next sitting follows from.
     * One that cannot be made at all is refused, and records nothing.
     */
    // Rule: PM-GA-012
    // Rule: PM-GA-013
    // Rule: PM-GA-014
    @Transactional
    fun open(entranceId: UUID, assemblyId: UUID, by: Actor): Sitting {
        val assembly = assemblies.lock(assemblyId, entranceId) ?: throw NoSuchElementException("no assembly $assemblyId in entrance $entranceId")
        check(assembly.status == AssemblyStatus.NOTICED.name) { "a sitting opens for an assembly whose notice is posted and that is not yet open; this one is ${assembly.status}" }
        val ladder = Ladder(assembly, sittings.findByAssemblyIdOrderBySessionNo(assemblyId))
        val n = ladder.atHand ?: throw IllegalStateException("every sitting the law allows this assembly is weighed")
        val now = clock.instant()
        // TODO(legal): PM-GA-013 — a postponed sitting whose hour falls past the day of the assembly: the catalogue does
        // not say whether it is held after midnight, or the assembly goes to the following day; it is refused, and says so.
        check(LocalDate.parse(toSofiaDate(ladder.opensFrom(n))) == ladder.day(n)) {
            "sitting $n would open at ${ladder.opensFrom(n)}, past ${ladder.day(n)}, the day it is held on: whether it may is not settled (TODO(legal): PM-GA-013)"
        }
        check(!now.isBefore(ladder.opensFrom(n))) { "sitting $n does not open before ${ladder.opensFrom(n)} — the hour its notice stated, or the law's interval after the last" }
        check(LocalDate.parse(toSofiaDate(now)) == ladder.day(n)) { "sitting $n is held on ${ladder.day(n)}" }
        val quorum = weigh(assembly, ladder, n)
        if (quorum.cannotWeigh.isNotEmpty()) throw QuorumNotWeighable("the quorum cannot be weighed: " + quorum.cannotWeigh.joinToString("; "))
        // TODO(legal): PM-GA-014 — whether the last sitting is held with nobody present is not settled; the strictest
        // reading is taken: "regardless of the ideal parts represented" needs somebody there to represent them.
        check(n != ladder.last || n == FIRST_SITTING || quorum.counted > 0) { "the last sitting is held whatever the share represented — but not with nobody present (TODO(legal): PM-GA-014)" }
        val sitting = aggregates.insert(
            Sitting(
                id = UUID.randomUUID(), entranceId = entranceId, assemblyId = assemblyId, sessionNo = n,
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
    // Rule: PM-GA-013
    // Rule: PM-GA-014
    // Rule: PM-GA-015
    // TODO(legal): PM-GA-012 — whose ideal parts count for a unit with both an owner and a holder of a right of use
    private fun weigh(assembly: Assembly, ladder: Ladder, n: Int): Quorum {
        val on = ladder.day(n)
        val register = attendance.register(assembly.entranceId, assembly.id, n)
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
        val ofSitting = constantOn(ladder.thresholdCode(n), on.toString())
        val concentration = concentration(assembly)
        // The owner's decision (2026-10-21, Q4c): the higher quorum governs the first and the postponed sittings; the
        // following day's opens at any share, as PM-GA-014 says — so one owner of more than half can neither carry a thin
        // sitting nor keep the assembly from ever being held.
        // TODO(legal): PM-GA-015 — counsel to confirm which sittings чл. 15 ал. 4 governs
        val raised = n == FIRST_SITTING || n != ladder.last
        val threshold = concentration.raisedTo?.takeIf { raised && BigDecimal(it.value) > BigDecimal(ofSitting.value) } ?: ofSitting
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
            cannotWeigh = cannotWeigh + (if (raised) concentration.cannotWeigh else emptyList()),   // the following day's is held at any share
            derivedParts = weighed.any { it.idealPartsSource != "DECLARED" },
            basis = (
                listOf(
                    "sitting $n on $on against $thresholdConstant ${threshold.value}" +
                        " (${concentration.said}${if (raised || concentration.raisedTo == null) "" else "; not raised at this sitting"})" +
                        ": ${representedPct.stripTrailingZeros().toPlainString()}",
                ) +
                    weighed.map {
                        val line = through.getValue(it.partyId)
                        "${it.partyId} ${it.unitId} ${it.idealParts} of ${it.unitIdealParts} by ${line.attendance.id} ${line.attendance.channel} ${line.identityVerifiedAt ?: "-"}"
                    }.sorted()
                ).joinToString("\n"),
            concentratedBy = concentration.party,
            concentratedPct = concentration.pct,
        )
    }

    /** What the notice date's book says of concentration: the owner over the law's share, if any, the threshold it raises the quorum to, what the basis says of it. */
    private data class Concentration(val party: UUID?, val pct: BigDecimal?, val raisedTo: Constant?, val said: String, val cannotWeigh: List<String> = emptyList())

    /**
     * The one owner of more than the law's share of all the ideal parts on the day the notice was posted (Rule:
     * PM-GA-015), their share, and the threshold that day's law raises the quorum to — both numbers read on the notice
     * date, as the rule is "selected … at notice date". A party's ideal parts are summed over every unit they own in the
     * entrance, by title share, a unit never more than itself (PM-ORG-005); a unit owned for more than the whole of it
     * that day leaves the concentration unknowable, and the quorum is not weighed. Co-owners are each their own person.
     * An assembly not yet noticed has no notice date: its meter says so, and no sitting opens (that needs a notice).
     */
    // Rule: PM-GA-015
    // TODO(legal): PM-GA-015 — whether co-owners of common property (spouses) are one person for this is not settled; each is their own
    private fun concentration(assembly: Assembly): Concentration {
        val posted = assembly.noticePostedAt
        if (posted == null) {
            check(assembly.status == AssemblyStatus.DRAFT.name) { "the notice of assembly ${assembly.id} has no posting date: its quorum cannot be chosen (PM-GA-015)" }
            return Concentration(null, null, null, "not yet noticed: the concentration is chosen at the notice date")
        }
        val day = toSofiaDate(posted)                                                       // a Sofia calendar day (PM-SYS-004)
        val more = constantOn("GA_CONCENTRATION_PCT", day)
        val book = holdings.inForce(assembly.entranceId, LocalDate.parse(day), TitleRole.OWN)
        val byParty = book.holdings.groupBy { it.partyId }.mapValues { (_, held) ->
            held.groupBy { it.unitId }.values.fold(BigDecimal.ZERO) { sum, ofUnit ->
                sum + ofUnit.fold(BigDecimal.ZERO) { parts, h -> parts + BigDecimal(h.idealParts) }.min(BigDecimal(ofUnit.first().unitIdealParts))
            }
        }
        val over = byParty.filterValues { it > BigDecimal(more.value) }                     // "more than"
        val doubtful = book.holdings.filter { it.partyId in over && it.unitId in book.overHeldUnitIds }.map { it.unitId }.distinct().sortedBy { it.toString() }
        if (doubtful.isNotEmpty()) {
            return Concentration(
                null, null, null, "concentration at the notice on $day unknowable",
                doubtful.map { "unit $it was owned for more than the whole of it on $day, the notice date: whether one owner held more than ${more.value}% cannot be told (PM-ORG-005, PM-GA-015)" },
            )
        }
        val top = over.maxByOrNull { it.value }
            ?: return Concentration(null, null, null, "no owner over ${more.value}% at the notice on $day")
        return Concentration(
            top.key, top.value, constantOn("GA_CONCENTRATED_QUORUM_PCT", day),
            "one owner, ${top.key}, held ${top.value.stripTrailingZeros().toPlainString()} at the notice on $day",
        )
    }

    /** Whether a quorum of this assembly has been weighed: from then on what its notice stated stays as it is. */
    @Transactional(readOnly = true)
    fun weighed(assemblyId: UUID): Boolean = sittings.findByAssemblyIdOrderBySessionNo(assemblyId).isNotEmpty()
}
