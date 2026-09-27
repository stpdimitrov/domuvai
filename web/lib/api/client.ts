import "server-only";
import createClient from "openapi-fetch";
import type { components, paths } from "./schema";

/** Where `api` listens. Server-side only; defaults to the local backend. */
export const API_URL = process.env.API_URL ?? "http://localhost:8080";

/**
 * The typed client for `api`, generated from its contract (ADR-011 §2.2, ADR-013): a path, a
 * parameter or a body the API does not accept is a type error, not a runtime surprise. Server-only —
 * the browser never calls `api` directly; this Next.js server is the thin BFF (ADR-011).
 */
export const api = createClient<paths>({ baseUrl: API_URL });

export type Schemas = components["schemas"];
