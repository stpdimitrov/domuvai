/**
 * The seal on what this server hands a browser to keep (ADR-011, AUTH-03): AES-256-GCM under a key derived from the
 * session secret, one key per purpose. A sealed value says nothing to its holder and cannot be altered, cut, moved to
 * another purpose or used past its time — it then does not open. Web Crypto only, so middleware can open it too.
 */
const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });

export const base64url = (bytes: Uint8Array): string => {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

/** The bytes of a base64url text; throws on anything that is not one. */
export const fromBase64url = (text: string): Uint8Array<ArrayBuffer> => {
  if (!/^[A-Za-z0-9_-]*$/.test(text)) throw new Error("not base64url");
  const binary = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
};

export const random = (bytes = 32): string => base64url(crypto.getRandomValues(new Uint8Array(bytes)));

const keys = new Map<string, Promise<CryptoKey>>();
function key(secret: string, purpose: string): Promise<CryptoKey> {
  const id = `${purpose}\u0000${secret}`;
  let derived = keys.get(id);
  if (!derived) {
    derived = crypto.subtle
      .importKey("raw", encoder.encode(secret), "HKDF", false, ["deriveKey"])
      .then((material) =>
        crypto.subtle.deriveKey(
          { name: "HKDF", hash: "SHA-256", salt: encoder.encode("domuvai.web"), info: encoder.encode(purpose) },
          material,
          { name: "AES-GCM", length: 256 },
          false,
          ["encrypt", "decrypt"],
        ),
      );
    keys.set(id, derived);
  }
  return derived;
}

/** [value], sealed for [purpose], good until [until] (epoch seconds). */
export async function seal(secret: string, purpose: string, value: unknown, until: number): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const sealed = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: encoder.encode(purpose) },
    await key(secret, purpose),
    encoder.encode(JSON.stringify({ value, until })),
  );
  const out = new Uint8Array(iv.length + sealed.byteLength);
  out.set(iv);
  out.set(new Uint8Array(sealed), iv.length);
  return base64url(out);
}

/** What was sealed, or null: not sealed by this server for this purpose, altered, or past its time at [now]. */
export async function open<T>(secret: string, purpose: string, sealed: string | null | undefined, now: number): Promise<T | null> {
  if (!sealed) return null;
  try {
    const bytes = fromBase64url(sealed);
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: bytes.subarray(0, 12), additionalData: encoder.encode(purpose) },
      await key(secret, purpose),
      bytes.subarray(12),
    );
    const { value, until } = JSON.parse(decoder.decode(plain)) as { value: T; until: unknown };
    return typeof until === "number" && now < until ? value : null;
  } catch {
    return null;
  }
}

/** Whether two texts are the same, in time that does not depend on where they differ. An empty text equals nothing. */
export function same(a: string, b: string): boolean {
  if (a.length !== b.length || a.length === 0) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
