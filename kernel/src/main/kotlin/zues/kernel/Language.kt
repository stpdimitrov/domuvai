package zues.kernel

/**
 * Language of record. Rule: PM-SYS-003 — Bulgarian is the primary UI and
 * document language; English MAY be offered, but statutory documents MUST be
 * produced in Bulgarian.
 */
enum class Language { BG, EN }

/** Bulgarian is the default UI language; English is optional. Rule: PM-SYS-003 */
val DEFAULT_UI_LANGUAGE: Language = Language.BG

/** Statutory documents exist in Bulgarian only. Rule: PM-SYS-003 */
val STATUTORY_LANGUAGE: Language = Language.BG

/** Guard the language of a statutory document. Rule: PM-SYS-003 */
fun assertStatutoryLanguage(lang: Language) {
    if (lang != STATUTORY_LANGUAGE) {
        throw IllegalArgumentException(
            "statutory documents must be produced in $STATUTORY_LANGUAGE, not $lang (PM-SYS-003)",
        )
    }
}
