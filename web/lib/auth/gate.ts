import type { Session } from "./oidc.ts";
import { open, seal } from "./seal.ts";
import { readSignIn, type Env, type SignIn } from "./settings.ts";

/**
 * Who this server serves the console to (Rule: PM-DEBT-011; ADR-011, AUTH-03). The console names debtors and what
 * they owe, so it is served to a person who is signed in and to nobody else. Closed unless told otherwise — the api's
 * rule, in the api's words: with sign-in not configured the console is not served at all, unless `DOMUVAI_AUTH=off`
 * says in so many words that this server runs without sign-in, or it is `next dev`, which `npm run dev` binds to this
 * machine alone. Half-configured, or "off" beside sign-in settings, is closed. Read from the running server's
 * environment, never baked into the build.
 */
export type Mode = { kind: "signIn"; signIn: SignIn } | { kind: "open" } | { kind: "closed"; why: string };

const SETTINGS = ["DOMUVAI_AUTH_ISSUER", "DOMUVAI_AUTH_CLIENT_ID", "DOMUVAI_AUTH_CLIENT_SECRET", "DOMUVAI_SESSION_SECRET", "DOMUVAI_WEB_URL"];

export function mode(env: Env): Mode {
  const any = SETTINGS.some((name) => env[name]?.trim());
  const auth = env.DOMUVAI_AUTH?.trim();
  if (auth) {
    if (auth !== "off") return { kind: "closed", why: "DOMUVAI_AUTH is set, and not to off" };
    return any ? { kind: "closed", why: "DOMUVAI_AUTH=off beside sign-in settings: one or the other" } : { kind: "open" };
  }
  if (!any) return env.NODE_ENV === "development" ? { kind: "open" } : { kind: "closed", why: "sign-in is not configured, and DOMUVAI_AUTH=off is not set" };
  const settings = readSignIn(env);
  return "signIn" in settings ? { kind: "signIn", signIn: settings.signIn } : { kind: "closed", why: settings.refused };
}

/** The sign-in routes: the only console paths a person who is not signed in is served. */
export const LOGIN = "/auth/login";
export const LOGOUT = "/auth/logout";
const ROUTES = new Set([LOGIN, "/auth/callback", LOGOUT]);
export const isSignInRoute = (path: string) => ROUTES.has(path);

export type Ask = { path: string; search: string; /** a page asked for by a person, not by a script on a page */ navigation: boolean };
export type Verdict = { kind: "pass" } | { kind: "notFound" } | { kind: "signIn"; to: string } | { kind: "unauthorized" };

/**
 * What a request gets. The landing is public. Where the console is closed every other path is the 404 a missing page
 * gets. Where people sign in, every other path but the sign-in routes needs a session: a page asked for by a person
 * who has none sends them to sign in and back; anything else asked without one is a 401. A path added later is
 * behind the same rule, with no list of console paths to keep.
 */
export function decide(m: Mode, ask: Ask, signedIn: boolean): Verdict {
  if (ask.path === "/") return { kind: "pass" };
  if (m.kind === "closed") return { kind: "notFound" };
  if (m.kind === "open" || isSignInRoute(ask.path) || signedIn) return { kind: "pass" };
  return ask.navigation ? { kind: "signIn", to: `${LOGIN}?return=${encodeURIComponent(ask.path + ask.search)}` } : { kind: "unauthorized" };
}

// ── the two cookies ──────────────────────────────────────────────────────────────────────────────────────────────

const SESSION = "session";
/** A cookie holds about 4096 bytes, name and attributes included; a session sealed is longer than one may be. */
const PIECE = 3500;
const PIECES = 4;

/**
 * The cookies' names and attributes. httpOnly, so no script on a page reads them; SameSite=Lax, so they come back
 * with the issuer's redirect and with nothing another site submits; `Secure` and the `__Host-` prefix — this host
 * only, set over https only, no other site of the domain can plant one — wherever the web is https, which is
 * everywhere but this machine.
 */
export function cookies(signIn: SignIn) {
  const secure = signIn.webUrl.startsWith("https://");
  const prefix = secure ? "__Host-" : "";
  return {
    session: `${prefix}domuvai-session`,
    pending: `${prefix}domuvai-signin`,
    options: (maxAge: number) => ({ httpOnly: true, secure, sameSite: "lax" as const, path: "/", maxAge: Math.max(0, Math.floor(maxAge)) }),
  };
}

type Put = (name: string, value: string, options: ReturnType<ReturnType<typeof cookies>["options"]>) => void;

/** The session, sealed and handed to the browser — or, with no session, taken back. False if it is too long to hand over. */
export async function putSession(signIn: SignIn, session: Session | null, now: number, put: Put): Promise<boolean> {
  const { session: name, options } = cookies(signIn);
  const sealed = session ? await seal(signIn.sessionSecret, SESSION, session, session.until) : "";
  const fits = sealed.length <= PIECE * PIECES;
  for (let i = 0; i < PIECES; i++) {
    const piece = fits ? sealed.slice(i * PIECE, (i + 1) * PIECE) : "";
    put(`${name}.${i}`, piece, options(piece && session ? session.until - now : 0));
  }
  return fits;
}

/** The session this browser holds, or null: none, not sealed by this server, altered, or over. */
export async function readSession(signIn: SignIn, get: (name: string) => string | undefined, now: number): Promise<Session | null> {
  const { session: name } = cookies(signIn);
  let sealed = "";
  for (let i = 0; i < PIECES; i++) {
    const piece = get(`${name}.${i}`);
    if (!piece) break;
    sealed += piece;
  }
  return open<Session>(signIn.sessionSecret, SESSION, sealed, now);
}
