import { Suspense, type ReactNode } from "react";
import EntranceSidebar from "./EntranceSidebar";

/** A single entrance's screens — the entrance-scoped navigation. */
export default function EntranceLayout({ children }: { children: ReactNode }) {
  return (
    <>
      {/* it reads the query string, which a prerendered page has none of */}
      <Suspense>
        <EntranceSidebar />
      </Suspense>
      <main className="console-main">{children}</main>
    </>
  );
}
