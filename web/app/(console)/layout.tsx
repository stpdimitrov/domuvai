import type { ReactNode } from "react";
import Sidebar from "./Sidebar";
import "./console.css";

/**
 * The manager console shell (ADR-011): the firm sidebar plus a main column each screen fills
 * with its own top bar and body. A thin view — every number it shows is decided by `api`.
 */
export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return (
    <div className="console">
      <Sidebar />
      <main className="console-main">{children}</main>
    </div>
  );
}
