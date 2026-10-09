import { relyingParty, type RelyingParty } from "./oidc.ts";
import type { SignIn } from "./settings.ts";

/** One relying party per set of settings, kept while the server runs: it remembers the issuer's endpoints and keys. */
const parties = new Map<string, RelyingParty>();
export function party(signIn: SignIn): RelyingParty {
  const id = JSON.stringify(signIn);
  let known = parties.get(id);
  if (!known) parties.set(id, (known = relyingParty(signIn)));
  return known;
}

export const now = () => Math.floor(Date.now() / 1000);
