package zues.app.money

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import zues.law.CostItem
import java.math.RoundingMode

/**
 * A charge line's derivation in Bulgarian, worded at read time from the issued line and its run's stored basis
 * (Rule: PM-FEE-018) — the key, the count, and the rate it was billed at, or the total it is a share of. The issued
 * English `derivation` is never rewritten (PM-FEE-015). Pure: no clock, no I/O.
 */
object DerivationBg {

    private val mapper = ObjectMapper()

    /** A malformed basis traces nothing: its lines get no derivation, the statement still answers. */
    fun parse(basisJson: String): JsonNode? = runCatching { mapper.readTree(basisJson) }.getOrNull()

    /** (rateMinor, text); both null when the basis holds no line to trace it to, never a guess. */
    // Rule: PM-FEE-018
    fun of(line: ChargeLineRow, basis: JsonNode): Pair<Long?, String?> {
        val tariff = basis.path("tariff")
        if (line.allocationKey == "METERED") {
            val price = tariff.path("consumption").firstOrNull { it.path("item").asText() == line.item }
                ?.path("priceMinor")?.takeIf { it.isIntegralNumber }?.asLong() ?: return null to null
            val uom = runCatching { CostItem.valueOf(line.item!!).unitOfMeasure }.getOrNull() ?: return null to null
            val reading = decimal(line, 3) ?: return null to null
            return price to "$reading $uom × ${euro(price)}/$uom"
        }
        val t = tariff.path("lines").firstOrNull {
            it.path("stream").asText() == line.component && it.path("key").asText() == line.allocationKey &&
                it.get("item")?.takeUnless { n -> n.isNull }?.asText() == line.item
        } ?: return null to null
        val count = when (line.allocationKey) {
            "PER_PERSON" -> line.quantity.toInt().let { if (it == 1) "1 човек" else "$it души" }
            "PER_UNIT" -> line.quantity.toInt().let { if (it == 1) "1 обект" else "$it обекта" }
            "BY_IDEAL_PARTS" -> "${decimal(line, 6) ?: return null to null} %"
            else -> return null to null
        }
        val rate = t.path("rateMinor").takeIf { it.isIntegralNumber }?.asLong()
        val total = t.path("totalMinor").takeIf { it.isIntegralNumber }?.asLong()
        val text = when {
            rate != null && line.allocationKey == "BY_IDEAL_PARTS" -> "$count ид. части от ${euro(rate)}"
            rate != null -> "$count × ${euro(rate)}"
            total != null -> "дял от ${euro(total)} по ${keyName(line.allocationKey)} · $count"
            else -> return null to null
        }
        return rate to text + (businessSuffix(line, basis) ?: return null to null)
    }

    /** PM-FEE-010 as the run applied it — the adopted multiple, else the statutory minimum the basis froze; null when the basis cannot say. */
    // Rule: PM-FEE-018
    private fun businessSuffix(line: ChargeLineRow, basis: JsonNode): String? {
        if (line.component == "REPAIR_FUND") return ""
        val unit = basis.path("units").firstOrNull { it.path("unitId").asText() == line.unitId.toString() } ?: return null
        if (!unit.path("businessUse").asBoolean()) return ""
        val adopted = basis.path("tariff").path("businessMultiplier")
        val multiple = (if (adopted.isNumber) adopted else basis.path("constants").path("BUSINESS_USE_MULTIPLIER_MIN"))
            .takeIf { it.isNumber && it.decimalValue().stripTrailingZeros().scale() <= 0 }?.asInt() ?: return null
        return " · ×$multiple стопанска дейност"
    }

    private fun keyName(key: String) = when (key) {
        "PER_PERSON" -> "брой души"
        "BY_IDEAL_PARTS" -> "идеални части"
        else -> "обект"
    }

    /** A count, not money: the line's quantity with a comma decimal. */
    private fun decimal(line: ChargeLineRow, scale: Int): String? =
        runCatching { line.quantity.setScale(scale, RoundingMode.UNNECESSARY) }.getOrNull()?.toPlainString()?.replace('.', ',')

    /** Euro from integer minor units: "1 200,00 €". Integer arithmetic only. */
    // Rule: PM-FEE-016
    fun euro(minor: Long): String {
        val sign = if (minor < 0) "-" else ""
        val whole = Math.abs(minor / 100).toString()                    // minor / 100 never overflows on abs
        val cents = Math.abs(minor % 100).toString().padStart(2, '0')
        val grouped = whole.reversed().chunked(3).joinToString(" ").reversed()
        return "$sign$grouped,$cents €"
    }
}
