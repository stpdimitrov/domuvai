export const dynamic = "force-dynamic";   // behind the sign-in like every path (PM-DEBT-011): never prerendered

/**
 * Rule: PM-SYS-015 — the resident surface installs on the phone's home screen and opens at its home. A route, not
 * `app/manifest.ts`: Next links that one without credentials, so where people sign in the gate would refuse it. The
 * resident layout links this one with `crossOrigin="use-credentials"`.
 */
export function GET() {
  return Response.json(
    { name: "Етаж", short_name: "Етаж", lang: "bg", display: "standalone", start_url: "/my", background_color: "#f2f1ee", theme_color: "#14584a" },
    { headers: { "content-type": "application/manifest+json", "cache-control": "no-store" } },
  );
}
