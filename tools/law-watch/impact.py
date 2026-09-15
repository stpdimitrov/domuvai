#!/usr/bin/env python3
"""Map a changed statute article to the rules, files and tests it affects.

Usage:
    python3 impact.py "чл. 51"
    python3 impact.py "чл. 15" "чл. 17" --code ../src
"""
import argparse, json, os, re, sys
from pathlib import Path

ART = re.compile(r"чл\.\s*(\d+)\s*([а-я](?![а-я]))?")   # чл. 51 / чл. 46б, but NOT the "а" of "ал."
RANGE = re.compile(r"чл\.\s*(\d+)\s*[–—-]\s*(\d+)")   # чл. 48–50


def articles_of(source: str) -> set[str]:
    """Every ЗУЕС article a rule's source string refers to."""
    if "ЗУЕС" not in source and "чл." not in source:
        return set()
    out: set[str] = set()
    for lo, hi in RANGE.findall(source):
        out.update(str(n) for n in range(int(lo), int(hi) + 1))
    for num, suffix in ART.findall(source):
        out.add(num + (suffix or ""))
    return out


def load_rules(path: Path) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8"))["rules"]


def normalise(arg: str) -> str:
    m = ART.search(arg)
    if not m:
        return arg.strip()
    return m.group(1) + (m.group(2) or "")


def grep_code(rule_ids: list[str], root: Path) -> dict[str, list[str]]:
    """Find files tagged `Rule: PM-XXX-000`. Empty until the repo exists."""
    hits: dict[str, list[str]] = {rid: [] for rid in rule_ids}
    if not root or not root.exists():
        return hits
    for p in root.rglob("*"):
        if not p.is_file() or p.suffix not in {".kt", ".py", ".sql"}:
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for rid in rule_ids:
            if rid in text:
                hits[rid].append(str(p))
    return hits


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("articles", nargs="+", help='e.g. "чл. 51" "чл. 15"')
    ap.add_argument("--rules", default="../rules.json")
    ap.add_argument("--code", default=None, help="repo root to grep for rule tags")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    rules = load_rules(Path(a.rules))
    wanted = {normalise(x) for x in a.articles}

    affected = [r for r in rules if articles_of(r["source"]) & wanted]
    ids = [r["id"] for r in affected]
    files = grep_code(ids, Path(a.code)) if a.code else {}

    if a.json:
        print(json.dumps({"articles": sorted(wanted), "rules": affected, "files": files},
                         ensure_ascii=False, indent=2))
        return 0

    print(f"Changed: {', '.join('чл. ' + x for x in sorted(wanted))}")
    print(f"Affected rules: {len(affected)}\n")
    for r in affected:
        flag = "  [VERIFY]" if not r["verified"] else ""
        print(f"{r['id']}  {r['modality']:<8} {r['source']}{flag}")
        print(f"   {r['rule'][:120]}")
        for f in files.get(r["id"], []):
            print(f"   file: {f}")
        print()
    if not affected:
        print("No rule cites that article. Either the change does not touch us,")
        print("or the catalogue is missing a citation — check before assuming the first.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BrokenPipeError:
        os._exit(0)
