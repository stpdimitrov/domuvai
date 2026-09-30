#!/usr/bin/env python3
"""
E2E-01 — the whole chain, checked: the real Next.js server, over the real API, over Postgres.

1. Every API call the live screens make is listed in CALLS. The list is compared with the calls found in web/, so
   a screen that starts calling something new fails here until the check covers it; a call the scan cannot read —
   a path that is not a string literal, a destructured client, a raw fetch — fails too.
2. Each call's real response is validated against the published contract (docs/api/openapi.json, ADR-013),
   formats included (uuid, date, date-time).
3. Each live screen renders the seeded entrance's figures — each with its label or its row, so the same amount
   elsewhere on the page cannot stand in for it — and no error state.

Expects the entrance tools/seed_demo.py creates. Usage: check_e2e.py --api URL --web URL --entrance ID
"""
import argparse
import html
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parent.parent
SPEC = json.loads((ROOT / "docs/api/openapi.json").read_text(encoding="utf-8"))

# The basis the charges screen posts (web/app/(console)/entrance/charges/page.tsx, DEMO_BASIS).
DEMO_BASIS = [
    {"stream": "MANAGEMENT", "key": "PER_PERSON", "decisionId": "GA-2026-03-12-4", "rateMinor": 600},
    {"stream": "MAINTENANCE", "key": "PER_PERSON", "decisionId": "GA-2026-03-12-4", "rateMinor": 450},
    {"stream": "REPAIR_FUND", "key": "BY_IDEAL_PARTS", "decisionId": "GA-2026-03-12-4", "totalMinor": 60_000},
]

# (method, contract path) → how the live screens call it for the seeded entrance: the URL and the body.
CALLS = {
    ("GET", "/api/registry/entrances"): lambda e: ("/api/registry/entrances", None),
    ("GET", "/api/registry/entrances/{entranceId}/units"): lambda e: (f"/api/registry/entrances/{e}/units", None),
    ("GET", "/api/registry/entrances/{entranceId}/owners"): lambda e: (f"/api/registry/entrances/{e}/owners?on=2026-09-01", None),
    ("POST", "/api/money/entrances/{entranceId}/charge-runs/preview"): lambda e: (
        f"/api/money/entrances/{e}/charge-runs/preview", {"period": "2026-09", "legalDate": "2026-09-01", "lines": DEMO_BASIS}),
    ("GET", "/api/money/entrances/{entranceId}/fund"): lambda e: (f"/api/money/entrances/{e}/fund", None),
    ("GET", "/api/money/entrances/{entranceId}/fund-accounts"): lambda e: (f"/api/money/entrances/{e}/fund-accounts", None),
    ("GET", "/api/money/entrances/{entranceId}/fund/handover-statements"): lambda e: (
        f"/api/money/entrances/{e}/fund/handover-statements", None),
}

# Each live screen, for the seeded entrance, and what it must show — figures that follow from tools/seed_demo.py,
# each with its label or its whole row (the visible text, tags and empty cells collapsed to single spaces).
SCREENS = {
    "/entrance/charges?period=2026-09&entrance={e}": [
        "ул. Шипка 14, вх. Б",
        "ап. 2 Георги Колев, Елена Колева 2 — — 16,2500% €12,00 €9,00 — €97,50 €118,50",   # co-owners; a child not counted
        "Общо 6 обекта 10 100,0000% €60,00 €45,00 — €600,00 €705,00",                      # per-stream totals
        "Общо за начисляване €705,00",
    ],
    "/entrance/fund?entrance={e}": [
        "BG87 UNCR 7000 1512 9981 02 · титуляр Мария Иванова",                    # the fund's account
        "BG44 UNCR 7000 1512 3456 78 · титуляр Мария Иванова",                    # the operating one
        "Салдо €570,00", "Поети, неплатени €425,00", "Разполагаемо €145,00",       # the fund card (PM-FUND-009)
        "аварийно, без решение: теч от покрива над ап. 6 след бурята €125,00 Поето чака плащане",
        "€99,00 Оттеглено оттеглено", "· изпълнителят се отказа",                  # the cancelled one, and why
        "€0,00 + €1.050,00 − €480,00 = €570,00 €570,00 €0,00 Съвпада с банката 2 · €425,00",   # the handover
    ],
}
# The words of every error state the live screens have. None may appear for the seeded entrance.
ERRORS = [
    "Бекендът не отговаря", "Бекендът не подаде", "API отказа", "не е регистриран", "Няма регистриран вход",
    "няма регистрирана сметка на фонд", "няма регистрирана оперативна сметка",
]

METHODS = "GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE"
WEB_CALL = re.compile(rf"""\bapi\.({METHODS})\(\s*["']([^"'`$]+)["']""")
ANY_CALL = re.compile(rf"\bapi\.({METHODS})\(")
UNREADABLE = [re.compile(r"\bfetch\("), re.compile(rf"\{{[^}}]*\b({METHODS})\b[^}}]*\}}\s*=\s*api\b")]


def web_calls():
    """
    Every (method, path) the web's code calls through the typed client — read from the source, never typed — and
    every place a call cannot be read: a path that is not a plain string literal, a destructured client, a raw fetch.
    """
    found, unreadable = set(), []
    for path in (ROOT / "web").rglob("*"):
        if path.suffix not in (".ts", ".tsx") or path.name.endswith(".d.ts") or {"node_modules", ".next"} & set(path.parts):
            continue
        source = re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S)
        source = re.sub(r"(?m)^\s*//.*$", "", source)                   # a commented-out call is not a call
        literal = WEB_CALL.findall(source)
        found |= set(literal)
        where = path.relative_to(ROOT)
        if len(ANY_CALL.findall(source)) > len(literal):
            unreadable.append(f"{where}: an API call whose path is not a plain string literal")
        unreadable += [f"{where}: {pattern.pattern!r} — call the API through the typed client, path as a literal"
                       for pattern in UNREADABLE if pattern.search(source)]
    return found, unreadable


def fetch(base, url, body=None):
    request = urllib.request.Request(
        base + url, method="POST" if body is not None else "GET",
        data=json.dumps(body).encode() if body is not None else None, headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")
    except urllib.error.URLError as e:              # nothing listening: reported, never a traceback
        return 0, str(e.reason)


def response_schema(method, path, status):
    responses = SPEC["paths"][path][method.lower()]["responses"]
    schema = responses[str(status)]["content"]["application/json"]["schema"]
    defs = SPEC["components"]["schemas"]
    as_defs = lambda node: json.loads(json.dumps(node).replace("#/components/schemas/", "#/$defs/"))
    return {**as_defs(schema), "$defs": as_defs(defs)}


def visible_text(page):
    page = re.sub(r"<!--.*?-->", "", page, flags=re.S)
    page = re.sub(r"<(script|style)\b.*?</\1>", " ", page, flags=re.S)
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", page)))


def main():
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--api", required=True)
    parser.add_argument("--web", required=True)
    parser.add_argument("--entrance", required=True)
    args = parser.parse_args()
    api, web, entrance = args.api.rstrip("/"), args.web.rstrip("/"), args.entrance
    failures = []

    (called, unreadable), checked = web_calls(), set(CALLS)
    failures += unreadable
    failures += [f"the web calls {m} {p}, which this check does not validate — add it to CALLS" for m, p in sorted(called - checked)]
    failures += [f"CALLS lists {m} {p}, which no screen calls any more — remove it" for m, p in sorted(checked - called)]

    for (method, path), call in CALLS.items():
        url, body = call(entrance)
        status, raw = fetch(api, url, body)
        if status not in (200, 201):
            failures.append(f"{method} {path}: HTTP {status} {raw[:200]}")
            continue
        validator = Draft202012Validator(response_schema(method, path, status), format_checker=Draft202012Validator.FORMAT_CHECKER)
        errors = list(validator.iter_errors(json.loads(raw)))
        grouped = {}                                   # one line per field, however many rows repeat it
        for e in errors:
            where = "/".join("*" if isinstance(part, int) else str(part) for part in e.absolute_path) or "(root)"
            grouped[(where, e.message)] = grouped.get((where, e.message), 0) + 1
        failures += [f"{method} {path}: {where} — {message} (×{n})" for (where, message), n in sorted(grouped.items())]
        print(f"{'ok ' if not errors else 'BAD'} {method:4} {path}  {len(errors)} contract violation(s)")

    for screen, expected in SCREENS.items():
        url = screen.format(e=entrance)
        status, page = fetch(web, url)
        text = visible_text(page)
        missing = [want for want in expected if want not in text]
        shown = [word for word in ERRORS if word in text]
        failures += [f"{url}: HTTP {status}"] if status != 200 else []
        failures += [f"{url}: does not show {want!r}" for want in missing]
        failures += [f"{url}: shows an error state ({word!r})" for word in shown]
        print(f"{'ok ' if status == 200 and not missing and not shown else 'BAD'} {url}  {len(expected) - len(missing)}/{len(expected)} figures")

    for failure in failures:
        print(f"  ✗ {failure}")
    print(f"e2e: {'FAILED — ' + str(len(failures)) + ' problem(s)' if failures else 'the chain holds'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
