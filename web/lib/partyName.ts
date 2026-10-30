/**
 * A party's name beside its id (WEB-52). Pure: no import, so it is tested on Node's own runner. Whether a name is served is
 * the API's (Rule: PM-BOOK-006, PM-SEC-002 — the manager, the board and the controller get names, anybody else ids only):
 * the web gates nothing and adds no name from anywhere else. A row's `names` holds only that row's ids; the name for an id
 * is read from that entry alone, so a name never stands beside an id it is not for.
 */

/** The name a row carries for `id`: its own entry in the row's `names`, or nothing when none was served. */
// Rule: PM-BOOK-006, PM-SEC-002
export function nameFor(id: string | undefined, names: Record<string, string> | undefined): string | undefined {
  return id !== undefined && names !== undefined && Object.hasOwn(names, id) ? names[id] : undefined;
}
