/**
 * The web's sign-in settings (ADR-011, AUTH-03), read from the running server's environment — never baked into the
 * build, never from a `web/.env*` default. All five or nothing: a value missing or malformed is "not configured",
 * and what that means is the caller's to decide — closed, never open (the api's rule, applied to the web).
 */
export type SignIn = {
  /** The realm's issuer URL, the same value the api trusts (`DOMUVAI_AUTH_ISSUER`). */
  issuer: string;
  clientId: string;
  clientSecret: string;
  /** What the session cookie is sealed under. */
  sessionSecret: string;
  /** This web's own origin — the return address is built from it, never from a request's Host header. */
  webUrl: string;
};

/** The one path the issuer may send a person back to. The realm's client allows this address and no other. */
export const CALLBACK_PATH = "/auth/callback";

export type Env = Record<string, string | undefined>;
export type Settings = { signIn: SignIn } | { refused: string };

const LOCAL = new Set(["localhost", "127.0.0.1", "[::1]"]);

/** An absolute URL that is https — http for this machine only — with no credentials, query or fragment; or null. */
function address(value: string): URL | null {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  const safe = url.protocol === "https:" || (url.protocol === "http:" && LOCAL.has(url.hostname));
  return safe && !url.username && !url.password && !url.search && !url.hash && !value.includes("#") && !value.includes("?") ? url : null;
}

export function readSignIn(env: Env): Settings {
  const value = (name: string) => env[name]?.trim() ?? "";
  const names = ["DOMUVAI_AUTH_ISSUER", "DOMUVAI_AUTH_CLIENT_ID", "DOMUVAI_AUTH_CLIENT_SECRET", "DOMUVAI_SESSION_SECRET", "DOMUVAI_WEB_URL"];
  const missing = names.filter((name) => !value(name));
  if (missing.length) return { refused: `not set: ${missing.join(", ")}` };

  const issuer = value("DOMUVAI_AUTH_ISSUER");
  if (!address(issuer) || issuer.endsWith("/")) return { refused: "DOMUVAI_AUTH_ISSUER is not an https address (http for this machine only), or ends in a slash" };
  const web = address(value("DOMUVAI_WEB_URL"));
  if (!web || web.pathname !== "/") return { refused: "DOMUVAI_WEB_URL is not this site's own https address (http for this machine only), without a path" };
  if (value("DOMUVAI_SESSION_SECRET").length < 32) return { refused: "DOMUVAI_SESSION_SECRET is shorter than 32 characters" };

  return {
    signIn: {
      issuer,
      clientId: value("DOMUVAI_AUTH_CLIENT_ID"),
      clientSecret: value("DOMUVAI_AUTH_CLIENT_SECRET"),
      sessionSecret: value("DOMUVAI_SESSION_SECRET"),
      webUrl: web.origin,
    },
  };
}

/**
 * Where a person goes once signed in: the path they asked for, if it is a path on this site — never another site's
 * address, however it is spelled (`//host`, `/\host`, `/.//host`, a scheme, a control character) — and never the
 * sign-in routes themselves, however those are spelled. Anything else is the console's first page. What is checked
 * is the path as a browser will read it, after its dot segments are resolved — not the text that was asked for.
 */
export const HOME = "/portfolio";
export function returnPath(wanted: string | null | undefined): string {
  if (!wanted || !/^\/(?![/\\])[^\\\u0000-\u001f\u007f]*$/.test(wanted)) return HOME;
  let url: URL;
  try {
    url = new URL(wanted, "https://this.invalid");
  } catch {
    return HOME;
  }
  let spelled: string;
  try {
    spelled = decodeURIComponent(url.pathname).toLowerCase();
  } catch {
    return HOME;
  }
  if (url.origin !== "https://this.invalid" || url.pathname.includes("//") || /[/\\]{2}|^\/auth(\/|$)/.test(spelled)) return HOME;
  return url.pathname + url.search;
}
