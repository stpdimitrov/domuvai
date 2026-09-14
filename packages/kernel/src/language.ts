/**
 * Language of record. Rule: PM-SYS-003 — Bulgarian is the primary UI and
 * document language; English MAY be offered, but statutory documents MUST be
 * produced in Bulgarian.
 */
export type Language = 'bg' | 'en';

/** Bulgarian is the default UI language; English is optional. Rule: PM-SYS-003 */
export const DEFAULT_UI_LANGUAGE: Language = 'bg';

/** Statutory documents exist in Bulgarian only. Rule: PM-SYS-003 */
export const STATUTORY_LANGUAGE: Language = 'bg';

/** Guard the language of a statutory document. Rule: PM-SYS-003 */
export const assertStatutoryLanguage = (lang: Language): void => {
  if (lang !== STATUTORY_LANGUAGE) {
    throw new RangeError(
      `statutory documents must be produced in ${STATUTORY_LANGUAGE}, not ${lang} (PM-SYS-003)`,
    );
  }
};
