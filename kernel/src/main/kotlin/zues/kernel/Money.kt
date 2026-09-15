package zues.kernel

/**
 * Money is integer minor units in EUR. Rule: PM-FEE-016
 *
 * The type is the guard: a `Long` cannot hold 42.5, so "money as a float" is a
 * compile error, not a runtime check. No floats anywhere in this file.
 */
@JvmInline
value class Money private constructor(val amountMinor: Long) {

    operator fun plus(other: Money): Money = Money(amountMinor + other.amountMinor)

    /** Integer formatting — never `%.2f`, which floats and localises the separator. */
    fun format(): String {
        val sign = if (amountMinor < 0) "-" else ""
        val a = if (amountMinor < 0) -amountMinor else amountMinor
        return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')} €"
    }

    companion object {
        fun eur(amountMinor: Long): Money = Money(amountMinor)
    }
}

fun eur(amountMinor: Long): Money = Money.eur(amountMinor)

fun sumMoney(xs: List<Money>): Money = xs.fold(eur(0)) { acc, m -> acc + m }
