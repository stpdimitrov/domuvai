import type { ReactNode } from "react";
import FirmSidebar from "./FirmSidebar";

/** Firm-wide screens (the portfolio and its siblings) — the firm navigation. */
export default function FirmLayout({ children }: { children: ReactNode }) {
  return (
    <>
      <FirmSidebar />
      <main className="console-main">{children}</main>
    </>
  );
}
