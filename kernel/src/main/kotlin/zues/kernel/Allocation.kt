package zues.kernel

/**
 * Split a pot into shares by weight so the parts sum EXACTLY to the total.
 * Largest remainder, computed on integers only — never lose or invent a cent.
 * Rule: PM-FEE-004, PM-FUND-003
 */
fun allocateByWeight(total: Money, weights: List<Long>): List<Money> {
    val sum = weights.sum()
    if (sum <= 0L) return weights.map { eur(0) }

    val floors = weights.map { total.amountMinor * it / sum }
    var remainder = total.amountMinor - floors.sum()

    // Hand the remaining minor units to the largest integer remainders first,
    // ties broken by position — deterministic, and float-free.
    val order = weights.indices.sortedWith(
        compareByDescending<Int> { i -> (total.amountMinor * weights[i]) % sum }.thenBy { it },
    )

    val out = floors.toMutableList()
    for (i in order) {
        if (remainder <= 0L) break
        out[i] = out[i] + 1L
        remainder -= 1L
    }
    return out.map { eur(it) }
}
