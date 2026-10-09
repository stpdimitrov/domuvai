import { fromBase64url } from "./seal.ts";

/**
 * The ID token, checked (OpenID Connect Core §3.1.3.7; ADR-011, AUTH-03): signed by one of the issuer's published
 * keys with RS256 and nothing else — `none`, a shared-secret algorithm or any other is refused before a key is
 * touched — issued by this issuer, for this client, in date, and carrying the nonce this browser's sign-in began with.
 */
export type Jwk = { kty?: string; kid?: string; use?: string; alg?: string; n?: string; e?: string };
export type Expected = { issuer: string; clientId: string; nonce: string; now: number };
export type IdClaims = { sub: string; preferred_username?: unknown; name?: unknown };
export type Checked = { ok: true; claims: IdClaims } | { ok: false; why: string };

/** How far the issuer's clock and this server's may differ, in seconds. */
const SKEW = 30;

const refused = (why: string): Checked => ({ ok: false, why: `id token: ${why}` });
const json = (part: string): Record<string, unknown> => {
  const parsed: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(fromBase64url(part)));
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw new Error("not an object");
  return parsed as Record<string, unknown>;
};

export async function checkIdToken(token: unknown, expected: Expected, keysFor: (kid: string | undefined) => Promise<Jwk[]>): Promise<Checked> {
  if (typeof token !== "string") return refused("none given");
  const parts = token.split(".");
  if (parts.length !== 3) return refused("not a signed token");
  let header: Record<string, unknown>, claims: Record<string, unknown>, signature: Uint8Array<ArrayBuffer>;
  try {
    header = json(parts[0]);
    claims = json(parts[1]);
    signature = fromBase64url(parts[2]);
  } catch {
    return refused("unreadable");
  }

  if (header.alg !== "RS256") return refused("not signed with RS256");
  if (header.crit !== undefined) return refused("asks for something this server does not understand");
  const kid = typeof header.kid === "string" ? header.kid : undefined;
  const candidates = (await keysFor(kid)).filter(
    (k) => k.kty === "RSA" && (k.use ?? "sig") === "sig" && (k.alg ?? "RS256") === "RS256" && (kid === undefined || k.kid === kid),
  );
  const signed = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  let verified = false;
  for (const jwk of candidates) {
    try {
      const key = await crypto.subtle.importKey("jwk", { kty: "RSA", n: jwk.n, e: jwk.e }, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
      if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, signature, signed)) verified = true;
    } catch {
      // a key that cannot be read verifies nothing
    }
  }
  if (!verified) return refused("the signature is not the issuer's");

  if (claims.iss !== expected.issuer) return refused("from another issuer");
  const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  if (!audiences.includes(expected.clientId)) return refused("issued for another client");
  if (claims.azp !== undefined ? claims.azp !== expected.clientId : audiences.length > 1) return refused("issued to another party");
  if (typeof claims.exp !== "number" || claims.exp <= expected.now) return refused("out of date");
  if (typeof claims.iat !== "number" || claims.iat > expected.now + SKEW) return refused("issued in the future");
  if (typeof claims.nbf === "number" && claims.nbf > expected.now + SKEW) return refused("not yet valid");
  if (typeof claims.nonce !== "string" || claims.nonce !== expected.nonce) return refused("not for the sign-in this browser began");
  if (typeof claims.sub !== "string" || !claims.sub.trim()) return refused("names nobody");
  return { ok: true, claims: claims as IdClaims };
}
