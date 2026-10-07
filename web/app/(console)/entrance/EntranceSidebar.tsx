"use client";

import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";

// Firm-level shortcuts sit above the entrance's own sections. A "#" has no screen behind it yet.
// The entrance's own screens carry `?entrance=` on, so the sidebar stays on the entrance being looked at.
const FIRM: { label: string; href: string; due?: string }[] = [
  { label: "Портфейл", href: "/portfolio" },
  { label: "Задължения", href: "/debts" },
  { label: "Съответствие на фирмата", href: "#" },
];

const ENTRANCE: { label: string; href: string; due?: string }[] = [
  { label: "Статутен календар", href: "/entrance" },
  { label: "Обекти и идеални части", href: "#" },
  { label: "Начисления", href: "/entrance/charges" },
  { label: "Каса и фонд", href: "/entrance/fund" },
  { label: "Общи събрания", href: "/assembly" },
  { label: "Задължения", href: "/debts" },
  { label: "Доставчици и договори", href: "#" },
  { label: "Документи", href: "#" },
  { label: "Сигнали и ремонти", href: "#" },
];

function Item({ label, href, due, active }: { label: string; href: string; due?: string; active: boolean }) {
  return (
    <Link href={href} className={`nav-item${active ? " active" : ""}`}>
      <span>{label}</span>
      {due && <span className="due">{due}</span>}
    </Link>
  );
}

export default function EntranceSidebar() {
  const pathname = usePathname();
  const asked = useSearchParams().getAll("entrance");
  const entrance = asked.length === 1 ? asked[0] : null;      // a repeated one names no entrance, as the screens read it
  const own = (href: string) => (entrance && href.startsWith("/entrance") ? `${href}?entrance=${encodeURIComponent(entrance)}` : href);
  return (
    <aside className="sidebar">
      <div className="sidebar-brand">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" style={{ color: "#14584A", flex: "none" }}>
          <path d="M3 3.5h18v3.4H3z" />
          <path d="M3 10.3h13.2v3.4H3z" />
          <path d="M3 17.1h8.4v3.4H3z" />
        </svg>
        <span>Етаж</span>
      </div>

      <div className="sidebar-section">Фирма</div>
      {FIRM.map((i) => (
        <Item key={i.label} {...i} active={i.href !== "#" && pathname === i.href} />
      ))}

      <div className="sidebar-section">Вход</div>
      {ENTRANCE.map((i) => (
        <Item key={i.label} {...i} href={own(i.href)} active={i.href !== "#" && pathname === i.href} />
      ))}

      <div className="sidebar-user">
        <div className="avatar">МП</div>
        <div className="who">
          Мария Петрова
          <br />
          <span className="firm">Домоуправител Про ООД</span>
        </div>
      </div>
    </aside>
  );
}
