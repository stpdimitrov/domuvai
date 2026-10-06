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
6. WEB-16 (PM-FEE-010): for the seeded entrance with a business unit, the charges screen shows the API's refusal and a
   field for the assembly's multiple; given one, it shows the API's own figures — for two different multiples. No
   figure is typed here: the check takes the ones the API accepts.
7. WEB-19: the portfolio lists every seeded entrance with the API's figures, its filter leaves out what it should, and
   its links carry the entrance and the date.

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
    ("GET", "/api/money/entrances/{entranceId}/operating-account"): lambda e: (f"/api/money/entrances/{e}/operating-account", None),
    ("GET", "/api/money/entrances/{entranceId}/journal"): lambda e: (
        f"/api/money/entrances/{e}/journal?from=2026-09-01&to=2026-09-30", None),
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
    "/entrance/fund?entrance={e}&period=2026-09": [
        "BG87 UNCR 7000 1512 9981 02 · титуляр Мария Иванова",                    # the fund's account
        "BG44 UNCR 7000 1512 3456 78 · титуляр Мария Иванова",                    # the operating one
        # what was paid into the operating account — named so, never a balance, while outflows are not recorded (WEB-17)
        "Само постъпления", "Постъпления по сметката €70,00 Поети задължения — Разполагаемо —", "не салдото в банката",
        # September's journal (PM-FUND-005, PM-PMC-008): the run, then the payments — each journal's debits, then its credits
        "Дневник · септември 2026",
        "01.09.2026 Начисление Вземания от обекти · 18 записвания €705,00 Приход · поддръжка на общи части €45,00 "
        "Приход · управление €60,00 Приход · фонд „Ремонт“ €600,00",
        "10.09.2026 Плащане Банка · оперативна сметка €40,00 Вземания от обекти €40,00",
        "10.09.2026 Плащане Банка · сметка на фонда €300,00 Аванси от обекти €168,00 Вземания от обекти €132,00",   # an overpayment
        "7 статии · всяка с равни дебит и кредит €1.825,00 €1.825,00",
        "Салдо €570,00", "Поети, неплатени €425,00", "Разполагаемо €145,00",       # the fund card (PM-FUND-009)
        "аварийно, без решение: теч от покрива над ап. 6 след бурята €125,00 Поето чака плащане",
        "€99,00 Оттеглено оттеглено", "· изпълнителят се отказа",                  # the cancelled one, and why
        "€0,00 + €1.050,00 − €480,00 = €570,00 €570,00 €0,00 Съвпада с банката 2 · €425,00",   # the handover
    ],
    "/entrance/fund?entrance={e}&period=2026-09&account=operating": [             # only the journals touching that account, whole
        "10.09.2026 Плащане Банка · оперативна сметка €30,00 Вземания от обекти €30,00",
        "2 статии · всяка с равни дебит и кредит €70,00 €70,00",
    ],
    "/entrance/fund?entrance={e}&period=2026-09&account=fund": [                  # the four payments into the fund's account
        "10.09.2026 Плащане Банка · сметка на фонда €250,00 Аванси от обекти €131,50 Вземания от обекти €118,50",
        "4 статии · всяка с равни дебит и кредит €1.050,00 €1.050,00",
    ],
    "/entrance/fund?entrance={e}&period=2026-09&account=cash": [                  # September has journals, none through the cash box
        "Няма статии в дневника за септември 2026 по тази сметка.", "0 статии · всяка с равни дебит и кредит €0,00 €0,00",
    ],
    "/entrance/fund?entrance={e}&period=2026-08": ["Дневник · август 2026", "Няма статии в дневника за август 2026."],
    "/debts?asOf=2026-09-30": [                              # the firm sidebar still carries the design's sample names
        "2 обекта дължат, 2 в просрочие · 1 вход от 2 · към 30.09.2026",   # the second is the seed's business entrance: nothing issued, nothing owed
        "ул. Шипка 14, вх. Б · 2 обекта с неплатено €156,50",  # the entrance's total (PM-DEBT-001)
        "ап. 5 Надя Тодорова €80,00 — €80,00 — 15 дни — —",            # owed, the oldest debt's days overdue (PM-DEBT-002)
        "ап. 6 Петър Георгиев €76,50 — €76,50 — 15 дни — —",
    ],
    "/debts?asOf=2026-09-05": [                              # before the due day: unpaid, but nothing overdue
        "6 обекта дължат, 0 в просрочие · 1 вход от 2 · към 05.09.2026",
        "ап. 1 Иван Петров €132,00 — €132,00 — в срок — —",
    ],
    # After October's run (WEB-18, PM-DEBT-001): the units that overpaid in September hold an advance against the new
    # debt — unpaid, the advance, and what is owed after it, each the API's. One covered in full is listed and owes nothing.
    "/debts?asOf=2026-10-20": [
        "3 обекта дължат, 3 в просрочие · 1 вход от 2 · към 20.10.2026",
        "ул. Шипка 14, вх. Б · 6 обекта с неплатено €861,50 − покрито с аванси €468,50 €393,00",   # the entrance, after the advances
        "сортирано по неплатено ↓",                                           # the API's order: largest unpaid first —
        "€183,00 — €183,00 — 35 дни — — ап. 1 Иван Петров €132,00",           # — not by what is owed after the advance
        "Общо 6 обекта с неплатено · 3 дължат",
        "ап. 5 Надя Тодорова €200,00 — €200,00 — 35 дни — —",                # no advance: all of it owed
        "ап. 1 Иван Петров €132,00 €168,00 €0,00 — покрито с аванс — —",       # covered in full
        "ап. 3 Мария Иванова €105,00 €95,00 €10,00 — 5 дни — —",              # covered in part
    ],
    "/debts?asOf=2026-10-10": [                              # October's debt not yet due: covered in part, and on time
        "ап. 3 Мария Иванова €105,00 €95,00 €10,00 — в срок — —",
        "3 обекта дължат, 2 в просрочие",                    # ап. 5 and ап. 6 still owe September
    ],
    # WEB-19 — the portfolio: each entrance with what it owes after the advances as of the date (PM-DEBT-001) and its
    # fund's balance beside what is available (PM-FUND-009), the API's figures. What is not kept yet is a dash.
    "/portfolio?asOf=2026-09-30": [
        "2 входа · 2 сгради · 8 обекта · към 30.09.2026",
        "1 вход дължи към 30.09.2026 · 0 входа с отрицателен фонд",
        "Всички 2 С дължимо 1 Отрицателен фонд 0",
        "ул. Шипка 14, вх. Б · 6 об. — — — €156,50 €570,00 €145,00 — —",
        "ул. Шипка 16, вх. А · 2 об. — — — €0,00 няма сметка — —",          # nothing issued; no fund account — said, not an error
        "Общо 2 входа показани всички",
    ],
    "/portfolio?asOf=2026-10-20": ["ул. Шипка 14, вх. Б · 6 об. — — — €393,00 €570,00 €145,00 — —"],   # owed moves with the date
    "/portfolio?asOf=2026-09-30&show=owing": ["ул. Шипка 14, вх. Б · 6 об.", "Общо 2 входа показани 1 от 2"],
    "/portfolio?asOf=2026-09-30&show=fund": ["Няма вход за този филтър.", "Общо 2 входа показани 0 от 2"],
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


BUSINESS_LABEL = "ул. Шипка 16, вх. А"                  # the entrance tools/seed_demo.py gives a business unit


def eur(minor):
    """An amount as the console writes it (web/lib/console.ts)."""
    return f"€{minor // 100:,}".replace(",", ".") + f",{minor % 100:02d}"


def charges_multiple(api, web):
    """
    Rule: PM-FEE-010 — the multiple a business unit pays is the assembly's, so the charges screen holds none: without
    one it shows the API's own refusal, which names the unit, and offers a field for it; with one it shows what the API
    computes with that figure. The check types no figure either — it asks the API which ones it accepts, and reads
    the screen for the lowest and the highest, so a screen that ignores the field, or sends a figure of its own, shows
    the wrong totals for at least one. A figure the API refuses, or one that is not a whole number, is shown as
    refused, with the field still there for the same entrance and period.
    """
    status, raw = fetch(api, "/api/registry/entrances")
    entrance = next((e["id"] for e in json.loads(raw) if e["label"] == BUSINESS_LABEL), None) if status == 200 else None
    if not entrance:
        return [f"PM-FEE-010: the seed's business entrance ({BUSINESS_LABEL}) is not registered — nothing was checked"]
    body = {"period": "2026-09", "legalDate": "2026-09-01", "lines": DEMO_BASIS}
    preview = lambda extra: fetch(api, f"/api/money/entrances/{entrance}/charge-runs/preview", {**body, **extra})
    previews = {m: preview({"businessMultiplier": m}) for m in range(1, 10)}
    accepted = {m: json.loads(raw) for m, (status, raw) in previews.items() if status == 200}
    refused = {m: json.loads(raw).get("error", "") for m, (status, raw) in previews.items() if status == 400}
    none_status, none_raw = preview({})
    if len(accepted) < 2 or not refused or none_status != 400:
        return [f"PM-FEE-010: of the multiples 1–9 the API accepts {sorted(accepted)} and answers a run with none HTTP {none_status} — "
                f"two accepted, one refused and a refusal for none are needed to tell the screens apart"]

    failures = []
    screen = f"/entrance/charges?period=2026-09&entrance={entrance}"
    hidden = lambda page, name, value: re.search(rf'<input(?=[^>]*\bname="{name}")(?=[^>]*\bvalue="{re.escape(value)}")[^>]*>', page)
    links = lambda page, m: all(f'href="?period={p}&entrance={entrance}{m}"' in page.replace("&amp;", "&") for p in ("2026-08", "2026-10"))
    asks = [("no multiple", screen, json.loads(none_raw).get("error", ""), "")]
    asks += [(f"the refused multiple {m}", f"{screen}&multiple={m}", refused[m], f"&multiple={m}") for m in (min(refused), max(refused))]
    asks += [("a multiple that is not a whole number", f"{screen}&multiple=3.5", "3.5", "")]
    for how, url, says, kept in asks:
        status, page = fetch(web, url)
        text = visible_text(page)
        if status != 200 or "API отказа изчислението" not in text or not says or says not in text:
            failures.append(f"PM-FEE-010: {url} ({how}) does not show the refusal {says!r}")
        if 'name="multiple"' not in page or not hidden(page, "entrance", entrance) or not hidden(page, "period", "2026-09"):
            failures.append(f"PM-FEE-010: {url} ({how}) does not offer the field for the multiple with its entrance and period")
        if not links(page, kept):
            failures.append(f"PM-FEE-010: {url} ({how}) — a period link drops the entrance or the multiple")
    if "магазин" not in asks[0][2]:
        failures.append(f"PM-FEE-010: the API's refusal for a run with no multiple does not name the unit: {asks[0][2]!r}")
    for m in (min(accepted), max(accepted)):
        status, page = fetch(web, f"{screen}&multiple={m}")
        text, run = visible_text(page), accepted[m]
        wanted = [f"Общо за начисляване {eur(run['totalMinor'])}", f"×{m} — посочена ръчно"] + [f"{eur(c['totalMinor'])}" for c in run["charges"]]
        missing = [want for want in wanted if want not in text]
        if status != 200 or missing or "API отказа" in text:
            failures.append(f"PM-FEE-010: {screen}&multiple={m} does not show the API's figures for ×{m} — missing {missing}")
        if not links(page, f"&multiple={m}") or not hidden(page, "entrance", entrance) or not hidden(page, "period", "2026-09"):
            failures.append(f"PM-FEE-010: {screen}&multiple={m} — a period link or the field drops the entrance, the period or the multiple")
    print(f"{'BAD' if failures else 'ok '} PM-FEE-010 the charges screen: the API's refusal with a field for the multiple, then the API's "
          f"figures for ×{min(accepted)} and ×{max(accepted)}")
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

    failures += charges_multiple(api, web)

    # WEB-19 — the portfolio's filter leaves out what it should, and its links carry the entrance and the date
    status, page = fetch(web, "/portfolio?asOf=2026-09-30&show=owing")
    kept_out = "ул. Шипка 16" not in visible_text(page)
    failures += [] if kept_out else ["/portfolio?show=owing: lists an entrance that owes nothing"]
    wanted = [f"/entrance/fund?entrance={entrance}", "/debts?asOf=2026-09-30", "/portfolio?asOf=2026-09-30&show=fund", "/portfolio?asOf=2026-09-30"]
    dropped = [link for link in wanted if f'href="{link}"' not in page.replace("&amp;", "&")]
    failures += [f"/portfolio: no link to {link}" for link in dropped]
    print(f"{'ok ' if status == 200 and kept_out and not dropped else 'BAD'} /portfolio  the filter and {len(wanted) - len(dropped)}/{len(wanted)} links")

    # WEB-17 — the fund screen's links keep what the others chose: the journal's month, its account, the register's filter
    status, page = fetch(web, f"/entrance/fund?entrance={entrance}&period=2026-09&account=operating&status=paid")
    wanted = [f"?entrance={entrance}&status=paid&period=2026-08&account=operating",      # the month before
              f"?entrance={entrance}&status=paid&period=2026-09&account=fund",           # another account
              f"?entrance={entrance}&status=committed&period=2026-09&account=operating"]  # another state of the register
    dropped = [link for link in wanted if f'href="{link}"' not in page.replace("&amp;", "&")]
    failures += [f"/entrance/fund: no link to {link} — a link drops the month, the account or the register's filter" for link in dropped]
    print(f"{'ok ' if status == 200 and not dropped else 'BAD'} /entrance/fund  {len(wanted) - len(dropped)}/{len(wanted)} links keep the month, the account and the filter")
    # … and a debit stands in the debit column, a credit in the credit column: the text alone cannot tell them apart
    cells = re.sub(r"<!--.*?-->", "", page, flags=re.S)
    sides = {"debit": 'Банка · оперативна сметка</div><div class="num">€40,00</div><div class="num"></div>',     # the money came in
             "credit": 'Вземания от обекти</div><div class="num"></div><div class="num">€40,00</div>'}          # the debt went down
    misplaced = [side for side, cell in sides.items() if cell not in cells]
    failures += [f"/entrance/fund: the journal shows no {side} of €40,00 in the {side} column" for side in misplaced]
    print(f"{'ok ' if not misplaced else 'BAD'} /entrance/fund  a debit in the debit column, a credit in the credit column")
    # … and the fund's payout, dated the day the seed ran: the screen is asked for the month the API dates it in
    status, raw = fetch(api, f"/api/money/entrances/{entrance}/journal?from=2026-01-01&to=2100-12-31&account=BANK:REPAIR_RENEWAL")
    payouts = [j for j in json.loads(raw)["journals"] if j.get("source") == "FUND_PAYOUT"] if status == 200 else []
    payout = "Изплащане от фонда Разход · фонд „Ремонт“ €480,00 Банка · сметка на фонда €480,00"
    shown = payouts and payout in visible_text(fetch(web, f"/entrance/fund?entrance={entrance}&period={payouts[0]['valueDate'][:7]}")[1])
    failures += [] if shown else [f"/entrance/fund: the month of the fund's payout does not show {payout!r}"]
    print(f"{'ok ' if shown else 'BAD'} /entrance/fund  the fund's payout in the journal of its month")

    for failure in failures:
        print(f"  ✗ {failure}")
    print(f"e2e: {'FAILED — ' + str(len(failures)) + ' problem(s)' if failures else 'the chain holds'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
