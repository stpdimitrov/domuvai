import "server-only";
import { api, type Schemas } from "./api/client";

/** A call to `api` that may not connect: `null` when the backend is down, logged, never a thrown error. */
export async function reach<T>(call: () => Promise<T>): Promise<T | null> {
  try {
    return await call();
  } catch (cause) {
    console.error("api unreachable:", cause);
    return null;
  }
}

export type Entrances =
  | { kind: "ok"; entrances: Schemas["EntranceView"][] }
  | { kind: "down" }
  | { kind: "unlisted"; status: number; message: string };      // the API refused the list of entrances — a 403 is not a 500

/** Every registered entrance, by name — the API lists them in no fixed order, and an order must not change between two loads. */
export async function entrances(): Promise<Entrances> {
  const listed = await reach(() => api.GET("/api/registry/entrances"));
  if (!listed) return { kind: "down" };
  if (!listed.data) return { kind: "unlisted", status: listed.response.status, message: listed.error?.error ?? `HTTP ${listed.response.status}` };
  return {
    kind: "ok",
    entrances: [...listed.data].sort((a, b) => a.label.localeCompare(b.label, "bg", { numeric: true }) || a.id.localeCompare(b.id)),
  };
}

export type EntranceAt =
  | { kind: "ok"; entrance: Schemas["EntranceView"] }
  | Exclude<Entrances, { kind: "ok" }>
  | { kind: "none" }
  | { kind: "unknown"; id: string };

/** The entrance an entrance screen shows: `?entrance=<id>`, or else the first by name. */
export async function entranceAt(id: string | undefined): Promise<EntranceAt> {
  const listed = await entrances();
  if (listed.kind !== "ok") return listed;
  const entrance = id ? listed.entrances.find((e) => e.id === id) : listed.entrances[0];
  if (entrance) return { kind: "ok", entrance };
  return id ? { kind: "unknown", id } : { kind: "none" };
}

/** [each] over [items], a few at a time and in order — a firm has dozens of entrances, and `api` one pool of connections. */
export async function inTurn<T, R>(items: T[], each: (item: T) => Promise<R>, atOnce = 6): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const i = next++;
      results[i] = await each(items[i]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(atOnce, items.length) }, worker));
  return results;
}

/** Integer minor units as euros, €1.234,56 — integer arithmetic only, no floats (PM-FEE-016). */
export const eur = (minor: number) => {
  const abs = Math.abs(minor);
  const euros = ((abs - (abs % 100)) / 100).toLocaleString("de-DE");
  return `${minor < 0 ? "−" : ""}€${euros},${String(abs % 100).padStart(2, "0")}`;
};

const MONTHS = ["януари", "февруари", "март", "април", "май", "юни", "юли", "август", "септември", "октомври", "ноември", "декември"];

/** A month as the screens address it: `YYYY-MM`. This one, in Sofia (PM-SYS-004). */
export const thisPeriod = () =>
  new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Sofia", year: "numeric", month: "2-digit" }).format(new Date()).slice(0, 7);
export const isPeriod = (value: string | undefined): value is string => /^[1-9]\d{3}-(0[1-9]|1[0-2])$/.test(value ?? "");   // a four-digit year, a real month
export const shiftPeriod = (period: string, by: number) => {
  const [y, m] = period.split("-").map(Number);
  const d = new Date(Date.UTC(y, m - 1 + by, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
};
export const monthName = (period: string) => `${MONTHS[Number(period.slice(5)) - 1]} ${period.slice(0, 4)}`;
/** A real calendar day, `YYYY-MM-DD` — 2026-02-31 is not one, and neither is a parameter given twice (a list). */
export const isDay = (value: unknown): value is string => {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [y, m, d] = value.split("-").map(Number);
  const day = new Date(Date.UTC(y, m - 1, d));
  return day.getUTCFullYear() === y && day.getUTCMonth() === m - 1 && day.getUTCDate() === d;
};
/** The month's first and last day, as ISO dates. */
export const monthDays = (period: string) => {
  const [y, m] = period.split("-").map(Number);
  return { from: `${period}-01`, to: `${period}-${String(new Date(Date.UTC(y, m, 0)).getUTCDate()).padStart(2, "0")}` };
};
