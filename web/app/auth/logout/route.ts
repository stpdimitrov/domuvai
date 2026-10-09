import { NextResponse, type NextRequest } from "next/server";
import { cookies, putSession, readSession } from "@/lib/auth/gate";
import { now, party } from "@/lib/auth/party";
import { signInMode } from "@/lib/auth/server";

export const dynamic = "force-dynamic";

/**
 * Sign-out (ADR-011, AUTH-03): the session is taken back from the browser, and the issuer is told — from this server,
 * with the session's own refresh token, so no token passes through the browser — to end its own session too, or the
 * next "sign in" would let the same person in without asking. A POST from this site's own pages only: another site
 * cannot sign a person out.
 */
export async function POST(request: NextRequest) {
  const m = signInMode();
  if (m.kind !== "signIn") return new NextResponse(null, { status: 303, headers: { location: "/" } });
  if (request.headers.get("origin") !== m.signIn.webUrl) return new NextResponse(null, { status: 403 });

  const session = await readSession(m.signIn, (name) => request.cookies.get(name)?.value, now());
  if (session && !(await party(m.signIn).end(session))) console.error("sign-out: the issuer's own session may still be open");
  const response = NextResponse.redirect(new URL("/", m.signIn.webUrl), { status: 303, headers: { "cache-control": "no-store" } });
  await putSession(m.signIn, null, now(), (name, value, options) => response.cookies.set(name, value, options));
  response.cookies.set(cookies(m.signIn).pending, "", cookies(m.signIn).options(0));
  return response;
}
