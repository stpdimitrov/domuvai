package zues.app.assembly

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.conversion.DbActionExecutionException
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.stereotype.Service
import zues.app.policy.Action
import zues.app.policy.Asking
import zues.kernel.toSofiaDate
import zues.law.CATALOGUE_VERSION
import zues.law.ENGINE_VERSION
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A decision to record, as its protocol gives it. Each field may be missing: what is missing is said only to the administrator. */
data class RecordPaperDecision(
    val decisionText: String? = null,
    val protocolDate: LocalDate? = null,
    val protocolRef: String? = null,
    val archiveLocation: String? = null,
    val custodian: String? = null,
)

/**
 * A decision the general assembly took on paper, transcribed from its protocol (the owner's decision on #396,
 * 2026-10-31). It claims no sitting, tally or majority: those are on the paper, and the paper stays the original. The
 * login is who recorded it — none only with sign-in switched off, when nobody is asking.
 */
@Table("paper_decision")
data class PaperDecision(
    @Id val id: UUID,
    val entranceId: UUID,
    val decisionText: String,
    val protocolDate: LocalDate,
    val protocolRef: String,
    val archiveLocation: String,
    val custodian: String,
    val basis: String,
    val basisHash: String,
    val lawVersion: String,
    val engineVersion: String,
    val loginIssuer: String?,
    val loginSubject: String?,
    val recordedAt: Instant,
)

interface PaperDecisionRepository : ListCrudRepository<PaperDecision, UUID> {
    fun findByEntranceIdOrderByProtocolDateDescRecordedAtDesc(entranceId: UUID): List<PaperDecision>
}

/**
 * An entrance's decisions taken on paper. One is recorded only by the deployment's administrator — the policy decides,
 * as at today in Sofia, before anything else is looked at, and a refused caller learns nothing else. With sign-in
 * switched off in so many words nobody is asking, and the record names nobody (the demo seed's way in). Recording is
 * the only write: the table refuses any other (Rule: PM-SEC-004).
 */
@Service
class PaperDecisions(
    private val aggregates: JdbcAggregateTemplate,
    private val stored: PaperDecisionRepository,
    private val reads: AssemblyReads,
    private val clock: Clock,
) {
    // Rule: PM-FEE-012
    // Rule: PM-GA-022
    // Rule: PM-DOC-007
    // Rule: PM-SEC-001
    // Rule: PM-SEC-004
    fun record(by: Asking, entranceId: UUID, request: RecordPaperDecision): PaperDecision {
        val decision = reads.decided(entranceId, by, Action.RECORD_PAPER_DECISION)
        if (decision != null && !(decision.allowed && by.login != null)) throw ActRefused(decision.ruleId)
        val text = textOf(request.decisionText)
        val date = requireNotNull(request.protocolDate) { "the protocol's date is required" }
        require(!date.isAfter(LocalDate.parse(toSofiaDate(clock.instant())))) { "the protocol's date is not in the future" }
        val ref = lineOf(request.protocolRef, "the protocol's reference")
        val location = lineOf(request.archiveLocation, "where the paper original is kept")
        val custodian = lineOf(request.custodian, "who keeps the paper original")
        val basis = "paper protocol $ref of $date, kept at $location by $custodian\n$text"
        val hash = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray()).joinToString("") { "%02x".format(it) }
        val row = PaperDecision(
            UUID.randomUUID(), entranceId, text, date, ref, location, custodian, basis, hash,
            CATALOGUE_VERSION, ENGINE_VERSION, by.login?.issuer, by.login?.subject, clock.instant(),
        )
        try {
            return aggregates.insert(row)
        } catch (e: DbActionExecutionException) {
            // the registry's own tables are not this module's to read: the foreign key says who is missing
            if (missingReference(e) != null) throw NoSuchElementException("no entrance $entranceId")
            throw e
        }
    }

    /**
     * The entrance's records, the latest protocol first, read as an assembly is. Each with its custodian's name only for
     * a reader of the mandates — a person's name, kept to who reads the offices (PM-SEC-002, strictest reading).
     */
    // Rule: PM-GA-022
    // Rule: PM-DOC-007
    // Rule: PM-SEC-001
    // Rule: PM-SEC-002
    fun list(by: Asking, entranceId: UUID): List<PaperDecisionView> {
        reads.require(entranceId, by)
        val namesCustodian = reads.decided(entranceId, by, Action.READ_MANDATES)?.allowed != false
        return stored.findByEntranceIdOrderByProtocolDateDescRecordedAtDesc(entranceId).map { PaperDecisionView.of(it, namesCustodian) }
    }

    /** A line as it is kept: ordinary characters, required. */
    private fun lineOf(given: String?, what: String): String =
        given.orEmpty().filterNot { it.isISOControl() }.trim().also { require(it.isNotEmpty() && it.length <= LINE_MAX) { "$what is required, in at most $LINE_MAX characters" } }

    /** The decision's text as it is kept: its lines stay, nothing else that does not print. */
    private fun textOf(given: String?): String =
        given.orEmpty().filterNot { it.isISOControl() && it != '\n' }.trim().also { require(it.isNotEmpty() && it.length <= TEXT_MAX) { "the decision's text is required, in at most $TEXT_MAX characters" } }

    private companion object {
        const val LINE_MAX = 500
        const val TEXT_MAX = 10_000
    }
}

/** A recorded decision as it is read; [custodian] is none for whoever may not read it. */
data class PaperDecisionView(
    val id: UUID,
    val decisionText: String,
    val protocolDate: LocalDate,
    val protocolRef: String,
    val archiveLocation: String,
    val custodian: String?,
    val basisHash: String,
    val lawVersion: String,
    val engineVersion: String,
    val recordedAt: Instant,
) {
    companion object {
        fun of(row: PaperDecision, namesCustodian: Boolean) = PaperDecisionView(
            row.id, row.decisionText, row.protocolDate, row.protocolRef, row.archiveLocation, row.custodian.takeIf { namesCustodian },
            row.basisHash, row.lawVersion, row.engineVersion, row.recordedAt,
        )
    }
}
