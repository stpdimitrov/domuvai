import type { ReactNode } from "react";
import "./console.css";

/**
 * The console frame (ADR-011). The sidebar differs by context — firm-wide vs a single
 * entrance — so each nested group ((firm)/, entrance/) supplies its own sidebar; this layout
 * only owns the flex shell and the shared stylesheet.
 */
export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return <div className="console">{children}</div>;
}
