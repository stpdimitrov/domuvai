package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.registry.Units
import java.time.LocalDate
import java.util.UUID

/** How a unit's billed headcount and its declared one differ (Rule: PM-BOOK-012). */
enum class HeadcountDifference {
    /** billed and declared, with different counts */
    COUNT_DIFFERS,
    /** billed by the run, and no longer a unit of the entrance in the book */
    NOT_IN_BOOK,
    /** a unit of the entrance in the book, and not billed by the run */
    NOT_BILLED,
}

/** One unit whose billed persons are not the persons the book declares. A side that does not exist has no counts. */
data class HeadcountMismatch(
    val unitId: UUID,
    val designation: String,
    val difference: HeadcountDifference,
    val billedOccupants: Int?,
    val billedChildrenUnder6: Int?,
    val declaredOccupants: Int?,
    val declaredChildrenUnder6: Int?,
)

/** The period's exception report (Rule: PM-BOOK-012): the units that differ, of how many compared. */
data class HeadcountCheck(
    val entranceId: UUID,
    val period: String,
    val legalDate: LocalDate,
    val chargeRunId: UUID,
    val unitsCompared: Int,
    val mismatches: List<HeadcountMismatch>,
)

/**
 * Reconciles the book against the fee headcount (Rule: PM-BOOK-012). The persons a period billed are the ones in the
 * basis its run keeps (PM-FEE-014); the persons the book declares are the registry's, read now, for the run's own
 * legal date. They differ when the book was changed afterwards for a date the run had already billed. Nothing is
 * corrected here: an issued period stays as issued (PM-FEE-015), and what to do about a difference is a person's.
 */
@Service
class HeadcountCheckService(
    private val runs: ChargeRunRepository,
    private val units: Units,
    private val json: ObjectMapper,
) {
    private data class Counted(val designation: String, val occupants: Int, val childrenUnder6: Int)

    // Rule: PM-BOOK-012
    @Transactional(readOnly = true)
    fun check(entranceId: UUID, period: String): HeadcountCheck {
        val run = runs.findByEntranceIdAndPeriod(entranceId, period)
            ?: throw NoSuchElementException("entrance $entranceId has no charge run issued for $period")
        val billed = json.readTree(run.basis.json).path("units").associate {
            UUID.fromString(it.path("unitId").asText()) to
                Counted(it.path("designation").asText(), it.path("occupants").asInt(), it.path("childrenUnder6").asInt())
        }
        val declared = units.forEntrance(entranceId, run.legalDate).associate { it.unitId to Counted(it.designation, it.occupants, it.childrenUnder6) }

        val mismatches = (billed.keys + declared.keys).mapNotNull { unitId ->
            val b = billed[unitId]
            val d = declared[unitId]
            val difference = when {
                d == null -> HeadcountDifference.NOT_IN_BOOK
                b == null -> HeadcountDifference.NOT_BILLED
                b.occupants != d.occupants || b.childrenUnder6 != d.childrenUnder6 -> HeadcountDifference.COUNT_DIFFERS
                else -> return@mapNotNull null
            }
            HeadcountMismatch(unitId, (d ?: b)!!.designation, difference, b?.occupants, b?.childrenUnder6, d?.occupants, d?.childrenUnder6)
        }.sortedWith(compareBy({ it.designation }, { it.unitId }))
        return HeadcountCheck(entranceId, run.period, run.legalDate, run.id, (billed.keys + declared.keys).size, mismatches)
    }
}

/** The period's headcount exception report (Rule: PM-BOOK-012) — a read; it changes nothing. */
@RestController
@RequestMapping("/api/money/entrances/{entranceId}/charge-runs/{period}/headcount")
class HeadcountCheckController(private val headcount: HeadcountCheckService) {

    @GetMapping
    fun check(@PathVariable entranceId: UUID, @PathVariable period: String): HeadcountCheck = headcount.check(entranceId, period)

    /** No run issued for the period, or no such entrance → 404. */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoSuchElementException): Map<String, String> = mapOf("error" to (e.message ?: "not found"))
}
