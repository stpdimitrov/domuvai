/**
 * A decision the general assembly took on paper (WEB-54): recorded from its protocol, shown as the API answers it, and
 * offered to the forms that cite a decision. Pure: no import but the refusal's words, so it is tested on Node's own
 * runner. The web decides nothing here: what is missing from a record, who may record one and who is told the
 * custodian are the API's (Rule: PM-SEC-001, PM-SEC-002, enforced there).
 */
import { undoneBy } from "./refusal.ts";

export type PaperDecision = {
  id: string; decisionText: string; protocolDate: string; protocolRef: string; archiveLocation: string; custodian?: string;
};

/** The five fields of a record, as the form names them and the API takes them. */
export const FIELDS = ["decisionText", "protocolDate", "protocolRef", "archiveLocation", "custodian"] as const;
export type PaperDecisionInput = Record<(typeof FIELDS)[number], string>;

/** What an option shows of the decision's text: a long one would stretch the list past the screen. */
const SHOWN = 80;

export const CUSTODIAN_NOT_SHOWN = "не се показва на вашата роля";

/** The read named as a refusal names it, in the accusative, definite: both the screen and the forms read it. */
export const PAPER_DECISIONS = "решенията от хартиени протоколи";

/**
 * The body the API takes: only what was typed. A field left empty is not sent, so what is missing is the API's to
 * refuse in its words — a date sent empty would be refused as a malformed request, not as a date that is required.
 */
// Rule: PM-DOC-007
export const recordOf = (input: PaperDecisionInput): Partial<PaperDecisionInput> =>
  Object.fromEntries(Object.entries(input).filter(([, value]) => value !== ""));

export const recordRefused = (answer: Parameters<typeof undoneBy>[0]): string => undoneBy(answer, "впишете решение от хартиен протокол");

/** Where the paper original is kept and who keeps it, on every record shown. A custodian the API did not name is said so, never left blank. */
// Rule: PM-GA-022, PM-DOC-007, PM-SEC-002
export const originalOf = (d: Pick<PaperDecision, "archiveLocation" | "custodian">) =>
  `Оригиналът на хартия е в: ${d.archiveLocation} · пази го: ${d.custodian || CUSTODIAN_NOT_SHOWN}`;

/** One line, cut to what an option can show, on a whole character. */
const shortened = (text: string) => {
  const line = [...text.replace(/\s+/g, " ").trim()];
  return line.length > SHOWN ? `${line.slice(0, SHOWN).join("").trimEnd()}…` : line.join("");
};

/**
 * A recorded paper decision as the forms offer it, by the id the API checks a citation against (`GaDecisions.passed`).
 * It has no status or outcome: being recorded is what it has to show.
 */
// Rule: PM-FEE-012, PM-FUND-007
export const paperOffer = (d: PaperDecision) => ({
  id: d.id,
  label: `ОС на хартия ${d.protocolDate}, ${d.protocolRef}: ${shortened(d.decisionText)}`,
});
