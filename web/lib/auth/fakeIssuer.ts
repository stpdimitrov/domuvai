import { base64url } from "./seal.ts";
import type { SignIn } from "./settings.ts";

/**
 * A stand-in for the issuer, for the tests: it answers discovery, its keys and the token endpoint as `fetch` would,
 * signs real RS256 tokens with a key of its own, and checks what a real issuer checks — the client's secret, the
 * return address, that a code is spent once, and the PKCE verifier against the challenge the sign-in began with.
 * `tamper` lets a test make it misbehave in one way.
 */
export type Tamper = {
  claims?: (claims: Record<string, unknown>) => Record<string, unknown>;
  header?: Record<string, unknown>;
  signWith?: CryptoKeyPair;
  signature?: (signature: string) => string;
  tokenAnswer?: (body: Record<string, unknown>) => Record<string, unknown>;
  document?: (document: Record<string, unknown>) => Record<string, unknown>;
  down?: boolean;
  /** The token endpoint's status, whatever was asked. */
  status?: number;
  /** A refresh answered without a new refresh token, as an issuer that does not rotate them does. */
  keepRefresh?: boolean;
  /** What the issuer publishes beside (before) its signing key. */
  otherKeys?: Record<string, unknown>[];
  keys?: unknown;
};

const encode = (value: unknown) => base64url(new TextEncoder().encode(JSON.stringify(value)));
export const newKey = () =>
  crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);

export async function fakeIssuer(signIn: SignIn, clock: { now: number }) {
  const pair = await newKey();
  const jwk = { ...(await crypto.subtle.exportKey("jwk", pair.publicKey)), kid: "k1", use: "sig", alg: "RS256" };
  const codes = new Map<string, { challenge: string; nonce: string; redirectUri: string }>();
  const issuer = {
    tamper: {} as Tamper,
    /** Every request the web made of the issuer's back channel: the URL, and a form's fields. */
    asked: [] as { url: string; form: URLSearchParams | null }[],
    subject: "f3b0c7de-0000-4000-8000-000000000001",
    refreshes: 0,
    lastRefresh: "",
    publicKey: pair.publicKey,

    async sign(claims: Record<string, unknown>): Promise<string> {
      const t = issuer.tamper;
      const head = `${encode({ alg: "RS256", typ: "JWT", kid: "k1", ...t.header })}.${encode(t.claims ? t.claims(claims) : claims)}`;
      const signature = base64url(new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", (t.signWith ?? pair).privateKey, new TextEncoder().encode(head))));
      return `${head}.${t.signature ? t.signature(signature) : signature}`;
    },

    /** The person signs in at the issuer's page: what the browser is sent back to the web with. */
    signInAt(url: string): URLSearchParams {
      const asked = new URL(url);
      const q = asked.searchParams;
      const expected = { response_type: "code", client_id: signIn.clientId, redirect_uri: `${signIn.webUrl}/auth/callback`, code_challenge_method: "S256", scope: "openid" };
      for (const [name, value] of Object.entries(expected)) if (q.get(name) !== value) throw new Error(`the issuer refuses ${name}=${q.get(name)}`);
      if (asked.origin + asked.pathname !== `${signIn.issuer}/auth`) throw new Error("not the issuer's sign-in page");
      const code = `code-${codes.size + 1}`;
      codes.set(code, { challenge: q.get("code_challenge") ?? "", nonce: q.get("nonce") ?? "", redirectUri: q.get("redirect_uri") ?? "" });
      return new URLSearchParams({ code, state: q.get("state") ?? "" });
    },

    fetch: (async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = String(input);
      const form = typeof init?.body === "string" ? new URLSearchParams(init.body) : null;
      issuer.asked.push({ url, form });
      if (issuer.tamper.down) throw new TypeError("fetch failed");
      const answer = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
      if (url === `${signIn.issuer}/.well-known/openid-configuration`) {
        const document = { issuer: signIn.issuer, authorization_endpoint: `${signIn.issuer}/auth`, token_endpoint: `${signIn.issuer}/token`, jwks_uri: `${signIn.issuer}/certs` };
        return answer(issuer.tamper.document ? issuer.tamper.document(document) : document);
      }
      if (url === `${signIn.issuer}/certs`) return answer({ keys: issuer.tamper.keys !== undefined ? issuer.tamper.keys : [...(issuer.tamper.otherKeys ?? []), jwk] });
      if (issuer.tamper.status) return answer({ error: "temporarily_unavailable" }, issuer.tamper.status);
      if (url !== `${signIn.issuer}/token` || !form) return answer({ error: "not_found" }, 404);
      if (form.get("client_id") !== signIn.clientId || form.get("client_secret") !== signIn.clientSecret) return answer({ error: "unauthorized_client" }, 401);

      const tokens = async (nonce?: string) => {
        const body = {
          access_token: `access-${++issuer.refreshes}`,
          token_type: "Bearer",
          expires_in: 300,
          refresh_token: issuer.tamper.keepRefresh ? undefined : `refresh-${issuer.refreshes}`,
          refresh_expires_in: 1800,
          id_token: await issuer.sign({ iss: signIn.issuer, aud: signIn.clientId, azp: signIn.clientId, sub: issuer.subject, exp: clock.now + 300, iat: clock.now, nonce, preferred_username: "ivan" }),
        };
        if (body.refresh_token) issuer.lastRefresh = body.refresh_token;
        return answer(issuer.tamper.tokenAnswer ? issuer.tamper.tokenAnswer(body) : body);
      };
      if (form.get("grant_type") === "refresh_token") return form.get("refresh_token") === issuer.lastRefresh ? tokens() : answer({ error: "invalid_grant" }, 400);
      const begun = codes.get(form.get("code") ?? "");
      codes.delete(form.get("code") ?? "");   // a code is spent once, whatever comes of it
      const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(form.get("code_verifier") ?? ""))));
      if (form.get("grant_type") !== "authorization_code" || !begun || begun.redirectUri !== form.get("redirect_uri") || begun.challenge !== challenge) {
        return answer({ error: "invalid_grant" }, 400);
      }
      return tokens(begun.nonce);
    }) as typeof fetch,
  };
  return issuer;
}
