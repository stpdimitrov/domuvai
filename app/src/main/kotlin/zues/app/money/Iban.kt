package zues.app.money

/**
 * A stored account number (Rule: PM-FUND-004). Normalisation only — spaces removed, upper-cased,
 * and checked for the ISO 13616 shape (two letters, two check digits, then the account body).
 *
 * The mod-97 checksum is deliberately **not** verified here: an IBAN is only ever *used* by the
 * `rail` module, which initiates payment into it (ADR-007). The checksum belongs with that use,
 * next to the partner's own validation, not at the point a record is filed.
 */
object Iban {
    private val SHAPE = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")

    fun normalize(raw: String): String {
        val compact = raw.replace(" ", "").uppercase()
        require(SHAPE.matches(compact)) { "not a well-formed IBAN: $raw" }
        return compact
    }
}
