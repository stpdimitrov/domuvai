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

export type EntranceAt =
  | { kind: "ok"; entrance: Schemas["EntranceView"] }
  | { kind: "down" }
  | { kind: "unlisted"; message: string }       // the API refused the list of entrances
  | { kind: "none" }
  | { kind: "unknown"; id: string };

/**
 * The entrance an entrance screen shows: `?entrance=<id>`, or else the first by name — the API lists them
 * in no fixed order, and the default must not change between two loads.
 */
export async function entranceAt(id: string | undefined): Promise<EntranceAt> {
  const listed = await reach(() => api.GET("/api/registry/entrances"));
  if (!listed) return { kind: "down" };
  if (!listed.data) return { kind: "unlisted", message: listed.error?.error ?? `HTTP ${listed.response.status}` };
  const all = [...listed.data].sort((a, b) => a.label.localeCompare(b.label, "bg", { numeric: true }) || a.id.localeCompare(b.id));
  const entrance = id ? all.find((e) => e.id === id) : all[0];
  if (entrance) return { kind: "ok", entrance };
  return id ? { kind: "unknown", id } : { kind: "none" };
}

/** Integer minor units as euros, €1.234,56 — integer arithmetic only, no floats (PM-FEE-016). */
export const eur = (minor: number) => {
  const abs = Math.abs(minor);
  const euros = ((abs - (abs % 100)) / 100).toLocaleString("de-DE");
  return `${minor < 0 ? "−" : ""}€${euros},${String(abs % 100).padStart(2, "0")}`;
};
