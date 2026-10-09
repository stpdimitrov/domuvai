import "server-only";
import { cookies as requestCookies } from "next/headers";
import { mode, readSession } from "./gate.ts";
import type { Session } from "./oidc.ts";
import { now } from "./party.ts";

/** How this server serves the console, read from its environment on this request. */
export const signInMode = () => mode(process.env);

/** The signed-in person's session, on this request — null where nobody is signed in, or people do not sign in here. */
export async function currentSession(): Promise<Session | null> {
  const m = signInMode();
  if (m.kind !== "signIn") return null;
  const jar = await requestCookies();
  return readSession(m.signIn, (name) => jar.get(name)?.value, now());
}
