/**
 * The resident surface's payload budget (PM-SYS-015): what one `(resident)` route sends a phone — the compressed
 * JavaScript and CSS of its page and of every layout above it — held against the dated entry of `web/budget.json` in
 * force on the day the check runs. A product number, not a legal one: not `zues.law`'s. Pure — `scripts/budget.ts`
 * reads and compresses the build and hands the sizes in.
 */

/** From `from` (a Sofia day, `YYYY-MM-DD`) the floor is this device on this network, and a route sends at most `bytes`. */
export type Entry = { from: string; floorDevice: string; floorNetwork: string; bytes: number };

const isDay = (s: unknown) => typeof s === "string" && /^\d{4}-\d{2}-\d{2}$/.test(s) && new Date(`${s}T00:00:00Z`).toISOString().startsWith(s);

/**
 * Rule: PM-SYS-015 — the entry in force on [day]: the latest whose `from` is on or before it; one that starts after
 * [day] is never used. Throws on an entry that is not well formed — a budget nobody can read is not a budget.
 */
export function entryInForce(entries: Partial<Entry>[], day: string): Entry | null {
  for (const e of entries) {
    if (!isDay(e.from) || !Number.isSafeInteger(e.bytes) || e.bytes! <= 0 || !e.floorDevice?.trim() || !e.floorNetwork?.trim())
      throw new Error(`web/budget.json: not a well-formed entry: ${JSON.stringify(e)}`);
  }
  if (new Set(entries.map((e) => e.from)).size !== entries.length) throw new Error("web/budget.json: two entries start on the same day");
  return (entries as Entry[]).filter((e) => e.from <= day).sort((a, b) => b.from.localeCompare(a.from))[0] ?? null;
}

/**
 * The `(resident)` routes of a build, from Next's `app-build-manifest.json` pages map: each page under the group, with
 * the JS and CSS of the page and of every layout above it, the root's included — what a first visit loads. Route
 * groups and the trailing `/page` are not part of the path the browser asks for.
 */
export function residentRoutes(pages: Record<string, string[]>): { route: string; files: string[] }[] {
  return Object.keys(pages)
    .filter((key) => key.startsWith("/(resident)/") && key.endsWith("/page"))
    .sort()
    .map((key) => {
      const segments = key.split("/").slice(1, -1);
      const files = new Set(pages[key]);
      for (let i = 0; i <= segments.length; i++) for (const f of pages["/" + [...segments.slice(0, i), "layout"].join("/")] ?? []) files.add(f);
      return {
        route: "/" + segments.filter((s) => !s.startsWith("(")).join("/"),
        files: [...files].filter((f) => /\.(js|css)$/.test(f)).sort(),
      };
    });
}

/**
 * Rule: PM-SYS-015 — CI fails a build that exceeds the payload budget for the floor. A route at or under the entry in
 * force on [day] passes; one over it fails, named with its bytes and how many over. No `(resident)` route, or no entry
 * in force, fails too: nothing would be held, and the rule says something must.
 */
export function check(sizes: { route: string; bytes: number }[], entries: Partial<Entry>[], day: string): { ok: boolean; lines: string[] } {
  const lines = sizes.map((s) => `${s.route}: ${s.bytes} B`);
  if (!sizes.length) return { ok: false, lines: ["PM-SYS-015: the build has no (resident) route — nothing to hold against the budget"] };
  let entry: Entry | null;
  try {
    entry = entryInForce(entries, day);
  } catch (e) {
    return { ok: false, lines: [...lines, `PM-SYS-015: ${(e as Error).message}`] };
  }
  if (!entry) return { ok: false, lines: [...lines, `PM-SYS-015: no entry of web/budget.json is in force on ${day}`] };
  const { bytes: limit } = entry;
  const over = sizes.filter((s) => s.bytes > limit);
  return {
    ok: !over.length,
    lines: [
      `PM-SYS-015: ${limit} B per route, in force from ${entry.from} (${entry.floorDevice}, ${entry.floorNetwork})`,
      ...lines,
      ...over.map((s) => `PM-SYS-015: ${s.route} is over the budget: ${s.bytes} B, ${s.bytes - limit} B over`),
    ],
  };
}
