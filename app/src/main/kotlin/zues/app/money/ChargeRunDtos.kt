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
)

data class TariffLineRequest(
    val stream: String,        // MANAGEMENT | MAINTENANCE | REPAIR_FUND
    val key: String,           // PER_PERSON | BY_IDEAL_PARTS | PER_UNIT
    val decisionId: String,    // the GA decision that adopted it (PM-FEE-012)
    val rateMinor: Long? = null,
    val totalMinor: Long? = null,
)

data class UnitRequest(
    val unitId: String,
    val designation: String,
    val idealParts: String,    // exact decimal percent, e.g. "4.2000"
    val occupants: Int,
    val childrenUnder6: Int = 0,
    val animals: Int = 0,
    val absentDays: Int = 0,
    val businessUse: Boolean = false,
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
)

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
)
