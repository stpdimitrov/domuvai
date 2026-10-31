package zues.app.money

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import zues.app.identity_org.WhoAsks
import zues.app.policy.Asking
import zues.app.registry.Units
import java.time.LocalDate
import java.util.UUID

/** One period an entrance has a run issued for: the run's header, no amount and no person (Rule: PM-FEE-014). */
data class IssuedPeriod(val period: String, val legalDate: LocalDate, val chargeRunId: UUID)

/** What a read of an entrance's runs found missing, so a caller need not read the words (Rule: PM-BOOK-012). */
enum class NoChargeRunCode { NO_RUN, NO_ENTRANCE }

/** The 404 of a read of an entrance's runs. */
data class NoChargeRunAnswer(val error: String, val code: NoChargeRunCode)

/** The entrance has no run for the period, or there is no such entrance. */
class NoChargeRun private constructor(val answer: NoChargeRunAnswer) : NoSuchElementException(answer.error) {
    companion object {
        fun of(entranceId: UUID, period: String) =
            NoChargeRun(NoChargeRunAnswer("entrance $entranceId has no charge run issued for $period", NoChargeRunCode.NO_RUN))

        fun noEntrance(entranceId: UUID) = NoChargeRun(NoChargeRunAnswer("no entrance $entranceId", NoChargeRunCode.NO_ENTRANCE))
    }
}

/**
 * The months a monthly report can be asked for (Rule: PM-BOOK-012): the periods an entrance has a run issued for, the
 * newest first. Read as the entrance's arrears are, so a reader refused them is told nothing of an entrance or its runs.
 * An entrance with no run has an empty list; one that does not exist is not found.
 */
@Service
class IssuedPeriodsService(private val runs: ChargeRunHeaders, private val units: Units, private val access: MoneyAccess) {
    // Rule: PM-BOOK-012
    // Rule: PM-FEE-014
    // Rule: PM-SEC-002
    fun of(entranceId: UUID, who: Asking): List<IssuedPeriod> {
        access.entranceArrears(entranceId, who)
        val issued = runs.issued(entranceId)
        if (issued.isEmpty() && !units.entranceExists(entranceId)) throw NoChargeRun.noEntrance(entranceId)
        return issued.map { IssuedPeriod(it.period, it.legalDate, it.id) }
    }
}

/** The periods an entrance has a charge run issued for (Rule: PM-BOOK-012) — a read; it changes nothing. */
@RestController
@RequestMapping("/api/money/entrances/{entranceId}/charge-runs")
class IssuedPeriodsController(private val periods: IssuedPeriodsService, private val who: WhoAsks) {

    @GetMapping
    fun of(@PathVariable entranceId: UUID, signedIn: JwtAuthenticationToken?): List<IssuedPeriod> =
        periods.of(entranceId, who.of(signedIn))

    /** No such entrance → 404. */
    @ExceptionHandler(NoChargeRun::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMissing(e: NoChargeRun): NoChargeRunAnswer = e.answer
}
