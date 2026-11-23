package zues.app.money

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.time.YearMonth
import java.util.UUID

/**
 * Test-only: a run bills the tariff version in force for its period (PM-FEE-012), so a test that runs one first
 * adopts a version. Unit tests hold the rows ([versionRow], [tariffVersionsOver]); ITs record one through the real
 * service ([adopt]), whose `GaDecisions` they answer as passed.
 */
fun versionRow(
    entranceId: UUID,
    from: String,
    to: String? = null,
    decisionId: String = "GA-2026-1",
    lines: List<AdoptedLine> = emptyList(),
    consumption: List<MeteredPrice> = emptyList(),
) = TariffVersionRow(
    UUID.randomUUID(), entranceId, decisionId, YearMonth.parse(from).atDay(1), to?.let { YearMonth.parse(it).atEndOfMonth() },
    JsonbValue(jacksonObjectMapper().writeValueAsString(AdoptedTariff(lines, consumption))),
)

/** The real [TariffVersions] over whatever [rows] answers when asked; every entrance sees the same rows. */
fun tariffVersionsOver(rows: () -> List<TariffVersionRow>): TariffVersions {
    val repository: TariffVersionRepository = mock { on { findByEntranceId(any()) } doAnswer { rows() } }
    return TariffVersions(repository, mock(), mock(), jacksonObjectMapper())
}

/**
 * An IT's run of [period]: unless a version is already in force then, records an open-ended one beginning [period]
 * that adopts [lines] and [consumption] under [decisionId]. Returns the run's request, which sends no amount.
 */
fun TariffVersions.adopted(
    entranceId: UUID,
    period: String,
    legalDate: String,
    businessMultiplier: Int? = null,
    lines: List<AdoptedLine> = emptyList(),
    consumption: List<MeteredPrice> = emptyList(),
    readings: List<ReadingRequest>? = null,
    decisionId: String = "GA-2026-1",
): StoredChargeRunRequest {
    if (list(entranceId, period).isEmpty()) record(entranceId, RecordTariffVersion(decisionId, period, lines = lines, consumption = consumption))
    return StoredChargeRunRequest(period, legalDate, businessMultiplier, readings)
}
