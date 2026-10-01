package zues.app.money

/**
 * The inputs a charge run computes from — the same snapshot a firm keeps in a
 * spreadsheet: the tariff its assembly adopted and the units it bills. Sent whole so the
 * calculation is self-contained and reproducible (PM-FEE-014).
 */
data class ChargeRunRequest(
    val entranceId: String,
    val period: String,        // YYYY-MM
    val legalDate: String,     // the date the law is read at (PM-SYS-002)
    val businessMultiplier: Int? = null,
    val lines: List<TariffLineRequest>,
    val units: List<UnitRequest>,
    val consumption: List<ConsumptionLineRequest>? = null,   // optional: a run without metered costs omits it (PM-FEE-017)
    val readings: List<ReadingRequest>? = null,
)

/** A metered cost, priced per unit of measure by GA decision (PM-FEE-017). */
data class ConsumptionLineRequest(
    val item: String,          // WATER | HEATING
    val priceMinor: Long,      // per m³ or kWh, in minor units
    val decisionId: String,    // the GA decision that adopted the metered method and the price (PM-FEE-012)
)

/** What one unit's own meter read for the period: up to three decimals, e.g. "12.345" (PM-FEE-017). */
data class ReadingRequest(
    val unitId: String,
    val item: String,          // WATER | HEATING
    val quantity: String,
)

data class TariffLineRequest(
    val stream: String,        // MANAGEMENT | MAINTENANCE | REPAIR_FUND
    val key: String,           // PER_PERSON | BY_IDEAL_PARTS | PER_UNIT — METERED is not a key a tariff line takes
    val decisionId: String,    // the GA decision that adopted it (PM-FEE-012)
    val rateMinor: Long? = null,
    val totalMinor: Long? = null,
    val item: String? = null,  // CONCIERGE — a named maintenance cost (PM-FEE-011)
)

data class UnitRequest(
    val unitId: String,
    val designation: String,
    val idealParts: String,    // exact decimal percent, e.g. "4.2000"
    val occupants: Int,
    val childrenUnder6: Int = 0,
    val animals: Int = 0,
    val absentDays: Int = 0,
    val businessUse: Boolean = false,   // pays the multiple: business use reached through the common parts (PM-FEE-010)
)

/** The computed charges. Money is minor units throughout (ADR-006); no floats cross the wire. */
data class ChargeRunResponse(
    val entranceId: String,
    val period: String,
    val legalDate: String,
    val lawVersion: String,
    val engineVersion: String,
    val totalMinor: Long,
    val charges: List<UnitChargeResponse>,
    val missingReadings: List<MissingReadingResponse> = emptyList(),
)

/** A unit a consumption line did not bill: its meter had no reading. Nothing is estimated (PM-FEE-017). */
data class MissingReadingResponse(val unitId: String, val item: String)

data class UnitChargeResponse(
    val unitId: String,
    val designation: String,
    val totalMinor: Long,
    val chargeablePersons: Int,
    val lines: List<ChargeLineResponse>,
)

data class ChargeLineResponse(
    val stream: String,
    val key: String,
    val amountMinor: Long,
    val derivation: String,
    val decisionId: String,    // the GA decision that set the key and rate (PM-FEE-003)
    val item: String? = null,  // CONCIERGE, WATER, HEATING — or none for the stream's own line
)
