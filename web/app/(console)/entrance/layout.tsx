import type { ReactNode } from "react";
import EntranceSidebar from "./EntranceSidebar";

/** A single entrance's screens — the entrance-scoped navigation. */
export default function EntranceLayout({ children }: { children: ReactNode }) {
  return (
    <>
      <EntranceSidebar />
      <main className="console-main">{children}</main>
    </>
  );
}
