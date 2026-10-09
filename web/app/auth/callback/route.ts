import { NextResponse, type NextRequest } from "next/server";
import { cookies, LOGIN, putSession } from "@/lib/auth/gate";
import { now, party } from "@/lib/auth/party";
import { signInMode } from "@/lib/auth/server";
import { HOME } from "@/lib/auth/settings";

export const dynamic = "force-dynamic";

const FAILED = `<!doctype html><html lang="bg"><meta charset="utf-8"><meta name="robots" content="noindex"><title>Входът не успя</title>
<body style="font:16px/1.5 system-ui;margin:15vh auto;max-width:28em;padding:0 1em"><h1 style="font-size:1.25em">Входът не успя</h1>
<p>Не успяхме да ви впишем. Нищо не е записано.</p><p><a href="${LOGIN}">Опитайте отново</a></p></body></html>`;

/**
 * A sign-in finished, or refused (ADR-011, AUTH-03). What the browser kept from the beginning is taken back once it
 * is answered, whatever comes of the answer — it is good for one. A request that is not an answer to it (another
 * site's, an old tab's) is refused and takes nothing back, so it cannot cancel a sign-in under way. The address a person is then sent to is built from this server's own
 * setting and a path on this site, never from the request. Why a sign-in was refused is logged — it names no token —
 * and not told to the browser.
 */
export async function GET(request: NextRequest) {
  const m = signInMode();
  if (m.kind !== "signIn") return new NextResponse(null, { status: 307, headers: { location: HOME } });
  const { pending, options } = cookies(m.signIn);
  const finished = await party(m.signIn).finish(request.nextUrl.searchParams, request.cookies.get(pending)?.value);

  let response: NextResponse;
  if (finished.ok) {
    response = NextResponse.redirect(new URL(finished.returnTo, m.signIn.webUrl), { status: 302 });
    if (!(await putSession(m.signIn, finished.session, now(), (name, value, o) => response.cookies.set(name, value, o)))) {
      console.error("sign-in refused: the session is too long for the browser to keep");
      response = new NextResponse(FAILED, { status: 400 });
      await putSession(m.signIn, null, now(), (name, value, o) => response.cookies.set(name, value, o));
    }
  } else {
    console.error("sign-in refused:", finished.why);
    response = new NextResponse(FAILED, { status: 400 });
  }
  if (response.status === 400) response.headers.set("content-type", "text/html; charset=utf-8");
  response.headers.set("cache-control", "no-store");
  response.headers.set("referrer-policy", "no-referrer");
  if (finished.ok || finished.answered) response.cookies.set(pending, "", options(0));
  return response;
}
