import { Suspense, type ReactNode } from "react";
import CatalogueFooter from "./CatalogueFooter";
import "./console.css";

/**
 * The console frame (ADR-011). The sidebar differs by context — firm-wide vs a single
 * entrance — so each nested group ((firm)/, entrance/) supplies its own sidebar; this layout
 * owns the flex shell, the shared stylesheet, and the footer every console screen ends in: the rule
 * catalogue's version (PM-SYS-010).
 */
export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return (
    <div className="console">
      <div className="console-row">{children}</div>
      {/* the footer waits on the API by itself: a slow answer holds an empty strip, never the screen */}
      <Suspense fallback={<div className="console-foot" />}>
        <CatalogueFooter />
      </Suspense>
    </div>
  );
}
