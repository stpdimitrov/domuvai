package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.assembly.GaDecisions
import zues.app.identity_org.WhoAsks
import zues.law.AllocationKey
import zues.law.CostItem
import zues.law.CostStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** A line the assembly adopted, as a run is sent it today, less the decision: the version carries that once. */
data class AdoptedLine(
    val stream: String,        // MANAGEMENT | MAINTENANCE | REPAIR_FUND
    val key: String,           // PER_PERSON | BY_IDEAL_PARTS | PER_UNIT
    val rateMinor: Long? = null,
    val totalMinor: Long? = null,
    val item: String? = null,  // CONCIERGE
)

/** A metered cost's adopted price per unit of measure (PM-FEE-017). */
data class MeteredPrice(val item: String, val priceMinor: Long)

data class RecordTariffVersion(
    val decisionId: String,    // the rule's protocol_id: the GA decision the version is adopted under
    val validFrom: String,     // YYYY-MM, the first month in force
    val validTo: String? = null,   // YYYY-MM, the last month in force; open when absent
    val lines: List<AdoptedLine> = emptyList(),
    val consumption: List<MeteredPrice> = emptyList(),
)

data class TariffVersionView(
    val id: UUID,
    val decisionId: String,
    val validFrom: String,
    val validTo: String?,
    val lines: List<AdoptedLine>,
    val consumption: List<MeteredPrice>,
)

/** The adopted tariff as stored in the row's `lines` column. */
data class AdoptedTariff(val lines: List<AdoptedLine>, val consumption: List<MeteredPrice>)

/** Mapped unqualified; the search_path resolves `tariff_version` to `money.tariff_version`. Never altered. */
@Table("tariff_version")
data class TariffVersionRow(
    @Id val id: UUID,
    val entranceId: UUID,
    val decisionId: String,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val lines: JsonbValue,
)

interface TariffVersionRepository : ListCrudRepository<TariffVersionRow, UUID> {
    fun findByEntranceId(entranceId: UUID): List<TariffVersionRow>
}

class TariffVersionConflict(message: String) : RuntimeException(message)

/**
 * Rule: PM-FEE-012 — the version in force for [period]: the one begun latest on or before it, unless its own
 * `validTo` has passed. A later version ends an earlier one; nothing falls back to it (ADR-001, dated data).
 */
// Rule: PM-FEE-012
fun inForce(versions: List<TariffVersionRow>, period: YearMonth): TariffVersionRow? =
    versions.filter { it.validFrom <= period.atDay(1) }.maxByOrNull { it.validFrom }
        ?.takeIf { it.validTo == null || it.validTo >= period.atDay(1) }

@Service
class TariffVersions(
    private val versions: TariffVersionRepository,
    private val aggregates: JdbcAggregateTemplate,
    private val decisions: GaDecisions,
    private val json: ObjectMapper,
) {
    // Rule: PM-FEE-012
    @Transactional
    fun record(entranceId: UUID, request: RecordTariffVersion): TariffVersionView {
        val from = month(request.validFrom).atDay(1)
        val to = request.validTo?.let { month(it).atEndOfMonth() }
        require(to == null || to >= from) { "a tariff version ends no earlier than it begins: ${request.validTo} is before ${request.validFrom}" }
        require(request.lines.isNotEmpty() || request.consumption.isNotEmpty()) { "a tariff version adopts at least one line or metered price" }
        request.lines.forEach(::check)
        request.consumption.forEach { meteredItem(it.item); require(it.priceMinor >= 0) { "a metered price is not negative" } }
        require(decisions.passed(entranceId, request.decisionId)) {
            "no passed GA decision of this entrance is ${request.decisionId}: no tariff is recorded without one (PM-FEE-012)"
        }
        if (versions.findByEntranceId(entranceId).any { it.validFrom == from }) {
            throw TariffVersionConflict("a tariff version of this entrance already begins in ${request.validFrom}; a later one ends it, two never begin together")
        }
        val row = TariffVersionRow(
            UUID.randomUUID(), entranceId, request.decisionId, from, to,
            JsonbValue(json.writeValueAsString(AdoptedTariff(request.lines, request.consumption))),
        )
        aggregates.insert(row)
        return row.view()
    }

    /** The entrance's versions, the latest begun first — or, for a [period], only the one in force then. */
    // Rule: PM-FEE-012
    fun list(entranceId: UUID, period: String?): List<TariffVersionView> {
        val all = versions.findByEntranceId(entranceId)
        val shown = if (period == null) all.sortedByDescending { it.validFrom } else listOfNotNull(inForce(all, month(period)))
        return shown.map { it.view() }
    }

    private fun TariffVersionRow.view(): TariffVersionView {
        val tariff = json.readValue<AdoptedTariff>(lines.json)
        return TariffVersionView(id, decisionId, YearMonth.from(validFrom).toString(), validTo?.let { YearMonth.from(it).toString() }, tariff.lines, tariff.consumption)
    }

    private fun month(text: String): YearMonth =
        runCatching { YearMonth.parse(text) }.getOrNull() ?: throw IllegalArgumentException("a month is YYYY-MM, not $text")

    private fun check(line: AdoptedLine) {
        val stream = CostStream.entries.firstOrNull { it.name == line.stream }
            ?: throw IllegalArgumentException("a stream is ${CostStream.entries.joinToString(" or ")}, not ${line.stream}")
        val keys = AllocationKey.entries - AllocationKey.METERED
        require(keys.any { it.name == line.key }) { "a tariff line's key is ${keys.joinToString(" or ")}, not ${line.key}" }
        require((line.rateMinor == null) != (line.totalMinor == null)) { "a tariff line has a rate or a total, exactly one" }
        require((line.rateMinor ?: line.totalMinor!!) >= 0) { "a tariff line's amount is not negative" }
        line.item?.let { name ->
            val item = CostItem.entries.firstOrNull { !it.metered && it.name == name }
                ?: throw IllegalArgumentException("a line's item is ${CostItem.entries.filterNot { it.metered }.joinToString(" or ")}, not $name")
            require(item.stream == stream) { "$name is a ${item.stream} cost, not $stream (PM-FEE-011)" }
        }
    }
}

/** Rule: PM-FEE-012 — an entrance's tariff versions, recorded and read by who keeps its accounts (PM-SEC-001). */
@RestController
@RequestMapping("/api/money/entrances/{entranceId}/tariff-versions")
class TariffVersionController(private val tariffs: TariffVersions, private val access: MoneyAccess, private val who: WhoAsks) {

    // Rule: PM-FEE-012
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun record(@PathVariable entranceId: UUID, @RequestBody request: RecordTariffVersion, signedIn: JwtAuthenticationToken?): TariffVersionView {
        access.keep(entranceId, who.of(signedIn))                                         // Rule: PM-SEC-001
        return tariffs.record(entranceId, request)
    }

    // Rule: PM-FEE-012
    @GetMapping
    fun list(@PathVariable entranceId: UUID, @RequestParam(required = false) period: String?, signedIn: JwtAuthenticationToken?): List<TariffVersionView> {
        access.keep(entranceId, who.of(signedIn))                                         // Rule: PM-SEC-001
        return tariffs.list(entranceId, period)
    }

    /** No passed decision of the entrance, a malformed month, or a malformed line → 400. */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalid(e: IllegalArgumentException): Map<String, String> = mapOf("error" to (e.message ?: "invalid tariff version"))

    /** A version already begins that month, or the table's own guard on a raced one → 409. */
    @ExceptionHandler(TariffVersionConflict::class, DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: RuntimeException): Map<String, String> =
        mapOf("error" to ((e as? TariffVersionConflict)?.message ?: "a tariff version of this entrance already begins that month"))
}
