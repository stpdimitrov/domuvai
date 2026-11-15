/**
 * A party as a row gives it: the name the API served beside the id (WEB-52), the 8-character id kept beside it and the
 * whole id on hover; the id alone when no name was served — to an owner and to every reader the book is closed to
 * (Rule: PM-BOOK-006, PM-SEC-002). The name is text, never markup. For the ids no read names (a unit, an actor) use `Party`.
 */
// Rule: PM-BOOK-006, PM-SEC-002
export default function PartyName({ id, name }: { id: string | undefined; name?: string }) {
  if (!id) return <>—</>;
  const short = <span className="mono" title={id}>{id.slice(0, 8)}</span>;
  return name ? <span title={id}>{name} <span style={{ color: "#6B6F6C" }}>{short}</span></span> : short;
}
