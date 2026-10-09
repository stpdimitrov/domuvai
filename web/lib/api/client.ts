import "server-only";
import createClient from "openapi-fetch";
import { currentSession } from "@/lib/auth/server";
import type { components, paths } from "./schema";

/** Where `api` listens. Server-side only; defaults to the local backend. */
export const API_URL = process.env.API_URL ?? "http://localhost:8080";

/**
 * The typed client for `api`, generated from its contract (ADR-011 §2.2, ADR-013): a path, a
 * parameter or a body the API does not accept is a type error, not a runtime surprise. Server-only —
 * the browser never calls `api` directly; this Next.js server is the thin BFF (ADR-011).
 */
/** How long one call to `api` may take before a screen reports the backend as not answering — a hung call must not hold a page. */
const TIMEOUT_MS = 10_000;

/**
 * Every call carries the signed-in person's access token as a bearer (ADR-011, AUTH-03): read from the session
 * cookie on this request, here on the server — it is never given to a page's script. With nobody signed in the call
 * carries none, and an `api` that asks for one refuses it.
 */
export const api = createClient<paths>({
  baseUrl: API_URL,
  fetch: async (request) => {
    const session = await currentSession();
    if (session) request.headers.set("authorization", `Bearer ${session.accessToken}`);
    return fetch(request, { signal: AbortSignal.timeout(TIMEOUT_MS), cache: "no-store" });
  },
});

export type Schemas = components["schemas"];
