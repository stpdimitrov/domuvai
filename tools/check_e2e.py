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
4. PM-DEBT-011: a second server from the same build, not switched on (web/lib/consoleSwitch.ts), serves no console
   path — each in the build's route manifest, never typed, answers a load, a client navigation, a prefetch and a HEAD
   with the 404 a missing page gets — and its landing links to none of them.
5. #79: the landing sends a demo request nowhere by itself, so it never says one arrived. The first server has an
   address to write to (web/lib/contact.ts) and offers the form with it; the second has none, a third has a value
   that is not an address, and they offer neither.

Expects the entrance tools/seed_demo.py creates, and the build in web/.next (or --next-dir).
Usage: check_e2e.py --api URL --web URL --closed-web URL --bad-contact-web URL --contact ADDRESS --entrance ID
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
    ("GET", "/api/money/entrances/{entranceId}/arrears"): lambda e: (f"/api/money/entrances/{e}/arrears?asOf=2026-09-30", None),
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
    "/debts?asOf=2026-09-30": [                              # the firm sidebar still carries the design's sample names
        "2 обекта с неплатено, 2 в просрочие · 1 вход от 1 · към 30.09.2026",
        "ул. Шипка 14, вх. Б · 2 обекта с неплатено €156,50",  # the entrance's total (PM-DEBT-001)
        "ап. 5 Надя Тодорова €80,00 — 15 дни — —",            # owed, the oldest debt's days overdue (PM-DEBT-002)
        "ап. 6 Петър Георгиев €76,50 — 15 дни — —",
    ],
    "/debts?asOf=2026-09-05": [                              # before the due day: unpaid, but nothing overdue
        "6 обекта с неплатено, 0 в просрочие · 1 вход от 1 · към 05.09.2026",
        "ап. 1 Иван Петров €132,00 — в срок — —",
    ],
}
# The words of every error state the live screens have. None may appear for the seeded entrance.
ERRORS = [
    "Бекендът не отговаря", "Бекендът не подаде", "API отказа", "не е регистриран", "Няма регистриран вход",
    "няма регистрирана сметка на фонд", "няма регистрирана оперативна сметка", "не се заредиха", "не се зареди ",
    "незаредени", "незареден",
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
        if where.as_posix() != "web/lib/api/client.ts":             # the typed client itself is where fetch belongs
            unreadable += [f"{where}: {pattern.pattern!r} — call the API through the typed client, path as a literal"
                           for pattern in UNREADABLE if pattern.search(source)]
    return found, unreadable


# Each way a browser asks for a page: a load, a client navigation, a prefetch, a HEAD.
ASKS = [("a load", "GET", {}), ("a client navigation", "GET", {"RSC": "1"}),
        ("a prefetch", "GET", {"RSC": "1", "Next-Router-Prefetch": "1"}), ("a HEAD", "HEAD", {})]


def routes(next_dir):
    """Every path the build serves, from Next's own route manifest — its routing rules, not a copy of them: page or route."""
    manifest = json.loads((next_dir / "app-path-routes-manifest.json").read_text(encoding="utf-8"))
    return {route: entry.rsplit("/", 1)[-1] for entry, route in manifest.items() if route != "/_not-found"}


def console_closed(web, closed_web, next_dir):
    """
    Rule: PM-DEBT-011 — the console names debtors and what they owe, and there is no sign-in yet, so a server not
    switched on serves none of it: every path but the landing answers each way of asking with the 404 a missing page
    gets, and the landing links to none of them. The switched-on server serves each static page (200) and each route
    handler (not 404), and its landing does link in — so a 404 or a missing link on the other is the switch's doing.
    A dynamic path is checked closed only: open, an unknown id may rightly be a 404.
    """
    served = routes(next_dir)
    console = sorted(r for r in served if r != "/")
    if not console:
        return ["PM-DEBT-011: the build's route manifest lists no console path — nothing was checked"]
    url = lambda route: re.sub(r"\[+\.*([^\]]+)\]+", r"\1", route)            # a dynamic segment: any value will do
    nowhere = {name: fetch(closed_web, "/no-such-page", method=method, headers=h) for name, method, h in ASKS}
    failures = []
    for route in console:
        for name, method, h in ASKS:
            got = fetch(closed_web, url(route), method=method, headers=h)
            if got[0] != 404 or got != nowhere[name]:
                failures.append(f"PM-DEBT-011: {url(route)} asked as {name} where the console is not switched on answers "
                                f"HTTP {got[0]} — expected the 404 a missing page gets")
        if "[" in route or "(" in route:
            continue
        opened = fetch(web, url(route))[0]
        if (opened != 200) if served[route] == "page" else (opened == 404):
            failures.append(f"PM-DEBT-011: {url(route)} answers HTTP {opened} where the console is switched on — its 404 proves nothing")
    links = lambda landing: [r for r in console if f'href="{url(r)}"' in landing]
    (status, landing), (opened, landing_open) = fetch(closed_web, "/"), fetch(web, "/")
    failures += [f"PM-DEBT-011: the landing answers HTTP {status} where the console is not switched on"] if status != 200 else []
    failures += [f"PM-DEBT-011: the landing links to {r}, which this server does not serve" for r in links(landing)]
    if opened != 200 or not links(landing_open):
        failures.append("PM-DEBT-011: the switched-on landing links to no console path — a link missing from the other proves nothing")
    print(f"{'BAD' if failures else 'ok '} PM-DEBT-011 not switched on: {len(console)} console paths × {len(ASKS)} ways asked "
          f"answer the 404 a missing page gets, the landing links to none")
    return failures


CLAIM = "Заявката е приета"                            # what the form said while it sent nothing (#79)
SENDS_NOTHING = "Тази страница не изпраща нищо сама"   # what it says once the letter is written out
NOT_YET = "Още не приемаме заявки през сайта"


def demo_request(web, without_address, contact, next_dir):
    """
    #79 — the landing sends a demo request nowhere by itself: with an address to write to (DOMUVAI_CONTACT_EMAIL) it
    offers the form and names the address in both places that had a placeholder, and the visitor sends the letter;
    with none — unset, or a value that is not an address — it offers no form and no address, and says so.

    What the form shows after submitting is never served, only shipped, so it is read from the build: no file says a
    request was accepted, and one says the page sends nothing by itself. That is as far as text can be checked
    without a browser — an acceptance worded some other way would pass.
    """
    failures = []
    status, page = fetch(web, "/")
    named = page.count(f'href="mailto:{contact}"')
    if status != 200 or "<form" not in page or named < 2:
        failures.append(f"#79: with {contact} to write to, the landing offers no form or names the address in {named} place(s), not 2")
    for how, base in without_address.items():
        status, page = fetch(base, "/")
        if status != 200 or "<form" in page or "mailto:" in page:
            failures.append(f"#79: with {how}, the landing still offers a form or an address")
        if NOT_YET not in visible_text(page):
            failures.append(f"#79: with {how}, the landing does not say that requests are not taken yet")
    built = [p.read_text(encoding="utf-8") for p in sorted(next_dir.rglob("*.js")) if "cache" not in p.parts]
    says = lambda text: sum(text in file or text.encode("unicode_escape").decode().lower() in file.lower() for file in built)
    failures += [f"#79: {says(CLAIM)} built file(s) say {CLAIM!r} — the page sends a request nowhere"] if says(CLAIM) else []
    failures += [f"#79: no built file says {SENDS_NOTHING!r} — the form no longer tells the visitor to send the letter"] if not says(SENDS_NOTHING) else []
    print(f"{'BAD' if failures else 'ok '} #79 the demo request: a form and the address where there is one, neither where there "
          f"is none, no claim of acceptance in {len(built)} built files")
    return failures


def fetch(base, url, body=None, method=None, headers=None):
    request = urllib.request.Request(
        base + url, method=method or ("POST" if body is not None else "GET"),
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **(headers or {})},
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
    parser.add_argument("--closed-web", required=True, help="the same build, not switched on (PM-DEBT-011), with no address to write to (#79)")
    parser.add_argument("--bad-contact-web", required=True, help="the same build, started with a DOMUVAI_CONTACT_EMAIL that is not an address (#79)")
    parser.add_argument("--contact", required=True, help="the address --web was started with, DOMUVAI_CONTACT_EMAIL (#79)")
    parser.add_argument("--entrance", required=True)
    parser.add_argument("--next-dir", type=Path, default=ROOT / "web/.next", help="the build both servers run")
    args = parser.parse_args()
    api, web, entrance = args.api.rstrip("/"), args.web.rstrip("/"), args.entrance
    failures = console_closed(web, args.closed_web.rstrip("/"), args.next_dir)
    without_address = {"no address to write to": args.closed_web.rstrip("/"),
                       "a value that is not an address": args.bad_contact_web.rstrip("/")}
    failures += demo_request(web, without_address, args.contact, args.next_dir)

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
