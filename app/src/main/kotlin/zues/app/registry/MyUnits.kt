package zues.app.registry

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One unit the asker holds on a date, in one role: where it is, what it is, and the asker's own share of it. It carries
 * ids, the designation, the type, the entrance's label and numbers — no identity number, no address, no person's name
 * (Rule: PM-BOOK-011), and nothing of any co-owner (Rule: PM-SEC-002).
 */
data class MyUnit(
    val entranceId: UUID,
    val entranceLabel: String,
    val unitId: UUID,
    val designation: String,
    val unitType: String,
    val titleRole: String,          // OWN | USR
    val share: String,              // the asker's own share, exact decimal in (0, 1]
    val idealParts: String,         // the unit's ideal parts × that share, exact
    val idealPartsSource: String,   // DECLARED | DERIVED
)

/**
 * Which units are mine: the asker's own titles in force on a date, over the existing title, unit and entrance tables.
 * The party is the only filter and it is applied in the query (Rule: PM-SEC-002) — another person's title is never read.
 */
@Service
class MyUnits(
    private val titles: TitleRepository,
    private val units: PropertyUnitRepository,
    private val entrances: EntranceRepository,
) {

    // Rule: PM-ORG-011
    // Rule: PM-ORG-005
    // Rule: PM-SEC-002
    // Rule: PM-BOOK-011
    @Transactional(readOnly = true)
    fun of(party: UUID, on: LocalDate): List<MyUnit> {
        val inForce = titles.findByPartyId(party)
            .filter { it.partyId == party && it.validFrom <= on && (it.validTo == null || on < it.validTo) }
        if (inForce.isEmpty()) return emptyList()
        val unitById = units.findAllById(inForce.map { it.unitId }.toSet()).associateBy { it.id }
        val entranceById = entrances.findAllById(unitById.values.map { it.entranceId }.toSet()).associateBy { it.id }
        return inForce
            .groupBy { it.unitId to it.titleRole }                    // two titles of one role over a unit are one holding
            .mapNotNull { (key, held) ->
                val unit = unitById[key.first] ?: return@mapNotNull null
                val entrance = entranceById[unit.entranceId] ?: return@mapNotNull null
                if (held.any { it.entranceId != unit.entranceId }) return@mapNotNull null      // a title filed under another entrance's unit is nobody's
                val share = held.fold(BigDecimal.ZERO) { sum, title -> sum + title.share }
                MyUnit(entrance.id, entrance.label, unit.id, unit.designation, unit.unitType, key.second,
                    plain(share), plain(unit.idealPartsPct * share), unit.idealPartsSource)
            }
            .sortedWith(compareBy({ it.entranceLabel }, { it.designation }, { it.titleRole }))
    }

    /** An exact decimal with no padding zeros and no exponent: 6.250000 → "6.25". */
    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}
