import type { ReactNode } from "react";
import "./resident.css";

/**
 * Rule: PM-SYS-015 — the resident surface's frame: a phone layout of its own, not the console's (STAGE1 §6.9), held to
 * the payload budget in `web/budget.json` by `npm run budget`. Body text 17 px, tap targets 48 px, a bottom tab bar
 * of at most four items (UIPROMPTS Prompt 1). No client script: a link is a link. The bar lists only screens that
 * exist — today the home.
 */
const TABS = [{ href: "/my", label: "Начало" }];

export default function ResidentLayout({ children }: { children: ReactNode }) {
  return (
    <div className="resident">
      <link rel="manifest" href="/manifest.webmanifest" crossOrigin="use-credentials" />
      <main className="resident-main">{children}</main>
      <nav className="resident-tabs" aria-label="Раздели">
        {TABS.slice(0, 4).map((t) => (
          <a key={t.href} href={t.href}>{t.label}</a>
        ))}
      </nav>
    </div>
  );
}
