import assert from "node:assert/strict";
import { test } from "node:test";
import { fakeIssuer, newKey } from "./fakeIssuer.ts";
import { PENDING_SECONDS, relyingParty } from "./oidc.ts";
import { base64url, open, same, seal } from "./seal.ts";
import { HOME, readSignIn, returnPath, type SignIn } from "./settings.ts";

const ENV = {
  DOMUVAI_AUTH_ISSUER: "https://id.example.test/realms/domuvai",
  DOMUVAI_AUTH_CLIENT_ID: "domuvai-web",
  DOMUVAI_AUTH_CLIENT_SECRET: "a-client-secret-made-for-this-test",
  DOMUVAI_SESSION_SECRET: "a-session-secret-of-thirty-two-chars-or-more",
  DOMUVAI_WEB_URL: "https://domuvai.example.test",
};
const SIGN_IN = (readSignIn(ENV) as { signIn: SignIn }).signIn;

/** A web and an issuer on one clock, and the browser's part between them. */
async function world() {
  const clock = { now: 1_800_000_000 };
  const issuer = await fakeIssuer(SIGN_IN, clock);
  const web = relyingParty(SIGN_IN, { fetch: issuer.fetch, now: () => clock.now });
  return { clock, issuer, web };
}
const tokenCalls = (issuer: { asked: { url: string }[] }) => issuer.asked.filter((a) => a.url.endsWith("/token")).length;
async function refusal(finishing: Promise<{ ok: boolean; why?: string; answered?: boolean }>, why: RegExp) {
  const finished = await finishing;
  assert.equal(finished.ok, false);
  assert.match(finished.why ?? "", why);
  // a sign-in is spent by an answer to it, and by nothing else: another site's request to the callback cancels nothing
  assert.equal(finished.answered, !/no sign-in was begun|not to the sign-in/.test(finished.why ?? ""), finished.why);
}

// ── settings ─────────────────────────────────────────────────────────────────────────────────────────────────────

test("sign-in is configured by all five settings and by nothing less", () => {
  assert.deepEqual(readSignIn(ENV), {
    signIn: { issuer: ENV.DOMUVAI_AUTH_ISSUER, clientId: "domuvai-web", clientSecret: ENV.DOMUVAI_AUTH_CLIENT_SECRET, sessionSecret: ENV.DOMUVAI_SESSION_SECRET, webUrl: "https://domuvai.example.test" },
  });
  for (const name of Object.keys(ENV)) {
    for (const gone of [undefined, "", "   "]) {
      const settings = readSignIn({ ...ENV, [name]: gone });
      assert.ok("refused" in settings && settings.refused.includes(name), `${name}=${JSON.stringify(gone)}`);
    }
  }
  assert.ok("refused" in readSignIn({}));
});

test("the issuer and the web's own address are https, http for this machine only, and nothing but an address", () => {
  const refused = (change: Record<string, string>) => "refused" in readSignIn({ ...ENV, ...change });
  for (const issuer of ["http://id.example.test/realms/domuvai", "ftp://id.example.test/r", "id.example.test", "https://id.example.test/realms/domuvai/",
    "https://u:p@id.example.test/r", "https://id.example.test/r?x=1", "https://id.example.test/r#x", "http://localhost.example.test/r"]) {
    assert.ok(refused({ DOMUVAI_AUTH_ISSUER: issuer }), issuer);
  }
  for (const web of ["http://domuvai.example.test", "https://domuvai.example.test/console", "https://domuvai.example.test/?a=1", "//domuvai.example.test", "javascript:alert(1)"]) {
    assert.ok(refused({ DOMUVAI_WEB_URL: web }), web);
  }
  assert.ok(refused({ DOMUVAI_SESSION_SECRET: "x".repeat(31) }));
  const local = readSignIn({ ...ENV, DOMUVAI_AUTH_ISSUER: "http://localhost:8180/realms/domuvai", DOMUVAI_WEB_URL: "http://127.0.0.1:3000/" });
  assert.ok("signIn" in local && local.signIn.webUrl === "http://127.0.0.1:3000");
});

test("after sign-in a person goes to a path on this site, never to another site and never back into sign-in", () => {
  for (const kept of ["/portfolio", "/entrance/charges?entrance=1&period=2026-10", "/debts", "/a/b/c", "/auth-notes"]) assert.equal(returnPath(kept), kept);
  for (const elsewhere of [undefined, null, "", "portfolio", "//evil.example", "/\\evil.example", "/\\/evil.example", "\\/evil.example", "https://evil.example/",
    "https:evil.example", "/ok\\..\\x", "/a\nb", "/a\tb", "/a\u0000", "javascript:alert(1)", " /portfolio", "/auth/login", "/auth/callback?code=1", "/auth", "/x/../auth/logout", "/%61uth/../auth/login/..",
    "/.//evil.example", "/x/..//evil.example/a", "/%2e//evil.example", "/..//evil.example?x=1", "/./\\evil.example", "/a//b", "/%2f/evil.example", "/x/%2e%2e//evil.example",
    "/%5cevil.example", "/a/%2F%2Fb", "/%61uth/login", "/Auth/login", "/AUTH/callback", "/%41uth", "/%zz"]) {
    assert.equal(returnPath(elsewhere), HOME, JSON.stringify(elsewhere));
  }
  assert.equal(returnPath("/debts#frag"), "/debts");
  assert.equal(returnPath("/x/../debts"), "/debts");
  for (const input of ["/.//evil.example", "/debts", "//evil.example", "/x/..//e", "/%2e%2e//e", "/a/./b"]) assert.match(returnPath(input), /^\/(?![/\\])/, input);
});

// ── the seal ─────────────────────────────────────────────────────────────────────────────────────────────────────

test("a sealed value opens only as it was sealed: this secret, this purpose, unaltered, in time", async () => {
  const secret = ENV.DOMUVAI_SESSION_SECRET;
  const sealed = await seal(secret, "session", { token: "the-bearer" }, 1000);
  assert.deepEqual(await open(secret, "session", sealed, 999), { token: "the-bearer" });
  assert.ok(!sealed.includes("bearer") && !atob(sealed.replace(/-/g, "+").replace(/_/g, "/")).includes("the-bearer"));
  assert.notEqual(sealed, await seal(secret, "session", { token: "the-bearer" }, 1000));          // never the same text twice

  assert.equal(await open(secret, "session", sealed, 1000), null);                               // past its time
  assert.equal(await open(secret, "sign-in begun", sealed, 999), null);                          // another purpose
  assert.equal(await open(secret + "x", "session", sealed, 999), null);                          // another secret
  for (let i = 0; i < sealed.length; i += 7) {                                                   // altered anywhere
    const altered = sealed.slice(0, i) + (sealed[i] === "A" ? "B" : "A") + sealed.slice(i + 1);
    assert.equal(await open(secret, "session", altered, 999), null, `altered at ${i}`);
  }
  for (const last of "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_") {     // the last character too: one spelling only
    if (last !== sealed.at(-1)) assert.equal(await open(secret, "session", sealed.slice(0, -1) + last, 999), null, `ending in ${last}`);
  }
  for (const not of [undefined, null, "", "x", sealed.slice(0, 20), sealed.slice(4), sealed + "AAAA", "not base64 at all!", "{}"]) {
    assert.equal(await open(secret, "session", not, 999), null);
  }
  // sealed by someone who knows the format but not the secret
  const forged = base64url(new TextEncoder().encode("0123456789ab" + JSON.stringify({ value: { token: "x" }, until: 9e12 })));
  assert.equal(await open(secret, "session", forged, 999), null);
});

test("two texts are the same only when they are, and an empty one equals nothing", () => {
  assert.ok(same("abc", "abc"));
  for (const [a, b] of [["abc", "abd"], ["abc", "ab"], ["", ""], ["abc", ""], ["", "abc"], ["abc", "abcabc"]]) assert.ok(!same(a, b), `${a}|${b}`);
});

// ── a sign-in, begun and finished ────────────────────────────────────────────────────────────────────────────────

test("a person who signs in at the issuer is signed in here, and goes where they were going", async () => {
  const { issuer, web, clock } = await world();
  const { url, pending } = await web.begin("/debts?entrance=7");
  const finished = await web.finish(issuer.signInAt(url), pending);
  assert.deepEqual(finished, {
    ok: true,
    returnTo: "/debts?entrance=7",
    session: { accessToken: "access-1", refreshToken: "refresh-1", accessUntil: clock.now + 300, until: clock.now + 1800, subject: issuer.subject, name: "ivan" },
  });
});

test("what a sign-in begins with is fresh each time, sealed, and never in the address but as the issuer needs it", async () => {
  const { web } = await world();
  const [one, two] = [await web.begin("/debts"), await web.begin("/debts")];
  const q = (url: string) => new URL(url).searchParams;
  for (const name of ["state", "nonce", "code_challenge"]) {
    assert.match(q(one.url).get(name) ?? "", /^[A-Za-z0-9_-]{43}$/, name);
    assert.notEqual(q(one.url).get(name), q(two.url).get(name), name);
  }
  assert.equal(q(one.url).get("redirect_uri"), "https://domuvai.example.test/auth/callback");
  assert.deepEqual([...q(one.url).keys()].sort(), ["client_id", "code_challenge", "code_challenge_method", "nonce", "redirect_uri", "response_type", "scope", "state"]);
  assert.ok(!one.url.includes(SIGN_IN.clientSecret) && !one.url.includes(SIGN_IN.sessionSecret));

  const begun = await open<{ state: string; nonce: string; verifier: string }>(SIGN_IN.sessionSecret, "sign-in begun", one.pending, 1_800_000_000);
  assert.ok(begun);
  assert.equal(begun.state, q(one.url).get("state"));
  assert.equal(begun.nonce, q(one.url).get("nonce"));
  const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(begun.verifier))));
  assert.equal(challenge, q(one.url).get("code_challenge"));                     // S256 of the verifier, not the verifier
  assert.ok(!one.url.includes(begun.verifier) && !one.pending.includes(begun.verifier) && !one.pending.includes(begun.state));
});

test("an answer whose state is not this browser's is refused before the issuer is asked anything", async () => {
  const { issuer, web } = await world();
  const mine = await web.begin("/debts");
  const theirs = await web.begin("/debts");
  const back = issuer.signInAt(mine.url);
  const with_ = (change: (q: URLSearchParams) => void) => { const q = new URLSearchParams(back); change(q); return q; };

  await refusal(web.finish(back, theirs.pending), /not to the sign-in this browser began/);            // another browser's sign-in
  await refusal(web.finish(with_((q) => q.set("state", "x".repeat(43))), mine.pending), /not to the sign-in/);
  await refusal(web.finish(with_((q) => q.delete("state")), mine.pending), /not to the sign-in/);
  await refusal(web.finish(with_((q) => q.set("state", "")), mine.pending), /not to the sign-in/);
  await refusal(web.finish(back, undefined), /no sign-in was begun/);
  await refusal(web.finish(back, "made-up"), /no sign-in was begun/);
  assert.equal(tokenCalls(issuer), 0);

  assert.equal((await web.finish(back, mine.pending)).ok, true);                                       // and the code was not spent on any of them
});

test("a sign-in not finished in time is refused", async () => {
  const { issuer, web, clock } = await world();
  const { url, pending } = await web.begin("/debts");
  const back = issuer.signInAt(url);
  clock.now += PENDING_SECONDS;
  await refusal(web.finish(back, pending), /no sign-in was begun in this browser, or it took too long/);
  assert.equal(tokenCalls(issuer), 0);
});

test("the PKCE verifier goes with the code, and an issuer that checks it is satisfied only by this browser's", async () => {
  const { issuer, web } = await world();
  const mine = await web.begin("/debts");
  assert.equal((await web.finish(issuer.signInAt(mine.url), mine.pending)).ok, true);
  const sent = issuer.asked.find((a) => a.url.endsWith("/token"))!.form!;
  const begun = (await open<{ verifier: string }>(SIGN_IN.sessionSecret, "sign-in begun", mine.pending, 1_800_000_000))!;
  assert.equal(sent.get("code_verifier"), begun.verifier);
  assert.equal(sent.get("redirect_uri"), "https://domuvai.example.test/auth/callback");
  assert.equal(sent.get("grant_type"), "authorization_code");

  // a code lifted from one browser's return address and presented with another browser's sealed sign-in: the state
  // would have to match first; made to match, the verifier still is not the one the code was issued against
  const a = await web.begin("/debts");
  const stolen = issuer.signInAt(a.url);
  const b = await web.begin("/debts");
  const forged = new URLSearchParams({ code: stolen.get("code")!, state: new URL(b.url).searchParams.get("state")! });
  await refusal(web.finish(forged, b.pending), /refused the code: invalid_grant/);
});

test("an ID token that does not carry this sign-in's nonce is refused", async () => {
  const { issuer, web } = await world();
  for (const nonce of ["another-sign-ins-nonce", "", undefined, 7]) {
    issuer.tamper = { claims: (c) => ({ ...c, nonce }) };
    const { url, pending } = await web.begin("/debts");
    await refusal(web.finish(issuer.signInAt(url), pending), /not for the sign-in this browser began/);
  }
});

test("an ID token is believed only when the issuer's key signed it with RS256", async () => {
  const { issuer, web } = await world();
  const other = await newKey();
  const hmac = async (head: string) => {      // the classic confusion: the issuer's public key, used as a shared secret
    const key = await crypto.subtle.importKey("raw", await crypto.subtle.exportKey("spki", issuer.publicKey), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    return base64url(new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(head))));
  };
  const attempt = async (answer: (body: Record<string, unknown>) => Promise<Record<string, unknown>> | Record<string, unknown>) => {
    const { url, pending } = await web.begin("/debts");
    const back = issuer.signInAt(url);
    const honest = issuer.fetch;
    const lying = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const response = await honest(input, init);
      return String(input).endsWith("/token") ? new Response(JSON.stringify(await answer(await response.json())), { status: 200 }) : response;
    }) as typeof fetch;
    return relyingParty(SIGN_IN, { fetch: lying, now: () => 1_800_000_000 }).finish(back, pending);
  };
  const parts = (body: Record<string, unknown>) => (body.id_token as string).split(".");
  const reheaded = (body: Record<string, unknown>, header: object) => base64url(new TextEncoder().encode(JSON.stringify(header))) + "." + parts(body)[1];

  issuer.tamper = { signWith: other };
  await refusal(attempt((b) => b), /signature is not the issuer's/);                                       // another key, the issuer's kid
  issuer.tamper = { signature: (s) => s.slice(0, -4) + (s.endsWith("AAAA") ? "BBBB" : "AAAA") };
  await refusal(attempt((b) => b), /signature is not the issuer's/);
  issuer.tamper = { header: { kid: "unknown" } };
  await refusal(attempt((b) => b), /signature is not the issuer's/);
  issuer.tamper = {};
  await refusal(attempt((b) => ({ ...b, id_token: reheaded(b, { alg: "none" }) + "." })), /not signed with RS256/);
  await refusal(attempt((b) => ({ ...b, id_token: reheaded(b, { alg: "none" }) + "." + parts(b)[2] })), /not signed with RS256/);
  await refusal(attempt(async (b) => { const head = reheaded(b, { alg: "HS256", kid: "k1" }); return { ...b, id_token: `${head}.${await hmac(head)}` }; }), /not signed with RS256/);
  await refusal(attempt((b) => ({ ...b, id_token: reheaded(b, { alg: "RS256", kid: "k1" }).split(".")[0] + "." + base64url(new TextEncoder().encode(JSON.stringify({ sub: "someone-else" }))) + "." + parts(b)[2] })), /signature is not the issuer's/);
  issuer.tamper = { header: { crit: ["exp"] } };
  await refusal(attempt((b) => b), /does not understand/);
  issuer.tamper = { signWith: other, header: { kid: undefined } };                                         // no kid: every key is tried, none fits
  await refusal(attempt((b) => b), /signature is not the issuer's/);
  // a key the issuer publishes for something other than signing RS256 is not believed, though it would verify
  const others = async (shape: object) => [{ ...(await crypto.subtle.exportKey("jwk", other.publicKey)), kid: "k1", ...shape }];
  for (const shape of [{ use: "enc" }, { alg: "RS512" }, { alg: "PS256" }, { kty: "EC" }, { kid: "k2" }]) {
    issuer.tamper = { signWith: other, keys: await others(shape) };
    await refusal(attempt((b) => b), /signature is not the issuer's/);
  }
  issuer.tamper = { signWith: other, keys: await others({}) };
  assert.equal((await attempt((b) => b)).ok, true);                                                        // the same key, published for signing
  for (const keys of [[], "none", null]) {
    issuer.tamper = { keys };
    await refusal(attempt((b) => b), /signature is not the issuer's|keys could not be read/);
  }
  issuer.tamper = { header: { kid: undefined }, otherKeys: await others({ kid: "k0" }) };                  // no kid: the right key is found among several
  assert.equal((await attempt((b) => b)).ok, true);
  issuer.tamper = {};
  await refusal(attempt((b) => ({ ...b, id_token: undefined })), /id token: none given/);
  await refusal(attempt((b) => ({ ...b, id_token: "a.b" })), /not a signed token/);
  await refusal(attempt((b) => ({ ...b, id_token: "!.!.!" })), /unreadable/);
  assert.equal((await attempt((b) => b)).ok, true);
});

test("an ID token from another issuer, for another client, out of date or naming nobody is refused", async () => {
  const { issuer, web, clock } = await world();
  const cases: [RegExp, (c: Record<string, unknown>) => Record<string, unknown>][] = [
    [/from another issuer/, (c) => ({ ...c, iss: "https://id.example.test/realms/other" })],
    [/from another issuer/, (c) => ({ ...c, iss: undefined })],
    [/issued for another client/, (c) => ({ ...c, aud: "another-client" })],
    [/issued for another client/, (c) => ({ ...c, aud: ["another-client", "domuvai-api"] })],
    [/issued for another client/, (c) => ({ ...c, aud: undefined })],
    [/issued to another party/, (c) => ({ ...c, aud: ["domuvai-web", "another-client"], azp: "another-client" })],
    [/issued to another party/, (c) => ({ ...c, aud: ["domuvai-web", "another-client"], azp: undefined })],
    [/issued to another party/, (c) => ({ ...c, azp: "another-client" })],
    [/out of date/, (c) => ({ ...c, exp: clock.now })],
    [/out of date/, (c) => ({ ...c, exp: undefined })],
    [/out of date/, (c) => ({ ...c, exp: String(clock.now + 300) })],
    [/issued in the future/, (c) => ({ ...c, iat: clock.now + 31 })],
    [/issued in the future/, (c) => ({ ...c, iat: undefined })],
    [/not yet valid/, (c) => ({ ...c, nbf: clock.now + 3600 })],
    [/names nobody/, (c) => ({ ...c, sub: undefined })],
    [/names nobody/, (c) => ({ ...c, sub: " " })],
  ];
  for (const [why, claims] of cases) {
    issuer.tamper = { claims };
    const { url, pending } = await web.begin("/debts");
    await refusal(web.finish(issuer.signInAt(url), pending), why);
  }
  issuer.tamper = { claims: (c) => ({ ...c, aud: ["domuvai-web"], azp: undefined, iat: clock.now + 30, exp: clock.now + 1 }) };
  const { url, pending } = await web.begin("/debts");
  assert.equal((await web.finish(issuer.signInAt(url), pending)).ok, true);
});

test("an issuer that refuses, answers without a bearer token, or cannot be reached leaves nobody signed in", async () => {
  const { issuer, web } = await world();
  const attempt = async (tamper: typeof issuer.tamper, back?: (q: URLSearchParams) => URLSearchParams) => {
    issuer.tamper = {};
    const { url, pending } = await web.begin("/debts");
    const q = issuer.signInAt(url);
    issuer.tamper = tamper;
    return web.finish(back ? back(q) : q, pending);
  };
  await refusal(attempt({}, (q) => new URLSearchParams({ state: q.get("state")!, error: "access_denied", error_description: "<script>" })), /^the issuer refused the sign-in$/);
  await refusal(attempt({}, (q) => new URLSearchParams({ state: q.get("state")! })), /sent no code/);
  await refusal(attempt({}, (q) => new URLSearchParams({ state: q.get("state")!, code: "made-up" })), /refused the code: invalid_grant/);
  await refusal(attempt({ tokenAnswer: (b) => ({ ...b, access_token: undefined }) }), /no bearer token/);
  await refusal(attempt({ tokenAnswer: (b) => ({ ...b, token_type: "mac" }) }), /no bearer token/);
  await refusal(attempt({ tokenAnswer: (b) => ({ ...b, expires_in: 0 }) }), /no bearer token/);
  await refusal(attempt({ down: true }), /could not be reached/);

  // a code is spent once
  issuer.tamper = {};
  const { url, pending } = await web.begin("/debts");
  const back = issuer.signInAt(url);
  assert.equal((await web.finish(back, pending)).ok, true);
  await refusal(web.finish(back, pending), /refused the code: invalid_grant/);
});

test("no refusal repeats a token, a secret or the issuer's own words", async () => {
  const { issuer, web } = await world();
  issuer.tamper = { tokenAnswer: () => ({ error: "Invalid <b>grant</b> access-1 " + SIGN_IN.clientSecret, error_description: "refresh-1" }) };
  const { url, pending } = await web.begin("/debts");
  const honest = issuer.fetch;
  const failing = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const response = await honest(input, init);
    return String(input).endsWith("/token") ? new Response(await response.text(), { status: 400 }) : response;
  }) as typeof fetch;
  const finished = await relyingParty(SIGN_IN, { fetch: failing, now: () => 1_800_000_000 }).finish(issuer.signInAt(url), pending);
  assert.deepEqual(finished, { ok: false, why: "the issuer refused the code: no reason given", answered: true });
});

test("the issuer's document must name this issuer and keep its endpoints on its own origin", async () => {
  for (const [why, document] of [
    [/names another issuer/, (d: Record<string, unknown>) => ({ ...d, issuer: "https://id.example.test/realms/other" })],
    [/token_endpoint is on another origin/, (d: Record<string, unknown>) => ({ ...d, token_endpoint: "https://evil.example.test/token" })],
    [/authorization_endpoint is on another origin/, (d: Record<string, unknown>) => ({ ...d, authorization_endpoint: "http://id.example.test/auth" })],
    [/jwks_uri is on another origin/, (d: Record<string, unknown>) => ({ ...d, jwks_uri: "https://id.example.test.evil.example/certs" })],
    [/no token_endpoint/, (d: Record<string, unknown>) => ({ ...d, token_endpoint: undefined })],
  ] as const) {
    const { issuer, web } = await world();
    issuer.tamper = { document };
    await assert.rejects(web.begin("/debts"), why);
    issuer.tamper = {};
    assert.ok((await web.begin("/debts")).url.startsWith("https://id.example.test/realms/domuvai/auth?"));   // a failure is not remembered
  }
});

// ── renewal ──────────────────────────────────────────────────────────────────────────────────────────────────────

test("the issuer's keys are read again after an hour, and at most every half minute for a key not seen before", async () => {
  const { issuer, web, clock } = await world();
  const keyReads = () => issuer.asked.filter((a) => a.url.endsWith("/certs")).length;
  const signIn = async () => { const { url, pending } = await web.begin("/debts"); return web.finish(issuer.signInAt(url), pending); };
  assert.equal((await signIn()).ok, true);
  assert.equal((await signIn()).ok, true);
  assert.equal(keyReads(), 1);
  issuer.tamper = { header: { kid: "new" } };
  assert.equal((await signIn()).ok, false);
  assert.equal(keyReads(), 1);                    // asked a moment ago: not again yet
  clock.now += 30;
  assert.equal((await signIn()).ok, false);
  assert.equal(keyReads(), 2);
  issuer.tamper = {};
  clock.now += 3599;
  assert.equal((await signIn()).ok, true);
  assert.equal(keyReads(), 2);
  clock.now += 1;
  assert.equal((await signIn()).ok, true);
  assert.equal(keyReads(), 3);
});

test("a session is renewed with its refresh token; refused, it is over; the old access token is never kept", async () => {
  const { issuer, web, clock } = await world();
  const { url, pending } = await web.begin("/debts");
  const finished = await web.finish(issuer.signInAt(url), pending);
  assert.ok(finished.ok);
  clock.now += 290;
  const renewed = await web.renew(finished.session);
  assert.deepEqual(renewed, {
    kind: "renewed",
    session: { accessToken: "access-2", refreshToken: "refresh-2", accessUntil: clock.now + 300, until: clock.now + 1800, subject: issuer.subject, name: "ivan" },
  });

  assert.deepEqual(await web.renew(finished.session), { kind: "refused" });                       // the issuer no longer knows this refresh token
  assert.deepEqual(await web.renew({ ...finished.session, refreshToken: null }), { kind: "refused" });
  issuer.tamper = { down: true };
  assert.deepEqual(await web.renew(finished.session), { kind: "unreachable" });
  for (const status of [500, 503, 429, 408]) {
    issuer.tamper = { status };
    assert.deepEqual(await web.renew(finished.session), { kind: "unreachable" }, String(status));
  }
  for (const status of [400, 401, 403]) {
    issuer.tamper = { status };
    assert.deepEqual(await web.renew(finished.session), { kind: "refused" }, String(status));
  }
  assert.ok(renewed.kind === "renewed");
  issuer.tamper = { keepRefresh: true };                                                          // an issuer that does not rotate: the same refresh token, a new access token
  const again = await web.renew(renewed.session);
  assert.ok(again.kind === "renewed");
  assert.deepEqual([again.session.accessToken, again.session.refreshToken], ["access-3", "refresh-2"]);
  issuer.tamper = { tokenAnswer: (b) => ({ ...b, access_token: "" }) };
  assert.deepEqual(await web.renew(again.session), { kind: "refused" });
});
