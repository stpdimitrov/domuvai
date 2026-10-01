import { NextResponse, type NextRequest } from "next/server";
import { consoleOpen } from "@/lib/consoleSwitch";

/**
 * Rule: PM-DEBT-011 — where the console is not switched on, every path but the landing answers 404 from here, before
 * any route is matched: one response for a console page and for a path that does not exist. Closed is the default:
 * a page added later is closed with the rest, with no list of console paths to keep.
 */
export function middleware(request: NextRequest) {
  if (request.nextUrl.pathname === "/" || consoleOpen()) return NextResponse.next();
  return new NextResponse("Страницата не е намерена.", { status: 404, headers: { "content-type": "text/plain; charset=utf-8" } });
}

// Every request but the build's static files — code and the design's sample text, never what the API returns.
export const config = { matcher: ["/((?!_next/static/).*)"] };
