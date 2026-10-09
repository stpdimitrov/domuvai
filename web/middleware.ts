import { NextResponse, type NextRequest } from "next/server";
import { decide, isSignInRoute, mode, putSession, readSession } from "@/lib/auth/gate";
import type { Session } from "@/lib/auth/oidc";
import { now, party } from "@/lib/auth/party";

/** A session is renewed when its access token has less than this left — seconds: longer than one request to `api` may take. */
const RENEW_BEFORE = 30;

const text = (body: string, status: number) =>
  new NextResponse(body, { status, headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" } });

/**
 * Rule: PM-DEBT-011 — the console is served to a person who is signed in and to nobody else (`lib/auth/gate.ts`).
 * Decided here, before any route is matched, so a page added later is behind the same rule. This is also where a
 * session is renewed: only here can a fresh one be handed both to the browser and to the page being rendered.
 */
export async function middleware(request: NextRequest) {
  const m = mode(process.env);
  const { pathname, search } = request.nextUrl;
  let session: Session | null = null;
  let renewed = false;
  let over = false;

  if (m.kind === "signIn" && pathname !== "/" && !isSignInRoute(pathname)) {
    session = await readSession(m.signIn, (name) => request.cookies.get(name)?.value, now());
    if (session && session.accessUntil - now() < RENEW_BEFORE) {
      const answer = await party(m.signIn).renew(session);
      if (answer.kind === "renewed") {
        session = answer.session;
        renewed = true;
      } else if (answer.kind === "refused") {
        session = null;
        over = true;
      } else if (session.accessUntil <= now()) {
        return text("Входът не е достъпен в момента. Опитайте отново след малко.", 503);
      }
    }
  }

  const headers = request.headers;
  const navigation = request.method === "GET" && !headers.has("rsc") && !headers.has("next-router-prefetch") && (headers.get("sec-fetch-mode") ?? "navigate") === "navigate";
  const verdict = decide(m, { path: pathname, search, navigation }, session !== null);

  let response: NextResponse;
  if (verdict.kind === "notFound") return text("Страницата не е намерена.", 404);
  if (verdict.kind === "pass") {
    if (m.kind === "signIn" && renewed) await putSession(m.signIn, session, now(), (name, value) => request.cookies.set(name, value));
    response = NextResponse.next({ request });
  } else if (verdict.kind === "signIn" && m.kind === "signIn") {
    response = NextResponse.redirect(new URL(verdict.to, m.signIn.webUrl), { status: 302, headers: { "cache-control": "no-store" } });
  } else {
    response = text("Не сте влезли.", 401);
  }
  if (m.kind === "signIn" && (renewed || over)) await putSession(m.signIn, session, now(), (name, value, options) => response.cookies.set(name, value, options));
  return response;
}

// Every request but the build's static files — code and the design's sample text, never what the API returns.
export const config = { matcher: ["/((?!_next/static/).*)"] };
