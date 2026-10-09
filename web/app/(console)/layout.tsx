import { Suspense, type ReactNode } from "react";
import CatalogueFooter from "./CatalogueFooter";
import SignedIn from "./SignedIn";
import "./console.css";

/**
 * The console frame (ADR-011). The sidebar differs by context — firm-wide vs a single
 * entrance — so each nested group ((firm)/, entrance/) supplies its own sidebar; this layout
 * owns the flex shell, the shared stylesheet, and the strip every console screen ends in: who is signed in, and the
 * rule catalogue's version (PM-SYS-010).
 */
export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return (
    <div className="console">
      <div className="console-row">{children}</div>
      {/* the strip's two ends each wait on the API by themselves: a slow answer holds an empty strip, never the screen */}
      <div className="console-foot">
        <Suspense>
          <SignedIn />
        </Suspense>
        <Suspense>
          <CatalogueFooter />
        </Suspense>
      </div>
    </div>
  );
}
