import type { CSSProperties } from "react";
import HeroVideo from "./HeroVideo";

// The three ЗУЕС pillars the platform runs per entrance.
const FEATURES = [
  { n: "01", label: "Календар по ЗУЕС" },
  { n: "02", label: "Начисления и каса" },
  { n: "03", label: "Общи събрания" },
];

const heading: CSSProperties = {
  fontFamily: "Literata, Georgia, serif",
  fontWeight: 400,
  letterSpacing: "-0.025em",
  color: "#17191A",
};

export default function LandingPage() {
  return (
    <div style={{ minHeight: "100vh", background: "#E7E6E1", overflowX: "hidden", color: "#17191A" }}>
      <nav
        className="nav"
        style={{
          position: "fixed",
          top: 0,
          left: 0,
          right: 0,
          zIndex: 50,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "20px 56px",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" style={{ color: "#17191A", flex: "none" }}>
            <path d="M3 3.5h18v3.4H3z" />
            <path d="M3 10.3h13.2v3.4H3z" />
            <path d="M3 17.1h8.4V20.5H3z" />
          </svg>
          <span style={{ font: "600 16px/1 'IBM Plex Sans'", letterSpacing: "-0.02em", color: "#17191A" }}>Етаж</span>
        </div>
        <div className="nav-links" style={{ display: "flex", alignItems: "center", gap: 32 }}>
          <a className="nav-link" href="#platform">Платформа</a>
          <a className="nav-link" href="#who">За кого</a>
          <a className="nav-link" href="#pricing">Цени</a>
          <a className="nav-link" href="#company">Фирмата</a>
        </div>
        <a className="btn-demo" href="#demo" style={{ padding: "10px 20px", font: "500 14px/1 'IBM Plex Sans'", borderRadius: 8 }}>
          Заявете демо
        </a>
      </nav>

      <section
        style={{
          position: "relative",
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          overflow: "hidden",
          minHeight: "100vh",
          boxSizing: "border-box",
        }}
      >
        <HeroVideo />

        <div
          style={{
            position: "relative",
            zIndex: 10,
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            textAlign: "center",
            padding: "128px 24px 72px",
          }}
        >
          <h1
            style={{
              margin: 0,
              fontFamily: "Literata, Georgia, serif",
              fontWeight: 400,
              fontSize: "clamp(40px, 6.6vw, 92px)",
              lineHeight: 1.08,
              letterSpacing: "-0.035em",
              color: "#17191A",
            }}
          >
            Нито един
            <br />
            пропуснат срок.
          </h1>
          <p style={{ maxWidth: 430, margin: "28px 0 0", font: "300 16px/1.65 'IBM Plex Sans'", color: "#5C605E" }}>
            Платформа за професионални домоуправители — законови срокове, начисления и общи събрания, водени по вход, а
            не по сграда.
          </p>
          <a
            className="btn-demo"
            href="#demo"
            style={{ marginTop: 36, padding: "14px 30px", font: "500 15px/1 'IBM Plex Sans'", borderRadius: 10 }}
          >
            Заявете демо
          </a>
        </div>

        <div style={{ position: "relative", zIndex: 10, marginTop: "auto", width: "100%", maxWidth: 1040, padding: "0 24px", boxSizing: "border-box" }}>
          <div
            className="panel"
            style={{
              background: "rgba(247,246,243,0.92)",
              backdropFilter: "blur(8px)",
              WebkitBackdropFilter: "blur(8px)",
              border: "1px solid #DEDDD9",
              borderBottom: 0,
              padding: "56px 48px 0",
              boxShadow: "0 -1px 24px rgba(23,25,26,0.06)",
            }}
          >
            <div className="what-grid" style={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) minmax(0,1fr)", gap: 64 }}>
              <div>
                <div style={{ font: "500 11px/1 'IBM Plex Sans'", textTransform: "uppercase", letterSpacing: "0.2em", color: "#84887F" }}>
                  Какво правим?
                </div>
                <h2 style={{ ...heading, margin: "14px 0 0", fontSize: "clamp(24px, 2.6vw, 38px)", lineHeight: 1.15 }}>
                  Срокове, пари и кворум
                  <br />
                  на едно място
                </h2>
              </div>
              <div style={{ display: "flex", alignItems: "flex-end" }}>
                <p style={{ margin: 0, font: "400 15px/1.65 'IBM Plex Sans'", color: "#5C605E" }}>
                  Създадена за фирми, които управляват 40–80 входа. Всеки вход със собствен календар по ЗУЕС, собствена
                  каса и фонд, собствено събрание — и нито едно евро, смесено с чужд вход.
                </p>
              </div>
            </div>

            <div style={{ marginTop: 36, height: 1, background: "#DEDDD9", width: "100%" }} />

            <div className="feature-grid" style={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0,1fr))", gap: 12, marginTop: 20 }}>
              {FEATURES.map((f) => (
                <a key={f.n} className="feature-card" href="#platform">
                  <span style={{ font: "400 14px/1 'IBM Plex Sans'", whiteSpace: "nowrap" }}>
                    <span style={{ color: "#9A9E96", fontVariantNumeric: "tabular-nums" }}>{f.n}</span>
                    <span style={{ margin: "0 8px", color: "#B6B9B2" }}>/</span>
                    <span style={{ fontWeight: 500 }}>{f.label}</span>
                  </span>
                  <svg
                    className="arrow"
                    width="16"
                    height="16"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.8"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    style={{ flex: "none" }}
                  >
                    <path d="M5 12h14" />
                    <path d="m13 6 6 6-6 6" />
                  </svg>
                </a>
              ))}
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
