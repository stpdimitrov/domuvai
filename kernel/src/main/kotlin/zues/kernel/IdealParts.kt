package zues.kernel

/**
 * Ideal parts as a percentage, held as exact integer millionths of a percent.
 * 12.345600% is 12_345_600. Never a float. Rule: PM-ORG-002
 */
@JvmInline
value class IdealParts private constructor(val ppmPct: Int) {

    fun format(): String = "${ppmPct / PPM}.${(ppmPct % PPM).toString().padStart(6, '0')}"

    companion object {
        const val PPM: Int = 1_000_000
        val WHOLE: IdealParts = IdealParts(100 * PPM)

        private val DECIMAL = Regex("""^(\d{1,3})(?:\.(\d{1,6}))?$""")

        fun of(decimalString: String): IdealParts {
            val m = DECIMAL.matchEntire(decimalString.trim())
                ?: throw IllegalArgumentException(
                    "ideal parts must be an exact decimal string, got \"$decimalString\" (PM-ORG-002)",
                )
            val whole = m.groupValues[1].toInt()
            val frac = m.groupValues[2].padEnd(6, '0').toInt()
            return IdealParts(whole * PPM + frac)
        }
    }
}

/** Rule: PM-ORG-002 — the sum per entrance MUST equal 100%. */
fun assertPartsSumTo100(parts: List<IdealParts>) {
    val total = parts.sumOf { it.ppmPct }
    if (total != IdealParts.WHOLE.ppmPct) {
        val whole = total / IdealParts.PPM
        val frac = (total % IdealParts.PPM).toString().padStart(6, '0')
        throw IllegalStateException(
            "ideal parts sum to $whole.$frac%, must be 100.000000% (PM-ORG-002)",
        )
    }
}
