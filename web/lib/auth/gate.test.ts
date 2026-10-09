import assert from "node:assert/strict";
import { test } from "node:test";
import { fakeIssuer } from "./fakeIssuer.ts";
import { cookies, decide, mode, putSession, readSession, type Mode } from "./gate.ts";
import { relyingParty, type Session } from "./oidc.ts";
import type { SignIn } from "./settings.ts";

const ENV = {
  DOMUVAI_AUTH_ISSUER: "https://id.example.test/realms/domuvai",
  DOMUVAI_AUTH_CLIENT_ID: "domuvai-web",
  DOMUVAI_AUTH_CLIENT_SECRET: "a-client-secret-made-for-this-test",
  DOMUVAI_SESSION_SECRET: "a-session-secret-of-thirty-two-chars-or-more",
  DOMUVAI_WEB_URL: "https://domuvai.example.test",
};
const SIGN_IN = (mode(ENV) as { signIn: SignIn }).signIn;
const SESSION: Session = { accessToken: "the-access-token", refreshToken: "the-refresh-token", accessUntil: 1300, until: 2800, subject: "s-1", name: "ivan" };

test("PM-DEBT-011 the console is closed unless sign-in is configured whole, or the server is told in so many words to run without it", () => {
  assert.equal(mode(ENV).kind, "signIn");
  assert.equal(mode({ ...ENV, NODE_ENV: "development" }).kind, "signIn");
  assert.equal(mode({ DOMUVAI_AUTH: "off" }).kind, "open");
  assert.equal(mode({ DOMUVAI_AUTH: " off ", NODE_ENV: "production" }).kind, "open");
  assert.equal(mode({ NODE_ENV: "development" }).kind, "open");                      // `next dev`, bound to this machine

  const closed = (env: Record<string, string | undefined>) => assert.equal(mode(env).kind, "closed", JSON.stringify(env));
  closed({});
  closed({ NODE_ENV: "production" });
  closed({ DOMUVAI_CONSOLE: "on", NODE_ENV: "production" });                         // the old switch opens nothing
  for (const word of ["on", "OFF", "Off", "false", "0", "no", "of"]) closed({ DOMUVAI_AUTH: word });
  for (const name of Object.keys(ENV)) {
    closed({ ...ENV, [name]: undefined });                                           // half-configured, in production and in dev
    closed({ ...ENV, [name]: "", NODE_ENV: "development" });
    closed({ DOMUVAI_AUTH: "off", [name]: ENV[name as keyof typeof ENV] });          // off beside a sign-in setting: one or the other
  }
  closed({ ...ENV, DOMUVAI_AUTH: "off" });
  closed({ ...ENV, DOMUVAI_AUTH_ISSUER: "http://id.example.test/realms/domuvai" });
  closed({ ...ENV, DOMUVAI_SESSION_SECRET: "short" });
});

test("PM-DEBT-011 only the landing and the sign-in routes are served to a person who is not signed in", () => {
  const signIn: Mode = { kind: "signIn", signIn: SIGN_IN };
  const page = (path: string, search = "") => ({ path, search, navigation: true });
  const script = (path: string) => ({ path, search: "", navigation: false });
  const PATHS = ["/portfolio", "/debts", "/entrance/fund", "/assembly", "/no-such-page", "/auth", "/auth/", "/auth/login/x", "/auth/callbackx", "/Auth/login", "/_next/image", "/api/anything", "//"];

  for (const m of [signIn, { kind: "open" }, { kind: "closed", why: "" }] as Mode[]) {
    for (const signedIn of [true, false]) assert.deepEqual(decide(m, page("/"), signedIn), { kind: "pass" });
  }
  for (const path of [...PATHS, "/auth/login", "/auth/callback", "/auth/logout"]) {
    for (const signedIn of [true, false]) {
      assert.deepEqual(decide({ kind: "closed", why: "" }, page(path), signedIn), { kind: "notFound" }, path);
      assert.deepEqual(decide({ kind: "closed", why: "" }, script(path), signedIn), { kind: "notFound" }, path);
      assert.deepEqual(decide({ kind: "open" }, page(path), signedIn), { kind: "pass" }, path);
    }
  }
  for (const path of PATHS) {
    assert.deepEqual(decide(signIn, page(path), true), { kind: "pass" }, path);
    assert.deepEqual(decide(signIn, script(path), true), { kind: "pass" }, path);
    assert.equal(decide(signIn, page(path), false).kind, "signIn", path);
    assert.deepEqual(decide(signIn, script(path), false), { kind: "unauthorized" }, path);
  }
  for (const path of ["/auth/login", "/auth/callback", "/auth/logout"]) assert.deepEqual(decide(signIn, script(path), false), { kind: "pass" });
  assert.deepEqual(decide(signIn, page("/entrance/fund", "?entrance=7&period=2026-09"), false), {
    kind: "signIn",
    to: "/auth/login?return=%2Fentrance%2Ffund%3Fentrance%3D7%26period%3D2026-09",
  });
});

test("the cookies are httpOnly and SameSite=Lax; Secure and host-only wherever the web is https", () => {
  const https = cookies(SIGN_IN);
  assert.deepEqual([https.session, https.pending], ["__Host-domuvai-session", "__Host-domuvai-signin"]);
  assert.deepEqual(https.options(600), { httpOnly: true, secure: true, sameSite: "lax", path: "/", maxAge: 600 });
  assert.equal(https.options(-5).maxAge, 0);
  const local = cookies({ ...SIGN_IN, webUrl: "http://localhost:3000" });
  assert.deepEqual([local.session, local.pending], ["domuvai-session", "domuvai-signin"]);
  assert.deepEqual(local.options(600), { httpOnly: true, secure: false, sameSite: "lax", path: "/", maxAge: 600 });
});

test("a session is handed to the browser sealed, in pieces a cookie can hold, and read back only whole and in time", async () => {
  const jar = new Map<string, { value: string; maxAge: number; httpOnly: boolean }>();
  const put = (name: string, value: string, options: { maxAge: number; httpOnly: boolean }) => void jar.set(name, { value, ...options });
  const get = (name: string) => jar.get(name)?.value || undefined;

  const long: Session = { ...SESSION, accessToken: "a".repeat(5000) };
  assert.equal(await putSession(SIGN_IN, long, 1000, put), true);
  const pieces = [...jar].filter(([, c]) => c.value);
  assert.ok(pieces.length >= 2 && pieces.every(([name, c]) => name.startsWith("__Host-domuvai-session.") && c.value.length <= 3500 && c.httpOnly && c.maxAge === 1800));
  assert.ok(pieces.every(([, c]) => !c.value.includes("the-refresh-token") && !c.value.includes("aaaa")));
  assert.deepEqual(await readSession(SIGN_IN, get, 1000), long);
  assert.equal(await readSession(SIGN_IN, get, 2800), null);                                            // over
  assert.equal(await readSession(SIGN_IN, (name) => (name.endsWith(".1") ? undefined : get(name)), 1000), null);   // a piece missing
  assert.equal(await readSession({ ...SIGN_IN, sessionSecret: SIGN_IN.sessionSecret + "x" }, get, 1000), null);
  assert.equal(await readSession(SIGN_IN, () => undefined, 1000), null);

  assert.equal(await putSession(SIGN_IN, SESSION, 1000, put), true);                                    // a shorter one leaves no piece of the longer behind
  assert.deepEqual([...jar].filter(([, c]) => c.value).map(([name]) => name), ["__Host-domuvai-session.0"]);
  assert.deepEqual(await readSession(SIGN_IN, get, 1000), SESSION);

  assert.equal(await putSession(SIGN_IN, { ...SESSION, accessToken: "a".repeat(20000) }, 1000, put), false);   // too long: nothing is handed over
  assert.ok([...jar.values()].every((c) => c.value === "" && c.maxAge === 0));
  assert.equal(await putSession(SIGN_IN, SESSION, 1000, put), true);
  assert.equal(await putSession(SIGN_IN, null, 1000, put), true);                                       // signed out: every piece taken back
  assert.equal(jar.size, 4);
  assert.ok([...jar.values()].every((c) => c.value === "" && c.maxAge === 0));
  assert.equal(await readSession(SIGN_IN, get, 1000), null);
});

test("sign-out ends the issuer's session with the session's own refresh token, from this server", async () => {
  const clock = { now: 1_800_000_000 };
  const issuer = await fakeIssuer(SIGN_IN, clock);
  const web = relyingParty(SIGN_IN, { fetch: issuer.fetch, now: () => clock.now });
  const { url, pending } = await web.begin("/debts");
  const finished = await web.finish(issuer.signInAt(url), pending);
  assert.ok(finished.ok);

  assert.equal(await web.end({ ...finished.session, refreshToken: null }), false);
  assert.equal(await web.end({ ...finished.session, refreshToken: "another" }), false);
  assert.deepEqual(await web.renew(finished.session).then((r) => r.kind), "renewed");                   // still open after the refusals
  const session = { ...finished.session, refreshToken: "refresh-2" };
  assert.equal(await web.end(session), true);
  const asked = issuer.asked.at(-1)!;
  assert.equal(asked.url, "https://id.example.test/realms/domuvai/logout");
  assert.deepEqual(Object.fromEntries(asked.form!), { client_id: "domuvai-web", client_secret: SIGN_IN.clientSecret, refresh_token: "refresh-2" });
  assert.deepEqual(await web.renew(session), { kind: "refused" });                                      // over at the issuer too
  issuer.tamper = { down: true };
  assert.equal(await web.end(session), false);
});
