#!/usr/bin/env python3
"""Poll the sources in sources.yml, hash what comes back, report what changed.

Detects only. Writes no rule and no constant (PM-LAW-001).

    python3 watch.py --once
    python3 watch.py --once --source dv_issues

State lives in state.json: {source_id: {"hash": ..., "seen": ..., "misses": 0}}

TODO before first real run: each `kind: index` source needs one selector,
filled in after looking at the page once. Guessing them here would produce a
scraper that silently returns nothing — which PM-LAW-008 exists to prevent.
"""
from __future__ import annotations
import argparse, hashlib, json, re, sys, time
from datetime import datetime, timezone
from pathlib import Path

try:
    import yaml, requests
except ImportError:
    sys.exit("pip install pyyaml requests")

ROOT = Path(__file__).parent
STATE = ROOT / "state.json"
ARCHIVE = ROOT / "archive"          # PM-LAW-004 — keep what you saw, and when
UA = "zues-law-watch/1.0 (+contact: ops@example.com)"


def load_state() -> dict:
    return json.loads(STATE.read_text()) if STATE.exists() else {}


def save_state(st: dict) -> None:
    STATE.write_text(json.dumps(st, ensure_ascii=False, indent=2))


def fetch(url: str, timeout: int = 30) -> str:
    r = requests.get(url, headers={"User-Agent": UA}, timeout=timeout)
    r.raise_for_status()
    r.encoding = r.encoding or "utf-8"
    return r.text


def strip_html(html: str) -> str:
    html = re.sub(r"(?is)<(script|style).*?</\1>", " ", html)
    return re.sub(r"\s+", " ", re.sub(r"(?s)<[^>]+>", " ", html)).strip()


def archive(source_id: str, body: str) -> Path:
    ARCHIVE.mkdir(exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    path = ARCHIVE / f"{source_id}-{stamp}.txt"
    path.write_text(body, encoding="utf-8")
    return path


def check(src: dict, keywords: list[str], state: dict) -> dict:
    sid = src["id"]
    prev = state.get(sid, {"hash": None, "misses": 0})
    try:
        raw = fetch(src["url"])
    except Exception as e:                       # PM-LAW-008
        prev["misses"] = prev.get("misses", 0) + 1
        state[sid] = prev
        return {"source": sid, "status": "UNREACHABLE",
                "misses": prev["misses"], "error": str(e)}

    text = strip_html(raw)
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    hits = [k for k in keywords if k.lower() in text.lower()]
    changed = prev["hash"] is not None and prev["hash"] != digest
    first = prev["hash"] is None

    path = archive(sid, text) if (changed or first) else None
    state[sid] = {"hash": digest, "misses": 0,
                  "seen": datetime.now(timezone.utc).isoformat()}

    return {"source": sid,
            "status": "FIRST_RUN" if first else ("CHANGED" if changed else "SAME"),
            "keywords": hits,
            "archived": str(path) if path else None,
            "auto_apply": bool(src.get("auto_apply")),
            "affects": src.get("affects", [])}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=str(ROOT / "sources.yml"))
    ap.add_argument("--source", help="run one source only")
    ap.add_argument("--once", action="store_true")
    a = ap.parse_args()

    cfg = yaml.safe_load(Path(a.config).read_text(encoding="utf-8"))
    state = load_state()
    sources = [s for s in cfg["sources"] if not a.source or s["id"] == a.source]

    results = [check(s, cfg["keywords"], state) for s in sources]
    save_state(state)

    limit = cfg.get("alerting", {}).get("unreachable_runs_before_alert", 3)
    for r in results:
        if r["status"] == "UNREACHABLE":
            level = "ALERT" if r["misses"] >= limit else "warn"
            print(f"[{level}] {r['source']} unreachable x{r['misses']}: {r['error'][:90]}")
        elif r["status"] in ("CHANGED", "FIRST_RUN"):
            kw = ", ".join(r["keywords"]) or "no keyword hit"
            print(f"[{r['status']}] {r['source']} — {kw}")
            print(f"          archived: {r['archived']}")
            if r["auto_apply"]:
                print(f"          scheduled value; propose a new dated row for {r['affects']}")
            else:
                print( "          open a task: identify the changed articles, then run")
                print( "          python3 impact.py \"чл. NN\"")
        else:
            print(f"[same]    {r['source']}")

    # Nothing here writes a rule or a constant. That is PM-LAW-001.
    return 0


if __name__ == "__main__":
    sys.exit(main())
