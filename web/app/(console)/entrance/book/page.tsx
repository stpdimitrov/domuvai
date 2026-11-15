import type { Metadata } from "next";
import Link from "next/link";
import { API_URL } from "@/lib/api/client";
import { entranceAt } from "@/lib/console";
import { sofiaToday } from "@/lib/payment";
import { unanswered } from "@/lib/refusal";
import BookForm from "./BookForm";
import NoEntrance from "@/app/(console)/NoEntrance";

export const metadata: Metadata = { title: "Книга на етажната собственост — Етаж" };

const BODY = { margin: "16px 24px", font: "400 13px/1.6 'IBM Plex Sans'" } as const;

/**
 * The Book of the Condominium read, and who read it (WEB-44). The page itself reads nothing of the book: the form asks
 * for a purpose and each read is the form's (Rule: PM-BOOK-007). Who may read is the API's (Rule: PM-BOOK-006,
 * PM-SEC-001), its refusal shown in its words.
 */
// Rule: PM-BOOK-001, PM-BOOK-006, PM-BOOK-007, PM-SEC-001
export default async function BookPage({ searchParams }: { searchParams: Promise<{ entrance?: string }> }) {
  const view = await entranceAt((await searchParams).entrance);
  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{view.kind === "ok" ? view.entrance.label : "—"}</span>
          </span>
          <span className="meta">Книга на етажната собственост</span>
        </div>
      </div>
      {view.kind === "ok" ? (
        <BookForm entrance={view.entrance.id} today={sofiaToday()} />
      ) : (
        <div style={BODY}>
          {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
          {view.kind === "unlisted" && unanswered(view, "списъка на входовете")}
          {view.kind === "none" && <NoEntrance />}
          {view.kind === "unknown" && <>Входът <code>{view.id}</code> не е регистриран.</>}
        </div>
      )}
    </>
  );
}
