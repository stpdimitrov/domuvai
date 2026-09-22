"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

// The firm-level navigation. Only Портфейл is a real route today; the rest are placeholders
// activated as each screen lands (WEB-02…). `due` badges are overdue-count flags (ЗУЕС).
const NAV: { label: string; href: string; count?: string; due?: string }[] = [
  { label: "Портфейл", href: "/portfolio", count: "62" },
  { label: "Задължения", href: "#", due: "31" },
  { label: "Начисления", href: "#" },
  { label: "Календар на сроковете", href: "#", due: "9" },
  { label: "Доставчици", href: "#" },
  { label: "Съответствие на фирмата", href: "#" },
  { label: "Документи", href: "#" },
  { label: "Екип", href: "#" },
];

const RECENT = ["ул. Шипка 14, вх. Б", "бул. Витоша 102, вх. А", "ж.к. Младост 3, бл. 318, вх. 2"];

export default function FirmSidebar() {
  const pathname = usePathname();

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
      {NAV.map((item) => {
        const active = item.href !== "#" && pathname === item.href;
        return (
          <Link key={item.label} href={item.href} className={`nav-item${active ? " active" : ""}`}>
            <span>{item.label}</span>
            {item.count && <span className="count">{item.count}</span>}
            {item.due && <span className="due">{item.due}</span>}
          </Link>
        );
      })}

      <div className="sidebar-section">Последно отваряни</div>
      {RECENT.map((addr) => (
        <a key={addr} href="#" className="recent">
          {addr}
        </a>
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
