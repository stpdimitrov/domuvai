import { checkIdToken, type Jwk } from "./idToken.ts";
import { base64url, open, random, same, seal } from "./seal.ts";
import { CALLBACK_PATH, returnPath, type SignIn } from "./settings.ts";

/**
 * The web as an OpenID Connect relying party (ADR-011, AUTH-03): the authorization-code flow with PKCE (S256), by
 * hand — Web Crypto and fetch, no package. What a sign-in begins with never leaves this module unsealed, and a
 * sign-in is finished only here, so the wiring around it cannot skip a check.
 */
export type Session = {
  /** The bearer this server passes to the api. It never reaches a browser unsealed. */
  accessToken: string;
  refreshToken: string | null;
  /** When the access token stops being good, and when the whole session does — epoch seconds. */
  accessUntil: number;
  until: number;
  subject: string;
  /** What the issuer calls the person, for display only. Who they are to the api is the api's answer. */
  name: string | null;
};
type Pending = { state: string; nonce: string; verifier: string; returnTo: string };
type Endpoints = { authorization: string; token: string; jwks: string; endSession: string | null };

export type Finished = { ok: true; session: Session; returnTo: string } | { ok: false; why: string };
export type Renewed = { kind: "renewed"; session: Session } | { kind: "refused" } | { kind: "unreachable" };
export type Deps = { fetch: typeof fetch; now: () => number };

/** How long a person has to come back from the issuer's sign-in page, in seconds. */
export const PENDING_SECONDS = 600;
const PENDING = "sign-in begun";
const TIMEOUT_MS = 10_000;
/** The issuer's keys are read again for a key not seen before, but no more often than this — seconds. */
const KEYS_EVERY = 30;
/** And they are read again after this long whatever they hold, so a key the issuer withdrew stops being believed. */
const KEYS_FOR = 3600;

const text = (value: unknown): string | null => (typeof value === "string" && value ? value : null);
const seconds = (value: unknown): number | null => (typeof value === "number" && Number.isFinite(value) && value > 0 ? value : null);

export function relyingParty(signIn: SignIn, deps: Deps = { fetch: (...args) => fetch(...args), now: () => Math.floor(Date.now() / 1000) }) {
  const redirectUri = signIn.webUrl + CALLBACK_PATH;
  const issuerOrigin = new URL(signIn.issuer).origin;
  let endpoints: Promise<Endpoints> | null = null;
  let keys: { read: number; keys: Jwk[] } | null = null;

  const get = async (url: string): Promise<Record<string, unknown>> => {
    const response = await deps.fetch(url, { headers: { accept: "application/json" }, redirect: "error", signal: AbortSignal.timeout(TIMEOUT_MS) });
    if (!response.ok) throw new Error(`${new URL(url).pathname} answered ${response.status}`);
    return (await response.json()) as Record<string, unknown>;
  };

  /** The issuer's endpoints, from its own document: it must name this issuer, and every endpoint must be on its origin. */
  const discover = (): Promise<Endpoints> => {
    endpoints ??= (async () => {
      const document = await get(`${signIn.issuer}/.well-known/openid-configuration`);
      if (document.issuer !== signIn.issuer) throw new Error("the issuer's document names another issuer");
      const at = (name: string, required = true): string | null => {
        const value = text(document[name]);
        if (!value) {
          if (required) throw new Error(`the issuer's document has no ${name}`);
          return null;
        }
        if (new URL(value).origin !== issuerOrigin) throw new Error(`the issuer's ${name} is on another origin`);
        return value;
      };
      return { authorization: at("authorization_endpoint")!, token: at("token_endpoint")!, jwks: at("jwks_uri")!, endSession: at("end_session_endpoint", false) };
    })();
    endpoints.catch(() => (endpoints = null));   // a failure is not remembered: the next sign-in asks again
    return endpoints;
  };

  const keysFor = async (kid: string | undefined): Promise<Jwk[]> => {
    const known = keys?.keys.some((k) => kid !== undefined && k.kid === kid);
    const age = keys ? deps.now() - keys.read : 0;
    if (!keys || age >= KEYS_FOR || (!known && age >= KEYS_EVERY)) {
      const set = await get((await discover()).jwks);
      if (!Array.isArray(set.keys)) throw new Error("the issuer's keys are not a key set");   // not remembered: the next sign-in asks again
      keys = { read: deps.now(), keys: set.keys as Jwk[] };
    }
    return keys.keys;
  };

  /** The token endpoint, asked as this client. The answer's body, or the issuer's error code — never a token in an error. */
  const token = async (form: Record<string, string>): Promise<{ status: number; body: Record<string, unknown> }> => {
    const response = await deps.fetch((await discover()).token, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" },
      body: new URLSearchParams({ ...form, client_id: signIn.clientId, client_secret: signIn.clientSecret }).toString(),
      redirect: "error",
      cache: "no-store",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    let body: Record<string, unknown> = {};
    try {
      const parsed: unknown = await response.json();
      if (typeof parsed === "object" && parsed !== null) body = parsed as Record<string, unknown>;
    } catch {
      // no body is an answer too
    }
    return { status: response.status, body };
  };
  const errorCode = (body: Record<string, unknown>) => (typeof body.error === "string" && /^[a-z_]{1,40}$/.test(body.error) ? body.error : "no reason given");

  /** A session out of a token answer, or null if the answer is not one: a bearer access token with a lifetime. */
  const session = (body: Record<string, unknown>, who: { subject: string; name: string | null }, refreshBefore: string | null): Session | null => {
    const accessToken = text(body.access_token);
    const lasts = seconds(body.expires_in);
    if (!accessToken || !lasts || text(body.token_type)?.toLowerCase() !== "bearer") return null;
    const now = deps.now();
    const refreshToken = text(body.refresh_token) ?? refreshBefore;
    return { accessToken, refreshToken, accessUntil: now + lasts, until: now + (refreshToken ? (seconds(body.refresh_expires_in) ?? lasts) : lasts), ...who };
  };

  return {
    /**
     * A sign-in begun: the issuer's address to send the browser to, and the sealed value the browser keeps until it
     * comes back. State, nonce and PKCE verifier are fresh for every attempt and are in that sealed value only.
     * Throws when the issuer cannot be reached or its document is refused — nobody is sent anywhere.
     */
    async begin(wanted: string | null | undefined): Promise<{ url: string; pending: string }> {
      const pending: Pending = { state: random(), nonce: random(), verifier: random(), returnTo: returnPath(wanted) };
      const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(pending.verifier))));
      const url = new URL((await discover()).authorization);
      url.search = new URLSearchParams({
        response_type: "code",
        client_id: signIn.clientId,
        redirect_uri: redirectUri,
        scope: "openid",
        state: pending.state,
        nonce: pending.nonce,
        code_challenge: challenge,
        code_challenge_method: "S256",
      }).toString();
      return { url: url.toString(), pending: await seal(signIn.sessionSecret, PENDING, pending, deps.now() + PENDING_SECONDS) };
    },

    /**
     * A sign-in finished, or refused. [query] is what the issuer sent the browser back with; [pending] is what that
     * browser kept from `begin`. Nothing is asked of the issuer until the state returned is the state sealed.
     */
    async finish(query: URLSearchParams, pending: string | null | undefined): Promise<Finished> {
      const refused = (why: string): Finished => ({ ok: false, why });
      const begun = await open<Pending>(signIn.sessionSecret, PENDING, pending, deps.now());
      if (!begun) return refused("no sign-in was begun in this browser, or it took too long");
      if (!same(query.get("state") ?? "", begun.state)) return refused("the answer is not to the sign-in this browser began");
      if (query.has("error")) return refused("the issuer refused the sign-in");
      const code = query.get("code");
      if (!code) return refused("the issuer sent no code");

      let answer: Awaited<ReturnType<typeof token>>;
      try {
        answer = await token({ grant_type: "authorization_code", code, redirect_uri: redirectUri, code_verifier: begun.verifier });
      } catch {
        return refused("the issuer could not be reached");
      }
      if (answer.status !== 200) return refused(`the issuer refused the code: ${errorCode(answer.body)}`);

      let checked: Awaited<ReturnType<typeof checkIdToken>>;
      try {
        checked = await checkIdToken(answer.body.id_token, { issuer: signIn.issuer, clientId: signIn.clientId, nonce: begun.nonce, now: deps.now() }, keysFor);
      } catch {
        return refused("the issuer's keys could not be read");
      }
      if (!checked.ok) return refused(checked.why);
      const name = text(checked.claims.preferred_username) ?? text(checked.claims.name);
      const signedIn = session(answer.body, { subject: checked.claims.sub, name }, null);
      return signedIn ? { ok: true, session: signedIn, returnTo: returnPath(begun.returnTo) } : refused("the issuer's answer carries no bearer token");
    },

    /**
     * The session with a fresh access token. Refused by the issuer is signed out: the access token it had is never
     * kept in use. An issuer that does not answer, or says to come back later, is "unreachable" — the caller's to
     * decide, and never a reason to go on with the old access token past its time. The refresh token is the new one
     * when the issuer gives one, the same one when it does not.
     */
    async renew(current: Session): Promise<Renewed> {
      if (!current.refreshToken) return { kind: "refused" };
      let answer: Awaited<ReturnType<typeof token>>;
      try {
        answer = await token({ grant_type: "refresh_token", refresh_token: current.refreshToken });
      } catch {
        return { kind: "unreachable" };
      }
      if (answer.status >= 500 || answer.status === 429 || answer.status === 408) return { kind: "unreachable" };
      const renewed = answer.status === 200 ? session(answer.body, { subject: current.subject, name: current.name }, current.refreshToken) : null;
      return renewed ? { kind: "renewed", session: renewed } : { kind: "refused" };
    },
  };
}

export type RelyingParty = ReturnType<typeof relyingParty>;
