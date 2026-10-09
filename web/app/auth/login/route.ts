import { NextResponse, type NextRequest } from "next/server";
import { cookies } from "@/lib/auth/gate";
import { PENDING_SECONDS } from "@/lib/auth/oidc";
import { party } from "@/lib/auth/party";
import { signInMode } from "@/lib/auth/server";
import { HOME } from "@/lib/auth/settings";

export const dynamic = "force-dynamic";

/**
 * A sign-in begun (ADR-011, AUTH-03): the browser is sent to the issuer, keeping — sealed, httpOnly — what the
 * answer will be held to. Where people do not sign in, this is simply the way into the console.
 */
export async function GET(request: NextRequest) {
  const m = signInMode();
  if (m.kind !== "signIn") return new NextResponse(null, { status: 307, headers: { location: HOME } });
  let begun: { url: string; pending: string };
  try {
    begun = await party(m.signIn).begin(request.nextUrl.searchParams.get("return"));
  } catch (cause) {
    console.error("sign-in: the issuer cannot be asked:", cause instanceof Error ? cause.message : "unknown");
    return new NextResponse("Входът не е достъпен в момента. Опитайте отново след малко.", {
      status: 503,
      headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" },
    });
  }
  const response = NextResponse.redirect(begun.url, { status: 302, headers: { "cache-control": "no-store" } });
  const { pending, options } = cookies(m.signIn);
  response.cookies.set(pending, begun.pending, options(PENDING_SECONDS));
  return response;
}
