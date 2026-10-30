/**
 * What a party picker offers (WEB-52b). Pure: no import, so it is tested on Node's own runner. Each option is built from one
 * served row: that row's name beside the 8-character id of that row's party, and the whole id as the value the form sends.
 * Who may read the rows is the API's (Rule: PM-BOOK-006, PM-SEC-002); the web gates nothing and adds no name from anywhere else.
 */
export type PartyOption = { partyId: string; label: string };

type Row = { partyId?: string; partyName?: string; titleRole?: string };

/**
 * One option per party, however many units or roles the rows give it; only the rows of `role`, when one is asked; by name, because
 * the API lists them in no fixed order. A row with no name served offers its id alone; one with no id offers nothing. No rows are no options.
 */
// Rule: PM-BOOK-006, PM-SEC-002
export function partyOptions(rows: Row[] | undefined, role?: string): PartyOption[] {
  const parties = new Map<string, PartyOption>();
  for (const { partyId, partyName, titleRole } of rows ?? []) {
    if (!partyId || (role !== undefined && titleRole !== role)) continue;
    if (!parties.has(partyId)) parties.set(partyId, { partyId, label: partyName ? `${partyName} ${partyId.slice(0, 8)}` : partyId.slice(0, 8) });
  }
  return [...parties.values()].sort((a, b) => a.label.localeCompare(b.label, "bg"));
}

/** The login's own party first, from `/me` and so no read of the book; it never adds a name, and stands once. No party, nothing added. */
// Rule: PM-SEC-002
export function withMyself(options: PartyOption[], myself: string | undefined): PartyOption[] {
  return myself ? [{ partyId: myself, label: `Аз ${myself.slice(0, 8)}` }, ...options.filter((o) => o.partyId !== myself)] : options;
}
