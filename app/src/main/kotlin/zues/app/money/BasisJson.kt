package zues.app.money

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import zues.charges.Basis
import zues.charges.ChargeRun
import java.security.MessageDigest

/**
 * Serializes a run's frozen [Basis] to canonical JSON and hashes it (Rule: PM-FEE-014). The
 * representation is built from primitives with sorted keys, so the same run always yields
 * byte-identical JSON and the same hash — the property that makes a past bill reproducible.
 * Pure: no clock, no I/O.
 */
object BasisJson {

    private val mapper = ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    fun of(run: ChargeRun): String = mapper.writeValueAsString(basisMap(run.basis))

    fun hash(json: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(json.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun basisMap(basis: Basis): Map<String, Any?> = sortedMapOf(
        "legalDate" to basis.legalDate,
        "constants" to basis.constants,
        "units" to basis.units.map {
            sortedMapOf(
                "unitId" to it.unitId,
                "designation" to it.designation,
                "idealPartsPpm" to it.idealParts.ppmPct,
                "occupants" to it.occupants,
                "childrenUnder6" to it.childrenUnder6,
                "animals" to it.animals,
                "absentDays" to it.absentDays,
                "businessUse" to it.businessUse,
            )
        },
        "tariff" to sortedMapOf(
            "entranceId" to basis.tariff.entranceId,
            "period" to basis.tariff.period,
            "legalDate" to basis.tariff.legalDate,
            "businessMultiplier" to basis.tariff.businessMultiplier,
            "lines" to basis.tariff.lines.map {
                sortedMapOf(
                    "stream" to it.stream.name,
                    "key" to it.key.name,
                    "decisionId" to it.decisionId,
                    "rateMinor" to it.rateMinor,
                    "totalMinor" to it.totalMinor,
                )
            },
        ),
    )
}
