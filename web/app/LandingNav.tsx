"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

const SECTIONS = ["platform", "who", "pricing", "company"] as const;
const LABELS: Record<string, string> = { platform: "Платформа", who: "За кого", pricing: "Цени", company: "Фирмата" };

function Logo({ size = 24 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" style={{ color: "#17191A", flex: "none" }}>
      <path d="M3 3.5h18v3.4H3z" /><path d="M3 10.3h13.2v3.4H3z" /><path d="M3 17.1h8.4V20.5H3z" />
    </svg>
  );
}

/** The sticky landing nav: transparent over the hero, solid after scroll; a scroll-spy underline on
 * the current section; and a full-screen menu below 880px. `Вход` opens the console (no login screen yet). */
export default function LandingNav() {
  const [scrolled, setScrolled] = useState(false);
  const [active, setActive] = useState("");
  const [menu, setMenu] = useState(false);
  const [mobile, setMobile] = useState(false);

  useEffect(() => {
    const onResize = () => setMobile(window.innerWidth < 880);
    const onScroll = () => {
      const y = window.scrollY;
      const hero = document.getElementById("top");
      const h = hero ? hero.offsetHeight : 700;
      let a = "";
      for (const id of SECTIONS) {
        const el = document.getElementById(id);
        if (el && el.offsetTop <= y + 140) a = id;
      }
      if (y + window.innerHeight >= document.documentElement.scrollHeight - 4 && y > h) a = "company";
      setScrolled(y > h - 80);
      setActive(a);
    };
    onResize();
    onScroll();
    window.addEventListener("resize", onResize);
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      window.removeEventListener("resize", onResize);
      window.removeEventListener("scroll", onScroll);
    };
  }, []);

  useEffect(() => {
    document.body.style.overflow = menu ? "hidden" : "";
    return () => {
      document.body.style.overflow = "";
    };
  }, [menu]);

  const navBg = scrolled || menu ? "#E7E6E1" : "transparent";
  const navLine = scrolled ? "#DEDDD9" : "transparent";

  return (
    <>
      <nav
        style={{
          position: "fixed", top: 0, left: 0, right: 0, zIndex: 50,
          display: "flex", alignItems: "center", justifyContent: "space-between",
          height: 68, padding: "0 clamp(20px,4vw,56px)", boxSizing: "border-box",
          background: navBg, borderBottom: `1px solid ${navLine}`, transition: "background .25s,border-color .25s",
        }}
      >
        <a href="#top" style={{ display: "flex", alignItems: "center", gap: 10, color: "#17191A" }}>
          <Logo />
          <span style={{ font: "600 16px/1 'IBM Plex Sans'", letterSpacing: "-0.02em", color: "#17191A" }}>Етаж</span>
        </a>

        {!mobile && (
          <div style={{ display: "flex", alignItems: "center", gap: 32 }}>
            {SECTIONS.map((id) => (
              <a key={id} href={`#${id}`} className="ln-navlink" style={{ padding: "6px 0", borderBottom: `1px solid ${active === id ? "#14584A" : "transparent"}`, font: "400 14px/1 'IBM Plex Sans'", color: active === id ? "#17191A" : "#5C605E" }}>
                {LABELS[id]}
              </a>
            ))}
          </div>
        )}

        {!mobile && (
          <div style={{ display: "flex", alignItems: "center", gap: 24 }}>
            <Link href="/portfolio" className="ln-link" style={{ font: "500 14px/1 'IBM Plex Sans'" }}>Вход</Link>
            <a href="#demo" className="ln-solid" style={{ padding: "10px 20px", font: "500 14px/1 'IBM Plex Sans'", borderRadius: 8, whiteSpace: "nowrap" }}>Заявете демо</a>
          </div>
        )}

        {mobile && (
          <button type="button" aria-label="Меню" onClick={() => setMenu(true)} style={{ display: "flex", alignItems: "center", justifyContent: "center", width: 44, height: 44, marginRight: -10, border: 0, background: "transparent", color: "#17191A", cursor: "pointer" }}>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M4 7h16" /><path d="M4 12h16" /><path d="M4 17h16" /></svg>
          </button>
        )}
      </nav>

      {menu && (
        <div style={{ position: "fixed", inset: 0, zIndex: 60, display: "flex", flexDirection: "column", background: "#E7E6E1", padding: "0 20px 24px", boxSizing: "border-box", overflowY: "auto" }}>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", height: 68, flex: "none" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <Logo />
              <span style={{ font: "600 16px/1 'IBM Plex Sans'", letterSpacing: "-0.02em" }}>Етаж</span>
            </div>
            <button type="button" aria-label="Затвори" onClick={() => setMenu(false)} style={{ display: "flex", alignItems: "center", justifyContent: "center", width: 44, height: 44, marginRight: -10, border: 0, background: "transparent", color: "#17191A", cursor: "pointer" }}>
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M6 6l12 12" /><path d="M18 6L6 18" /></svg>
            </button>
          </div>
          <div style={{ display: "flex", flexDirection: "column", marginTop: 24, borderTop: "1px solid #DEDDD9" }}>
            {SECTIONS.map((id, i) => (
              <a key={id} href={`#${id}`} onClick={() => setMenu(false)} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", height: 64, borderBottom: "1px solid #DEDDD9", fontFamily: "Literata, Georgia, serif", fontSize: 28, letterSpacing: "-0.02em", color: "#17191A" }}>
                {LABELS[id]}<span style={{ font: "400 12px/1 'IBM Plex Mono', monospace", color: "#5C605E" }}>0{i + 1}</span>
              </a>
            ))}
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 12, marginTop: "auto", paddingTop: 40 }}>
            <a href="#demo" onClick={() => setMenu(false)} className="ln-solid" style={{ display: "flex", alignItems: "center", justifyContent: "center", height: 52, font: "500 16px/1 'IBM Plex Sans'", borderRadius: 10 }}>Заявете демо</a>
            <Link href="/portfolio" onClick={() => setMenu(false)} className="ln-outline" style={{ display: "flex", alignItems: "center", justifyContent: "center", height: 52, font: "500 16px/1 'IBM Plex Sans'", borderRadius: 10 }}>Вход в системата</Link>
          </div>
        </div>
      )}
    </>
  );
}
