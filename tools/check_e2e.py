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
4. PM-DEBT-011: a second server from the same build, with nothing set (web/lib/auth/gate.ts), serves no console
   path — each in the build's route manifest, never typed, answers a load, a client navigation, a prefetch and a HEAD
   with the 404 a missing page gets — and its landing links to none of them. A third, with sign-in configured, serves
   a person who is not signed in the landing and the sign-in routes only; and no page is prerendered.
5. #79: the landing sends a demo request nowhere by itself, so it never says one arrived. The first server has an
   address to write to (web/lib/contact.ts) and offers the form with it; the second has none, a third has a value
   that is not an address, and they offer neither.
6. WEB-16 (PM-FEE-010): for the seeded entrance with a business unit, the charges screen shows the API's refusal and a
   field for the assembly's multiple; given one, it shows the API's own figures — for two different multiples. No
   figure is typed here: the check takes the ones the API accepts.
7. WEB-19: the portfolio lists every seeded entrance with the API's figures, its filter leaves out what it should, and
   its links carry the entrance and the date.
8. WEB-20: the entrance screen shows the entrance asked for — who is past the deadline to declare for the book, and its
   accounts — nothing of the design's sample, and refuses an entrance that is not registered.
9. WEB-21: every live screen answers a request that gives one of its parameters twice.
10. WEB-22 (PM-SYS-010): every screen under the console's layout has one footer, with the catalogue and engine
    versions the API serves.

11. WEB-23 (PM-BOOK-003, PM-BOOK-012): the compliance screen shows each entrance's overdue declarations and the month's
    headcount check as the API gives them, says so where no run was issued, and nothing of the design's sample.
12. WEB-25 … WEB-29 (PM-GA-001, PM-GA-002, PM-GA-004, PM-GA-006, PM-GA-007, PM-GA-012, PM-GA-013, PM-GA-014, PM-GOV-005, PM-VOTE-004): the entrance's assembly
    screen — whether this year's assembly was held, in the API's count; in CI, an assembly convened through the page's
    own form, listed among the entrance's assemblies and shown as the API stored it, an agenda item put on it through
    the page's own form and shown bound to the API's majority, one whose majority waits on counsel refused in the API's
    words, its quorum meter in the API's figures, an office not held refused; the draft moved through the page's own
    form and shown as stored; a posting act co-signed by no owner refused in the API's words, the form kept; the notice
    posted through the form with its photograph, the assembly NOTICED; its first sitting weighed through the page's form
    weeks before its hour, refused in the API's words, nothing weighed; the noticed assembly moved, a draft again whose
    notice the page says is void and which offers no weighing.
13. AUTH-03 (ADR-011), in CI: a person signs in as a browser does — through the issuer's own form — and holds an
    httpOnly cookie and no token; every screen above is then read signed in, from an api that is closed; the console
    names the person, with the login the api read from the web's bearer; sign-out ends the session at the web and at
    the issuer.
14. WEB-35 (ADR-012, PM-SEC-001): the intake screen — a fee sheet uploaded through its own form shows every column the
    API profiled, each preset to the API's field, and the columns it could not place; the mapping confirmed through the
    second form, one column corrected, shown as confirmed. The controller's profile is the API's 403 naming PM-BOOK-005.
15. WEB-36 (ADR-012, PM-FEE-014, PM-FEE-012, PM-SEC-001): on the same screen, the confirmed sheet dry-run with a tariff
    typed in euros shows the API's own verdict for the same request — every count and each differing bill, to the cent;
    recording it shows the import's id, which the API then reads with the same verdict.
16. WEB-37 (PM-SEC-001, PM-FEE-014): the import's page shows it as the API reads it — status, counts, content hash, what
    blocked it; a commit of a sheet that does not reproduce is the API's refusal in its words; a sheet that reproduces is
    recorded and committed through the page (COMMITTING), and its revert through the import's page is the API's answer.
17. WEB-38 (PM-FEE-012, PM-FEE-015, PM-SEC-001): the charges screen's issue form offers, as decisions to cite, exactly the
    entrance's PASSED decisions the assembly API stores (none: it says no run can be issued); an issue posted through it
    citing a decision that is not one is refused by the web, and the API then holds no run for that period. The issue
    call is validated against its 409 (the seeded period is billed) with the board's token, and refused for the controller.

Expects the entrance tools/seed_demo.py creates, and the build in web/.next (or --next-dir).
Usage, in CI — a real issuer, the api closed (E2E_PASSWORD and E2E_API_TOKEN in the environment):
  check_e2e.py --api URL --web URL --open-web URL --closed-web URL --bad-contact-web URL --contact ADDRESS --entrance ID
               --issuer URL --user LOGIN --subject ID
Usage, on a machine with no issuer — --web told to run without sign-in, the api open; sign-in itself is not checked:
  check_e2e.py --api URL --web URL --closed-web URL --bad-contact-web URL --signin-web URL --contact ADDRESS --entrance ID
"""
import argparse
import datetime
import html
import http.client
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from zoneinfo import ZoneInfo

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parent.parent
SPEC = json.loads((ROOT / "docs/api/openapi.json").read_text(encoding="utf-8"))

# The basis the charges screen posts (web/app/(console)/entrance/charges/page.tsx, DEMO_BASIS).
DEMO_BASIS = [
    {"stream": "MANAGEMENT", "key": "PER_PERSON", "decisionId": "GA-2026-03-12-4", "rateMinor": 600},
    {"stream": "MAINTENANCE", "key": "PER_PERSON", "decisionId": "GA-2026-03-12-4", "rateMinor": 450},
    {"stream": "REPAIR_FUND", "key": "BY_IDEAL_PARTS", "decisionId": "GA-2026-03-12-4", "totalMinor": 60_000},
]

def sofia_year():
    """The calendar year Sofia is in — the one the assembly screen asks about (PM-GA-001)."""
    return datetime.datetime.now(ZoneInfo("Europe/Sofia")).year


# (method, contract path) → how the live screens call it for the seeded entrance: the URL and the body.
# The operations about one assembly need an assembly, and the seed convenes none: assembly_screen convenes one
# through the page and validates these itself, signed in (WEB-25). Here they are listed so that the web calls nothing
# this check does not cover.
ON_AN_ASSEMBLY = {
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/notice-posting"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/schedule"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/sittings"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/attendance"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/attendance/{attendanceId}/identity-verification"),
    ("GET", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}"),
    ("GET", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/quorum"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/votes"),
    ("GET", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/votes"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/decision"),
    ("GET", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/decision"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/absentee-ballots"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/agenda/{itemId}/absentee-merge"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/protocol-posting"),
    ("GET", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/attendance"),
    ("POST", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/proxies"),   # appointed through the page by the seeded owner (WEB-48b; proxies_on)
    ("GET", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/proxies"),
}
# The calls that keep an entrance's accounts or enter its book — for the manager and the board only (G-3, G-1, PM-SEC-001).
KEPT_BY_THE_BOARD = {("POST", "/api/money/entrances/{entranceId}/charge-runs/preview"),
                     ("POST", "/api/money/entrances/{entranceId}/charge-runs"),
                     ("POST", "/api/intake/entrances/{entranceId}/fee-sheet/profile"),
                     ("POST", "/api/intake/entrances/{entranceId}/fee-sheet/dry-run"),
                     ("POST", "/api/intake/entrances/{entranceId}/imports"),
                     ("GET", "/api/intake/imports/{id}"),
                     ("POST", "/api/intake/imports/{id}/commit"),
                     ("POST", "/api/intake/imports/{id}/revert"),
                     ("POST", "/api/money/entrances/{entranceId}/payments"),
                     ("POST", "/api/money/entrances/{entranceId}/fund-accounts"),
                     ("POST", "/api/money/entrances/{entranceId}/fund/disbursements"),
                     ("POST", "/api/money/entrances/{entranceId}/fund/disbursements/{disbursementId}/pay"),
                     ("POST", "/api/money/entrances/{entranceId}/fund/disbursements/{disbursementId}/cancel"),
                     ("POST", "/api/money/entrances/{entranceId}/fund/handover-statements"),
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/household"),
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/household/{memberId}/end"),
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/titles"),
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/animals"),
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/animals/{animalId}/end"),
                     ("POST", "/api/registry/entrances/{entranceId}/book/declarations"),
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/absences")}
# A firm's own layout (WEB-35, ADR-012): Bulgarian headers the API places, one it cannot ("м2"), one nobody maps.
INTAKE_SHEET = "Ап.,Идеални части,Живущи,Такса,м2,Бележка\n1,50.000,2,1250,61.40,\n2,50.000,3,1480,58.10,ремонт\n"
# The tariff it is dry-run with (WEB-36): €2,50 a person for management — the sheet's own fees are not that, so bills differ.
INTAKE_RUN = {"period": "2026-09", "legalDate": "2026-09-01",
              "lines": [{"stream": "MANAGEMENT", "key": "PER_PERSON", "decisionId": "GA-2026-03-12-4", "rateMinor": 250}]}
# A sheet that reproduces (WEB-37): €10,00 a unit for maintenance, which is what the firm billed each.
INTAKE_REPRO = "Ап.,Идеални части,Живущи,Такса\n1,50.000,2,1000\n2,50.000,3,1000\n"
INTAKE_REPRO_RUN = {"period": "2026-09", "legalDate": "2026-09-01",
                    "lines": [{"stream": "MAINTENANCE", "key": "PER_UNIT", "decisionId": "GA-2026-03-12-5", "rateMinor": 1000}]}
# The answer a CALLS entry is validated against when the call is refused by design: a revert needs a committed import,
# and the seeded entrance's book already holds its units, so no import of the check's commits (WEB-37).
REFUSED_BY_DESIGN = {("POST", "/api/intake/imports/{id}/revert"): 409,
                     ("POST", "/api/money/entrances/{entranceId}/charge-runs"): 409,   # September is seeded as billed: a bill is not reissued
                     ("POST", "/api/money/entrances/{entranceId}/payments"): 409,      # dated before the seeded payments: the demo stays as seeded
                     ("POST", "/api/money/entrances/{entranceId}/fund-accounts"): 409,  # both purposes are seeded; fund_accounts_screen checks the words
                     ("POST", "/api/money/entrances/{entranceId}/fund/disbursements"): 409,                     # an emergency above the seeded €145,00 available (PM-FUND-008)
                     ("POST", "/api/money/entrances/{entranceId}/fund/disbursements/{disbursementId}/pay"): 409,    # the seeded paid one: paid once
                     ("POST", "/api/money/entrances/{entranceId}/fund/disbursements/{disbursementId}/cancel"): 409,  # the seeded cancelled one: cancelled once
                     ("POST", "/api/money/entrances/{entranceId}/fund/handover-statements"): 400,                    # the seeded manager on both sides: the demo stays as seeded (PM-FUND-010)
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/household"): 404,                  # no unit of this id: the seeded book is not written (WEB-45; household_screen writes through the page)
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/household/{memberId}/end"): 404,   # no resident of this id either (WEB-45)
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/titles"): 404,                     # no unit of this id: the seeded titles are not written (WEB-51; units_in records one through the page)
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/animals"): 404,                    # no unit of this id: the seeded book is not written (WEB-46; animals_screen writes through the page)
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/animals/{animalId}/end"): 404,     # no animal of this id either (WEB-46)
                     ("POST", "/api/registry/entrances/{entranceId}/book/declarations"): 404,                          # no unit of this id: the seeded book is not written (WEB-47; declarations_screen files through the page)
                     ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/absences"): 404}                   # no unit of this id (WEB-46)
# Never run against the seeded demo: the retention run anonymises for good (PM-BOOK-010; the owner, 2026-10-29, WEB-47). The
# check asks it only with its own token — the controller's, which the API refuses (403) — and only where it has one.
NEVER_RUN = {("POST", "/api/registry/entrances/{entranceId}/book/retention")}
# The seeded entrance's fund account — tools/seed_demo.py's iban("UNCR70001512998102").
SEEDED_FUND_IBAN = "BG87UNCR70001512998102"
# A payment read back needs a payment the check knows: payment_screen records one through the page, last, and validates
# this itself (WEB-39). Listed here so that the web calls nothing this check does not cover.
ON_A_PAYMENT = {("GET", "/api/money/entrances/{entranceId}/payments/{paymentId}")}
# A petition is opened, read, signed and convened on by the seeded owner, through the page (WEB-48): petitions_screen checks them
# itself (the weighing it reads back is validated against the contract; the 201 bodies are shown on the page and compared there).
ON_A_PETITION = {("POST", "/api/assembly/entrances/{entranceId}/petitions"), ("GET", "/api/assembly/entrances/{entranceId}/petitions/{petitionId}"),
                 ("POST", "/api/assembly/entrances/{entranceId}/petitions/{petitionId}/signatures"),
                 ("POST", "/api/assembly/entrances/{entranceId}/petitions/{petitionId}/assembly")}
# Mandates are recorded and ended by the deployment's administrator through the page (WEB-49): mandates_screen checks them
# itself, against the API's own list. Listed here so that the web calls nothing this check does not cover.
ON_A_MANDATE = {("POST", "/api/identity/entrances/{entranceId}/mandates"), ("POST", "/api/identity/entrances/{entranceId}/mandates/{mandateId}/end"),
                ("POST", "/api/identity/logins"), ("POST", "/api/identity/logins/untie")}
# An entrance and a party are registered through the page (WEB-50): registry_screen checks them itself, against the API's own list.
# Units are registered through the page (WEB-50b): units_screen and registry_screen check them against the API's own list.
ON_A_REGISTRATION = {("POST", "/api/registry/entrances"), ("POST", "/api/registry/parties"), ("POST", "/api/registry/entrances/{entranceId}/units")}


def a_unit(entrance):
    """The entrance's first unit by designation, as the registry lists it — the one the payment checks pay for."""
    status, raw = fetch(API[0], f"/api/registry/entrances/{entrance}/units")
    units = sorted(json.loads(raw), key=lambda u: u["designation"]) if status == 200 else []
    return units[0]["id"] if units else "00000000-0000-0000-0000-000000000000"


def recorded(entrance, csv, run):
    """An import recorded with the board's token, for a call that needs one: its id, or a nil one the call then fails on."""
    status, raw = fetch(API[0], f"/api/intake/entrances/{entrance}/imports", {"csv": csv, **run}, headers=as_board())
    return json.loads(raw)["importId"] if status in (200, 201) else "00000000-0000-0000-0000-000000000000"


def fund_holder(entrance):
    """The party holding the fund's account: the one who signed the seeded disbursements (tools/seed_demo.py) — no API
    read names it otherwise; a nil one, which the call then fails on, when the fund cannot be read."""
    found, raw = fetch(API[0], f"/api/money/entrances/{entrance}/fund")
    listed = json.loads(raw)["disbursements"] if found == 200 else []
    return listed[0]["authorisedBy"] if listed else "00000000-0000-0000-0000-000000000000"


def seeded(entrance, status):
    """The seeded disbursement in [status] (tools/seed_demo.py: one paid, one cancelled), as the fund lists it — a nil id,
    which the call then fails on, when the fund cannot be read or lists none."""
    found, raw = fetch(API[0], f"/api/money/entrances/{entrance}/fund")
    listed = [d for d in json.loads(raw)["disbursements"] if d["status"] == status] if found == 200 else []
    return listed[0]["id"] if listed else "00000000-0000-0000-0000-000000000000"


def seeded_handover(entrance):
    """The first handover statement the API lists (tools/seed_demo.py issues one), its id — a nil one, which the call then fails on, when none is listed."""
    found, raw = fetch(API[0], f"/api/money/entrances/{entrance}/fund/handover-statements")
    listed = json.loads(raw) if found == 200 else []
    return listed[0]["id"] if listed else "00000000-0000-0000-0000-000000000000"


NO_ONE = "00000000-0000-0000-0000-000000000000"   # an id nothing has: a call on it is the API's 404
API = [""]   # the api's base, set in main() before CALLS is run
CALLS = {
    ("GET", "/api/identity/entrances/{entranceId}/mandates"): lambda e: (f"/api/identity/entrances/{e}/mandates", None),   # the api's own today
    ("GET", "/api/identity/me"): lambda e: ("/api/identity/me", None),
    ("GET", "/api/law/version"): lambda e: ("/api/law/version", None),
    ("GET", "/api/registry/entrances"): lambda e: ("/api/registry/entrances", None),
    ("GET", "/api/registry/entrances/{entranceId}/units"): lambda e: (f"/api/registry/entrances/{e}/units", None),
    ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/household"): lambda e: (
        f"/api/registry/entrances/{e}/units/{NO_ONE}/household", {"members": [{"isChildUnder6": False}]}),
    ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/household/{memberId}/end"): lambda e: (
        f"/api/registry/entrances/{e}/units/{NO_ONE}/household/{NO_ONE}/end", {"on": "2026-10-15"}),
    ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/titles"): lambda e: (
        f"/api/registry/entrances/{e}/units/{NO_ONE}/titles", {"partyId": NO_ONE, "titleRole": "OWN", "share": "1"}),
    ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/animals"): lambda e: (
        f"/api/registry/entrances/{e}/units/{NO_ONE}/animals", {"animals": [{"species": "куче"}]}),
    ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/animals/{animalId}/end"): lambda e: (
        f"/api/registry/entrances/{e}/units/{NO_ONE}/animals/{NO_ONE}/end", {"on": "2026-10-15"}),
    ("POST", "/api/registry/entrances/{entranceId}/units/{unitId}/absences"): lambda e: (
        f"/api/registry/entrances/{e}/units/{NO_ONE}/absences", {"absences": [{"absentFrom": "2026-11-02", "absentTo": "2026-11-09"}]}),
    ("GET", "/api/registry/entrances/{entranceId}/book"): lambda e: (f"/api/registry/entrances/{e}/book?purpose=e2e%3A%20contract", None),
    ("GET", "/api/registry/entrances/{entranceId}/book/access-log"): lambda e: (f"/api/registry/entrances/{e}/book/access-log?purpose=e2e%3A%20contract", None),
    ("POST", "/api/registry/entrances/{entranceId}/book/declarations"): lambda e: (
        f"/api/registry/entrances/{e}/book/declarations", {"unitId": NO_ONE, "partyId": NO_ONE, "kind": "ACQUISITION"}),
    ("POST", "/api/registry/entrances/{entranceId}/book/retention"): lambda e: (f"/api/registry/entrances/{e}/book/retention", None),
    ("GET", "/api/registry/entrances/{entranceId}/book/declarations/overdue"): lambda e: (
        f"/api/registry/entrances/{e}/book/declarations/overdue", None),
    ("GET", "/api/registry/entrances/{entranceId}/owners"): lambda e: (f"/api/registry/entrances/{e}/owners?on=2026-09-01", None),
    ("POST", "/api/money/entrances/{entranceId}/charge-runs/preview"): lambda e: (
        f"/api/money/entrances/{e}/charge-runs/preview", {"period": "2026-09", "legalDate": "2026-09-01", "lines": DEMO_BASIS}),
    ("POST", "/api/money/entrances/{entranceId}/charge-runs"): lambda e: (
        f"/api/money/entrances/{e}/charge-runs", {"period": "2026-09", "legalDate": "2026-09-01", "lines": DEMO_BASIS}),
    ("GET", "/api/money/entrances/{entranceId}/fund"): lambda e: (f"/api/money/entrances/{e}/fund", None),
    ("GET", "/api/money/entrances/{entranceId}/operating-account"): lambda e: (f"/api/money/entrances/{e}/operating-account", None),
    ("GET", "/api/money/entrances/{entranceId}/charge-runs/{period}/headcount"): lambda e: (
        f"/api/money/entrances/{e}/charge-runs/2026-09/headcount", None),
    ("GET", "/api/money/entrances/{entranceId}/journal"): lambda e: (
        f"/api/money/entrances/{e}/journal?from=2026-09-01&to=2026-09-30", None),
    ("GET", "/api/money/entrances/{entranceId}/fund/handover-statements"): lambda e: (
        f"/api/money/entrances/{e}/fund/handover-statements", None),
    ("GET", "/api/money/entrances/{entranceId}/fund/handover-statements/{statementId}"): lambda e: (
        f"/api/money/entrances/{e}/fund/handover-statements/{seeded_handover(e)}", None),
    ("POST", "/api/money/entrances/{entranceId}/fund/handover-statements"): lambda e: (
        f"/api/money/entrances/{e}/fund/handover-statements",
        {"handoverOn": "2026-10-01", "outgoingPartyId": fund_holder(e), "incomingPartyId": fund_holder(e), "bankBalanceMinor": 0},
        {"Idempotency-Key": str(uuid.uuid4())}),
    ("GET", "/api/money/entrances/{entranceId}/arrears"): lambda e: (f"/api/money/entrances/{e}/arrears?asOf=2026-09-30", None),
    ("GET", "/api/money/units/{unitId}/statement"): lambda e: (f"/api/money/units/{a_unit(e)}/statement", None),
    ("GET", "/api/money/units/{unitId}/arrears"): lambda e: (f"/api/money/units/{a_unit(e)}/arrears?asOf=2026-09-30", None),
    ("GET", "/api/assembly/entrances/{entranceId}/assemblies"): lambda e: (f"/api/assembly/entrances/{e}/assemblies", None),
    ("GET", "/api/assembly/entrances/{entranceId}/years/{year}"): lambda e: (f"/api/assembly/entrances/{e}/years/{sofia_year()}", None),
    ("POST", "/api/intake/entrances/{entranceId}/fee-sheet/profile"): lambda e: (
        f"/api/intake/entrances/{e}/fee-sheet/profile", {"csv": INTAKE_SHEET}),
    ("POST", "/api/intake/entrances/{entranceId}/fee-sheet/dry-run"): lambda e: (
        f"/api/intake/entrances/{e}/fee-sheet/dry-run", {"csv": INTAKE_SHEET, **INTAKE_RUN}),
    ("POST", "/api/intake/entrances/{entranceId}/imports"): lambda e: (
        f"/api/intake/entrances/{e}/imports", {"csv": INTAKE_SHEET, **INTAKE_RUN}),
    ("GET", "/api/intake/imports/{id}"): lambda e: (f"/api/intake/imports/{recorded(e, INTAKE_SHEET, INTAKE_RUN)}", None),
    ("POST", "/api/intake/imports/{id}/commit"): lambda e: (
        f"/api/intake/imports/{recorded(e, INTAKE_REPRO, INTAKE_REPRO_RUN)}/commit", {"sheet": {"csv": INTAKE_REPRO, **INTAKE_REPRO_RUN}}),
    ("POST", "/api/intake/imports/{id}/revert"): lambda e: (
        f"/api/intake/imports/{recorded(e, INTAKE_SHEET, INTAKE_RUN)}/revert", {"reason": "e2e: an import not committed"}),
    ("POST", "/api/money/entrances/{entranceId}/payments"): lambda e: (
        f"/api/money/entrances/{e}/payments", {"unitId": a_unit(e), "amountMinor": 100, "receivedInto": "CASH", "valueDate": "2026-01-01"},
        {"Idempotency-Key": str(uuid.uuid4())}),
    ("GET", "/api/money/entrances/{entranceId}/fund-accounts"): lambda e: (f"/api/money/entrances/{e}/fund-accounts", None),
    ("POST", "/api/money/entrances/{entranceId}/fund-accounts"): lambda e: (
        f"/api/money/entrances/{e}/fund-accounts", {"purpose": "OPERATING", "iban": SEEDED_FUND_IBAN, "holderName": "e2e", "holderKind": "MANAGER"}),
    ("POST", "/api/money/entrances/{entranceId}/fund/disbursements"): lambda e: (
        f"/api/money/entrances/{e}/fund/disbursements", {"amountMinor": 1_000_000, "purpose": "WORKS", "authorisedBy": fund_holder(e),
                                                         "emergencyJustification": "e2e: above what is available"}, {"Idempotency-Key": str(uuid.uuid4())}),
    ("POST", "/api/money/entrances/{entranceId}/fund/disbursements/{disbursementId}/pay"): lambda e: (
        f"/api/money/entrances/{e}/fund/disbursements/{seeded(e, 'PAID')}/pay",
        {"paidOn": "2026-10-01", "paidBy": fund_holder(e)}, {"Idempotency-Key": str(uuid.uuid4())}),
    ("POST", "/api/money/entrances/{entranceId}/fund/disbursements/{disbursementId}/cancel"): lambda e: (
        f"/api/money/entrances/{e}/fund/disbursements/{seeded(e, 'CANCELLED')}/cancel",
        {"cancelledBy": fund_holder(e), "reason": "e2e: cancelled once"}, {"Idempotency-Key": str(uuid.uuid4())}),
}

# Each live screen, for the seeded entrance, and what it must show — figures that follow from tools/seed_demo.py,
# each with its label or its whole row (the visible text, tags and empty cells collapsed to single spaces).
SCREENS = {
    # WEB-20 — the entrance: its units, who is past the deadline to declare for the book with the registry's own due
    # date (PM-BOOK-003; the seed files no declaration, so every owner since 2020 is), and its two accounts (PM-FUND-009).
    "/entrance?entrance={e}": [
        "Портфейл / ул. Шипка 14, вх. Б 6 обекта 7 просрочени декларации",
        "Просрочени декларации · 7",
        "16.01.2020 Декларация за вписване в книгата — ап. 1 чл. 7, ал. 3 ЗУЕС · собственик от 01.01.2020 Просрочена Иван Петров",
        "Декларация за вписване в книгата — ап. 2 чл. 7, ал. 3 ЗУЕС · собственик от 01.01.2020 Просрочена Георги Колев",   # a co-owner owes his own
        "Останалите срокове по ЗУЕС — отчети, покани, мандати, проверки — още не се водят в системата.",
        "Обекти 6 · 6 жил. Форма на управление общо събрание Управител — Мандат до — Последно ОС — Книга на собствениците 7 просрочени декларации",
        "Оперативна сметка BG44 UNCR 7000 1512 3456 78 €70,00 Постъпления по сметката — не салдото в банката",
        "Фонд „Ремонт и обновяване“ BG87 UNCR 7000 1512 9981 02 €570,00 Разполагаемо €145,00 — €425,00 поети, неплатени",
        "Следващо събрание Системата още не изрежда събранията на входа. Общи събрания",
    ],
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
    "/debts?asOf=2026-09-30": [
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
    "Бекендът не отговаря", "Бекендът не подаде", "API отказа", "Нямате право", "нямате право", "Входът ви не беше приет", "не е прочетен", "не са прочетени", "не е регистриран", "Няма регистриран вход",
    "няма регистрирана сметка на фонд", "няма регистрирана оперативна сметка", "не се заредиха", "не се зареди ",
    "незаредени", "незареден", "версията не е достъпна",
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
        # the typed client itself is where fetch belongs — and the one module that asks the sign-in issuer, which must
        # then never ask the API: it names neither the API's address nor a path of it
        if where.as_posix() == "web/lib/auth/oidc.ts":
            unreadable += [f"{where}: {word!r} — the module that asks the issuer must not call the API" for word in ("API_URL", "/api/") if word in source]
        elif where.as_posix() != "web/lib/api/client.ts":
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
    Rule: PM-DEBT-011 — the console names debtors and what they owe, so a server where sign-in is not configured, and
    that is not told to run without it, serves none of it: every path but the landing answers each way of asking with the 404 a missing page
    gets, and the landing links to none of them. The server told to run without sign-in serves each static page (200) and each route
    handler (not 404), and its landing does link in — so a 404 or a missing link on the other is the rule's doing.
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
                failures.append(f"PM-DEBT-011: {url(route)} asked as {name} where sign-in is not configured answers "
                                f"HTTP {got[0]} — expected the 404 a missing page gets")
        if "[" in route or "(" in route:
            continue
        opened = fetch(web, url(route))[0]
        if (opened != 200) if served[route] == "page" else (opened == 404):
            failures.append(f"PM-DEBT-011: {url(route)} answers HTTP {opened} where the console is served — its 404 proves nothing")
    links = lambda landing: [r for r in console if f'href="{url(r)}"' in landing]
    (status, landing), (opened, landing_open) = fetch(closed_web, "/"), fetch(web, "/")
    failures += [f"PM-DEBT-011: the landing answers HTTP {status} where sign-in is not configured"] if status != 200 else []
    failures += [f"PM-DEBT-011: the landing links to {r}, which this server does not serve" for r in links(landing)]
    if opened != 200 or not links(landing_open):
        failures.append("PM-DEBT-011: the open landing links to no console path — a link missing from the other proves nothing")
    print(f"{'BAD' if failures else 'ok '} PM-DEBT-011 sign-in not configured: {len(console)} console paths × {len(ASKS)} ways asked "
          f"answer the 404 a missing page gets, the landing links to none")
    return failures


def ask(base, url, method="GET", headers=None):
    """One request, as sent — no redirect followed, the path not tidied: status, the Location header, the body."""
    target = urllib.parse.urlsplit(base)
    connection = http.client.HTTPConnection(target.hostname, target.port, timeout=60)
    try:
        connection.request(method, url, headers=headers or {})
        response = connection.getresponse()
        return response.status, response.getheader("location") or "", response.read().decode("utf-8", "replace")
    except OSError as e:
        return 0, "", str(e)
    finally:
        connection.close()


# Spellings of a console path that a server might tidy into one after the gate has looked at it.
SIDEWAYS = ["/_next/static/../portfolio", "/_next/static/%2e%2e/portfolio", "/_next/static/..%2fportfolio", "/_next/static/..%5cportfolio",
            "//portfolio", "/portfolio/", "/./portfolio", "/_next/data/x/portfolio.json", "/_next/image?url=%2Fportfolio&w=64&q=75"]


def signed_in_only(signin_web, closed_web, next_dir):
    """
    Rule: PM-DEBT-011 — where people sign in, a person who is not signed in is served the landing and the sign-in
    routes and nothing else: every other path in the build's route manifest sends a load to sign in — at this site's
    own address, and back to the path asked for — and answers any other way of asking with a 401. A session cookie
    this server did not seal counts for nothing. Sign-out is refused to a request that does not come from this site's
    own pages. And no page is prerendered: such a page is answered as one a cache may keep and hand to anyone.
    """
    failures = []
    kept = [r for r in json.loads((next_dir / "prerender-manifest.json").read_text(encoding="utf-8"))["routes"] if r != "/_not-found"]
    failures += [f"PM-DEBT-011: {r} is prerendered — render it when asked for (export const dynamic = \"force-dynamic\")" for r in kept]

    url = lambda route: re.sub(r"\[+\.*([^\]]+)\]+", r"\1", route)
    console = sorted(url(r) for r in routes(next_dir) if r != "/" and not r.startswith("/auth/"))
    forged = {"Cookie": "domuvai-session.0=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA; __Host-domuvai-session.0=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
    # A browser says how it asks (Sec-Fetch-Mode); Next hides its router's own headers from middleware. A client that
    # says nothing is taken for a person loading a page.
    loads = [("a load", "GET", {"Sec-Fetch-Mode": "navigate"}), ("a load by a client that does not say how it asks", "GET", {})]
    others = [(name, method, {**h, "Sec-Fetch-Mode": "cors"}) for name, method, h in ASKS if h] + [("a HEAD", "HEAD", {}), ("a POST", "POST", {})]
    for path in console + ["/no-such-page"]:
        wanted = f"/auth/login?return={urllib.parse.quote(path, safe='')}"           # this site's own, relative or spelled out
        for cookie in ({}, forged):
            for name, method, h in loads + others:
                status, location, body = ask(signin_web, path, method, {**h, **cookie})
                if (name, method, h) in loads and (status != 302 or location not in (wanted, signin_web + wanted)):
                    failures.append(f"PM-DEBT-011: {path} asked as {name}, not signed in, answers HTTP {status} → {location or 'nowhere'} — expected 302 → {wanted}")
                if (name, method, h) in others and (status != 401 or len(body) > 100):
                    failures.append(f"PM-DEBT-011: {path} asked as {name}, not signed in, answers HTTP {status} — expected a bare 401")
    for base, where in ((signin_web, "people sign in"), (closed_web, "sign-in is not configured")):
        for path in SIDEWAYS:
            status, location, body = ask(base, path)
            if status not in (302, 308, 401, 404) or "€" in body or len(body) > 2000:     # a way round, a refusal — never a page
                failures.append(f"PM-DEBT-011: {path} answers HTTP {status} with {len(body)} characters where {where}, to a person not signed in")
    for origin in ({}, {"Origin": "http://evil.example"}, {"Origin": "null"}, {"Origin": signin_web + "/"}, {"Origin": signin_web.upper()}):
        status, _, _ = ask(signin_web, "/auth/logout", "POST", origin)
        if status != 403:
            failures.append(f"sign-out asked with {origin or 'no Origin'} answers HTTP {status} — expected 403: only this site's own pages sign a person out")
    status, _, landing = ask(signin_web, "/")
    if status != 200 or 'href="/portfolio"' not in landing:
        failures.append(f"the landing where people sign in answers HTTP {status}, or shows no way in")
    print(f"{'BAD' if failures else 'ok '} PM-DEBT-011 not signed in: {len(console) + 1} paths × {len(loads) + len(others)} ways asked × with and without a forged "
          f"cookie lead to sign-in or a 401; {len(SIDEWAYS)} sideways spellings serve nothing; sign-out is this site's own; nothing is prerendered")
    return failures


TOKEN = re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}")       # a signed token, wherever it turns up


class Browser:
    """A person's browser, as far as sign-in goes: it keeps each site's cookies, follows where it is sent, and notes every address."""

    def __init__(self):
        self.cookies, self.visited, self.set = {}, [], []

    def go(self, url, form=None, headers=None):
        for _ in range(12):
            target = urllib.parse.urlsplit(url)
            origin = f"{target.scheme}://{target.netloc}"
            jar = self.cookies.setdefault(target.netloc, {})
            sent = {"Sec-Fetch-Mode": "navigate", **(headers or {})}
            if jar:
                sent["Cookie"] = "; ".join(f"{k}={v}" for k, v in jar.items())
            if form is not None:
                sent["Content-Type"] = "application/x-www-form-urlencoded"
            connection = http.client.HTTPConnection(target.hostname, target.port, timeout=60)
            try:
                connection.request("POST" if form is not None else "GET", (target.path or "/") + (f"?{target.query}" if target.query else ""),
                                   body=urllib.parse.urlencode(form) if form is not None else None, headers=sent)
                response = connection.getresponse()
                status, body, location = response.status, response.read().decode("utf-8", "replace"), response.getheader("location")
                for cookie in response.msg.get_all("set-cookie") or []:
                    name, _, value = cookie.split(";", 1)[0].partition("=")
                    self.set.append((origin, cookie))
                    if value and "max-age=0" not in cookie.lower().replace(" ", ""):
                        jar[name.strip()] = value
                    else:
                        jar.pop(name.strip(), None)
            finally:
                connection.close()
            self.visited.append(url)
            if status not in (301, 302, 303, 307, 308) or not location:
                return status, url, body
            url, form, headers = urllib.parse.urljoin(url, location), None, None
        return 0, url, "sent round in circles"

    def cookie_header(self, base):
        return "; ".join(f"{k}={v}" for k, v in self.cookies.get(urllib.parse.urlsplit(base).netloc, {}).items())


def login_form(page):
    """Where the issuer's sign-in page posts a name and a password to — Keycloak's own form — or None."""
    form = re.search(r'<form\b[^>]*\bid="kc-form-login"[^>]*>', page)
    action = re.search(r'\baction="([^"]+)"', form.group(0)) if form else None
    return html.unescape(action.group(1)) if action else None


def sign_in(web, issuer, user, password, subject, tied=True):
    """
    ADR-011, AUTH-03 — a person signs in as a browser does: asks for a console page, is sent to the issuer, gives a
    name and a password there, and comes back signed in to the page they asked for. What they then hold is an httpOnly
    cookie and no token: none in an address they passed through, none in a cookie, none in the page. The console's
    strip names them, and carries the login the api itself read from the web's bearer — the issuer's own id for this
    person — so the token the web passes on is this person's and the api accepts it.
    """
    failures, browser, asked = [], Browser(), "/portfolio?asOf=2026-09-30"
    status, at, page = browser.go(web + asked)
    action = login_form(page)
    if status != 200 or not at.startswith(issuer + "/") or not action:
        return [f"sign-in: a console page asked for without a session leads to HTTP {status} at {at.split('?')[0]} — expected the issuer's sign-in form"], None
    if not action.startswith(issuer + "/"):
        return [f"sign-in: the issuer's form posts to {action.split('?')[0]}, not to the issuer"], None
    status, at, page = browser.go(action, form={"username": user, "password": password, "credentialId": ""})
    if (status, at) != (200, web + asked):
        return [f"sign-in: after the issuer's form the browser is at {at.split('?')[0]} with HTTP {status} — expected {web + asked}. "
                f"The issuer said: {visible_text(page)[:300]!r}"], None

    kept = [cookie for origin, cookie in browser.set if origin == web and "domuvai-session" in cookie and "max-age=0" not in cookie.lower().replace(" ", "")]
    if not kept:
        failures.append("sign-in: the web handed the browser no session cookie")
    for cookie in kept:
        flags = [part.strip().lower() for part in cookie.split(";")[1:]]
        failures += [f"sign-in: the session cookie is not {flag}" for flag in ("httponly", "samesite=lax", "path=/") if flag not in flags]
    # the web's cookies only: the issuer keeps its own session in signed cookies of its own, on its own site
    held = [("an address passed through", u) for u in browser.visited] + [("a cookie of the web's", c) for o, c in browser.set if o == web] + [("the page", page)]
    if any("domuvai-signin" in name for name in browser.cookies.get(urllib.parse.urlsplit(web).netloc, {})):
        failures.append("sign-in: what the browser kept for the sign-in is still there after it — it is good for one answer")
    failures += [f"sign-in: a signed token is in {where}" for where, text in held if TOKEN.search(text)]
    failures += [f"sign-in: {word} is in an address the browser passed through" for word in ("access_token", "id_token", "refresh_token", "client_secret", "code_verifier")
                 if any(word in u for u in browser.visited)]

    strip = re.search(r'<form\b[^>]*class="signed-in"[^>]*>.*?</form>', re.sub(r"<!--.*?-->", "", page, flags=re.S), re.S)
    if not strip:
        failures.append("who am I: the console shows nobody signed in")
    else:
        shown = visible_text(strip.group(0))
        failures += [] if user in shown and "Изход" in shown else [f"who am I: the strip says {shown!r} — expected {user!r} and the way out"]
        # AUTH-04: the run's administrator tied this login to a party before the screens were read (e2e.yml), so the strip
        # no longer says the person is nobody in the book — the web is told so by the api's own record, not by the token.
        # … except the deployment's administrator (WEB-49), tied to nobody — and the strip says so
        if tied:
            failures += [] if "без лице в книгата" not in shown else [f"who am I: the strip says {shown!r} — this login is tied to a party, and it says it is nobody in the book"]
        else:
            failures += [] if "без лице в книгата" in shown else [f"who am I: the strip says {shown!r} — this login is the administrator's, tied to nobody, and it does not say so"]
        failures += [] if f'data-login="{subject}"' in strip.group(0) else [
            "who am I: the login the api read from the web's bearer is not the issuer's id for this person "
            f"({subject}) — GET /api/identity/me through the web answered something else, or nothing"]
    print(f"{'BAD' if failures else 'ok '} sign-in: through the issuer's own form and back to the page asked for; an httpOnly cookie and no token in "
          f"{len(browser.visited)} addresses, {len(browser.set)} cookies or the page; the api names the same login the issuer does")
    return failures, browser


def sign_out(web, issuer, browser):
    """
    ADR-011, AUTH-03 — sign-out ends the session: the browser's cookie is taken back, a console page sends the person
    to sign in again — and the issuer asks for the password again, so its own session is over too, not only the web's.
    """
    failures = []
    target = urllib.parse.urlsplit(web)
    # First, that the issuer's session is there to end: a second browser window with the issuer's cookies and none of
    # the web's is let straight in. Without this, "asks for the password again" below could be true for any reason.
    window = Browser()
    window.cookies = {site: dict(jar) for site, jar in browser.cookies.items() if site != target.netloc}
    status, at, page = window.go(web + "/portfolio")
    if status != 200 or not at.startswith(web + "/") or login_form(page):
        failures.append(f"sign-out: before it, a browser holding the issuer's session is not let straight in (HTTP {status} at {at.split('?')[0]}) "
                        "— so the issuer asking for the password afterwards would prove nothing")
    connection = http.client.HTTPConnection(target.hostname, target.port, timeout=60)
    try:
        connection.request("POST", "/auth/logout", headers={"Origin": web, "Cookie": browser.cookie_header(web)})
        response = connection.getresponse()
        response.read()
        cleared = [c for c in response.msg.get_all("set-cookie") or [] if "domuvai-session" in c]
        if response.status != 303 or not cleared or any("max-age=0" not in c.lower().replace(" ", "") for c in cleared):
            failures.append(f"sign-out: HTTP {response.status}, {len(cleared)} session cookie(s) taken back — expected 303 and every piece")
    finally:
        connection.close()
    for name in [n for n in browser.cookies.get(target.netloc, {}) if "domuvai-session" in n]:
        del browser.cookies[target.netloc][name]
    status, at, page = browser.go(web + "/portfolio")           # the issuer's own cookies are still in this browser
    if status != 200 or not at.startswith(issuer + "/") or not login_form(page):
        failures.append(f"sign-out: afterwards a console page leads to HTTP {status} at {at.split('?')[0]} — expected the issuer asking for "
                        "the password again; if it let the person straight back in, its own session was not ended")
    print(f"{'BAD' if failures else 'ok '} sign-out: the cookie taken back, and the issuer — which let this browser straight in before — asks for the password again")
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
    preview = lambda extra: fetch(api, f"/api/money/entrances/{entrance}/charge-runs/preview", {**body, **extra}, headers=as_board())
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


def entrance_screen(api, web, entrance):
    """
    WEB-20 — `/entrance` shows the entrance it is asked for and nothing of the design's sample: no sample figure or
    name is left, its tabs and the sidebar carry the entrance on, an entrance with no accounts and nobody overdue says
    so, and an id that is not registered is refused rather than answered with the first entrance.
    """
    failures = []
    status, page = fetch(web, f"/entrance?entrance={entrance}")
    text, hrefs = visible_text(page), page.replace("&amp;", "&")
    sample = ["€3.812,40", "€1.240,50", "Покрив 2026", "57 живущи", "24 обекта", "Шипка 14 Б", "Мандат до 31.10", "отг. М. Петрова", "Лифт Сервиз"]
    failures += [f"/entrance: still shows the design's sample ({word!r})" for word in sample if word in text]
    failures += [] if status == 200 else [f"/entrance: HTTP {status}"]
    # the tab and the sidebar each carry the entrance — and, to the fund, the accounts card's button too
    wanted = {f"/entrance/charges?entrance={entrance}": 2, f"/entrance/fund?entrance={entrance}": 3, f"/entrance?entrance={entrance}": 2}
    failures += [f"/entrance: {n} link(s) to {link}, where {least} should carry the entrance"
                 for link, least in wanted.items() if (n := hrefs.count(f'href="{link}"')) < least]

    status, raw = fetch(api, "/api/registry/entrances")
    other = next((e["id"] for e in json.loads(raw) if e["label"] == BUSINESS_LABEL), None) if status == 200 else None
    if not other:
        return failures + [f"/entrance: the seed's second entrance ({BUSINESS_LABEL}) is not registered — it was not checked"]
    status, page = fetch(web, f"/entrance?entrance={other}")
    failures += [] if status == 200 else [f"/entrance for {BUSINESS_LABEL}: HTTP {status}"]
    bare = visible_text(page)
    expected = [f"Портфейл / {BUSINESS_LABEL} 2 обекта", "Просрочени декларации · 0", "Няма просрочена декларация за книгата на собствениците.",
                "Обекти 2 · 1 жил. / 1 търг.", "Книга на собствениците без просрочени декларации",
                "Входът няма оперативна сметка в регистъра.", "Входът няма сметка на фонда в регистъра."]
    failures += [f"/entrance for {BUSINESS_LABEL}: does not show {want!r}" for want in expected if want not in bare]
    failures += [f"/entrance for {BUSINESS_LABEL}: shows the other entrance's {word!r}" for word in ("ул. Шипка 14", "€570,00", "Иван Петров") if word in bare]

    unknown = "00000000-0000-0000-0000-000000000000"
    refused = visible_text(fetch(web, f"/entrance?entrance={unknown}")[1])
    if f"Вход {unknown} не е регистриран." not in refused or "Сметки на входа" in refused:
        failures.append("/entrance: an entrance that is not registered is not refused")
    print(f"{'ok ' if not failures else 'BAD'} /entrance  no sample left, links carry the entrance, an entrance with nothing says so, an unknown one is refused")
    return failures


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


# What this check holds once it is signed in: per server, the headers its requests carry — the session cookie for the
# web, a bearer for the api. Never printed.
CARRIED = {}


def as_board():
    """G-3 (PM-SEC-001): an entrance's accounts — a charge run previewed or issued — are kept by its manager or its board,
    so the check keeps them with the board's token (E2E_MANAGER_TOKEN); its own is the controller's. None with no sign-in."""
    manager = os.environ.get("E2E_MANAGER_TOKEN")
    return {"Authorization": f"Bearer {manager}"} if manager else None


def fetch(base, url, body=None, method=None, headers=None):
    request = urllib.request.Request(
        base + url, method=method or ("POST" if body is not None else "GET"),
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **CARRIED.get(base, {}), **(headers or {})},
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


def compliance_screen(api, web, entrance):
    """
    WEB-23 — `/compliance` shows, entrance by entrance, what the API serves and nothing of the design's sample: who is
    past the registry's due date to declare for the book (PM-BOOK-003) and the month's headcount check (PM-BOOK-012).
    Every figure is taken from the API here, never typed: the page must show the same. An entrance with no run issued
    for the month is said to have none — never shown as one with nothing to report. This check writes: it enters one
    person in the seeded entrance's book, so that there is a difference to read.
    """
    failures, date = [], lambda iso: ".".join(reversed(iso.split("-")))
    plural = lambda n, one, many: f"{n} {one if n == 1 else many}"
    status, page = fetch(web, "/compliance?period=2026-09")
    text = visible_text(page)
    failures += [] if status == 200 else [f"/compliance: HTTP {status}"]
    sample = ["чл. 19", "Армеец", "Агенция", "КЗЛД", "ПД-0142", "22-0034512", "62 действащи", "204118736", "62 входа под управление", "Армеец", "СОА26", "КЗЛД-26", "Остават 83 дни", "20260628153412"]
    failures += [f"/compliance: still shows the design's sample ({word!r})" for word in sample if word in text]

    status, raw = fetch(api, f"/api/registry/entrances/{entrance}/book/declarations/overdue")
    overdue = json.loads(raw) if status == 200 else None
    if not overdue:
        failures.append(f"/compliance: the API lists no overdue declaration for the seeded entrance (HTTP {status}) — nothing to read off the page")
    roles = {"OWN": "собственик", "USR": "ползвател"}
    rows = [f"{d['designation']} {d['partyName']} {roles.get(d['titleRole'], d['titleRole'].lower())} {date(d['acquiredOn'])} {date(d['dueOn'])}" for d in overdue or []]
    failures += [f"/compliance: does not show the overdue declaration {row!r}" for row in rows if row not in text]
    group = f"ул. Шипка 14, вх. Б {plural(len(rows), 'просрочена декларация', 'просрочени декларации')}"
    failures += [] if group in text else [f"/compliance: does not show {group!r}"]

    # PM-BOOK-012 — the report lists the units that differ, so the check makes one differ, as a manager would: through
    # the public API it enters a child in the book of the first unit for a date the September run had already billed.
    # (Each run of this check enters one more; the figures below are the API's, whatever they have become.)
    def headcount():
        status, raw = fetch(api, f"/api/money/entrances/{entrance}/charge-runs/2026-09/headcount")
        return json.loads(raw) if status == 200 else None
    check = headcount()
    if not check:
        failures.append("/compliance: the API gives no headcount check for the seeded run of 2026-09")
    else:
        if not check["mismatches"] and "Начислените лица са лицата по книга." not in text.split("Сверка на книгата", 1)[-1]:
            failures.append("/compliance: a check with no difference is not said to have none")
        status, raw = fetch(api, f"/api/registry/entrances/{entrance}/units")
        unit = sorted(json.loads(raw), key=lambda u: u["designation"])[0]["id"] if status == 200 else None
        # AUTH-05c (PM-BOOK-005): the book is entered by the manager or the board — the check's own token is the
        # controller's, refused; the entry is made with the board's (E2E_MANAGER_TOKEN). With no sign-in, neither is carried.
        entry = (f"/api/registry/entrances/{entrance}/units/{unit}/household", {"members": [{"isChildUnder6": True, "validFrom": "2026-08-01"}]})
        manager = os.environ.get("E2E_MANAGER_TOKEN")
        if manager:
            refused, _ = fetch(api, *entry)
            failures += [] if refused == 403 else [f"/compliance: the controller entered the book (HTTP {refused}), not refused (PM-BOOK-005)"]
        status, raw = fetch(api, *entry, headers={"Authorization": f"Bearer {manager}"} if manager else None)
        check = headcount() if status in (200, 201) else None
        if not check or not check["mismatches"]:
            failures.append(f"/compliance: a person entered in the book for a date already billed (HTTP {status}) makes no difference the API reports — no row to read")
        else:
            text = visible_text(fetch(web, "/compliance?period=2026-09")[1])
            checks = text.split("Сверка на книгата", 1)[-1]
            head = (f"ул. Шипка 14, вх. Б сравнени {plural(check['unitsCompared'], 'обект', 'обекта')} към {date(check['legalDate'])} · "
                    f"{plural(len(check['mismatches']), 'разминаване', 'разминавания')}")
            failures += [] if head in checks else [f"/compliance: does not show {head!r}"]
            words = {"COUNT_DIFFERS": "броят се различава", "NOT_IN_BOOK": "начислен, а вече не е обект на входа по книга", "NOT_BILLED": "обект по книга, а не е начислен"}
            persons = lambda all_, under6: "—" if all_ is None else f"{all_} · {under6} под 6 г."
            for m in check["mismatches"]:
                row = (f"{m['designation']} {words.get(m['difference'], m['difference'])} {persons(m.get('billedOccupants'), m.get('billedChildrenUnder6'))} "
                       f"{persons(m.get('declaredOccupants'), m.get('declaredChildrenUnder6'))}")
                failures += [] if row in checks else [f"/compliance: does not show the unit that differs as the API reports it: {row!r}"]
    # the seed's second entrance has no run at all, and no entrance has one for a month long past: said, not shown as clean
    none = f"{BUSINESS_LABEL} няма издадено начисление за месеца"
    failures += [] if none in text else [f"/compliance: does not show {none!r}"]
    past = visible_text(fetch(web, "/compliance?period=2020-01")[1])
    if past.count("няма издадено начисление за месеца") != 2 or "сравнени" in past or "януари 2020" not in past:
        failures.append("/compliance?period=2020-01: a month with no run is not said to have none for both entrances")
    links = [f'href="/compliance?period=2026-08"', f'href="/compliance?period=2026-10"', f'href="/entrance?entrance={entrance}"']
    failures += [f"/compliance: no link {link}" for link in links if link not in page.replace("&amp;", "&")]
    print(f"{'ok ' if not failures else 'BAD'} /compliance  {len(rows)} overdue declarations, and a unit made to differ shown as the API reports it; "
          f"no run said as none; no sample left")
    return failures


def refusal_words(raw):
    """The API's own words in a refusal's body, or None when the body is not one."""
    try:
        body = json.loads(raw)
    except ValueError:
        return None
    return body.get("error") if isinstance(body, dict) and isinstance(body.get("error"), str) else None


def submit_form(web, path, page, marker, fields, files=None):
    """
    A form posted as a browser with no script posts it: every hidden input the server rendered in the form that
    holds [marker] (a server action's own references among them), the visible fields given, multipart — signed in,
    from this site's own page; [files], name → (filename, bytes), as a file input sends them. Answers status, Location, body.
    """
    forms = [f for f in re.findall(r"<form\b.*?</form>", page, re.S) if marker in f]
    if len(forms) != 1:
        return 0, "", f"{len(forms)} forms hold {marker!r}"
    hidden = []
    for tag in re.findall(r"<input\b[^>]*>", forms[0]):
        attrs = dict((k, html.unescape(v)) for k, v in re.findall(r'(\w[\w$:-]*)="([^"]*)"', tag))
        if attrs.get("type") == "hidden" and "name" in attrs:
            hidden.append((attrs["name"], attrs.get("value", "")))
    boundary = "----e2e" + uuid.uuid4().hex
    body = b"".join(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in hidden + list(fields.items()))
    for name, (filename, content) in (files or {}).items():
        body += (f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
                 f"Content-Type: application/octet-stream\r\n\r\n").encode() + content + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    target = urllib.parse.urlsplit(web)
    connection = http.client.HTTPConnection(target.hostname, target.port, timeout=60)
    try:
        connection.request("POST", path, body=body, headers={**CARRIED.get(web, {}), "Origin": web, "Content-Type": f"multipart/form-data; boundary={boundary}"})
        response = connection.getresponse()
        return response.status, response.getheader("location") or "", response.read().decode("utf-8", "replace")
    finally:
        connection.close()


def assembly_screen(api, web, entrance, signed_in, owner_party=None, owner=None):
    """
    WEB-25 — the entrance's general assembly as the API answers it. Signed in (CI): the offices in force today as
    identity gives them (PM-GOV-005); an assembly convened through the page's own form, by the office the person holds
    (PM-GA-002), landing on it and showing what the API stored — when in Sofia, where, how, by which office, a draft
    whose notice is not posted, an empty agenda — and the quorum meter in the API's figures or the API's refusal; and
    convening as an office the person does not hold, refused in the API's words, the form kept. The page computes
    no deadline, share or threshold: every value compared here is taken from the API.
    """
    failures, date = [], lambda iso: ".".join(reversed(iso.split("-")))
    path = f"/entrance/assembly?entrance={entrance}"
    status, page = fetch(web, path)
    text = visible_text(page)
    if status != 200 or "Свикване на общо събрание" not in text or 'name="convenedAs"' not in page:
        return [f"{path}: HTTP {status}, or no form to convene"]
    # WEB-26 (PM-GA-001): whether this year's assembly was held, in the API's own count against the law's
    status, raw = fetch(api, f"/api/assembly/entrances/{entrance}/years/{sofia_year()}")
    if status == 200:
        y = json.loads(raw)
        line = f"През {y['year']} проведени {y['held']} от {y['required']} по {y['requiredSource']} " + ("годишното е проведено" if y["met"] else "годишното не е проведено")
        failures += [] if line in text else [f"{path}: does not show {line!r}"]
    elif (refusal_words(raw) or f"HTTP {status}") not in text:
        failures.append(f"{path}: the year is refused (HTTP {status}) and the page does not say the API's words")
    if not signed_in:
        print("—   /entrance/assembly  read; convening, and the five operations on an assembly, NOT checked: nobody is signed in on this run")
        return failures

    status, raw = fetch(api, f"/api/identity/entrances/{entrance}/mandates")       # as at the api's own today, as the page asks
    offices = {"MB": "член на управителния съвет", "BM": "управител", "CTL": "контролен съвет / контрольор", "CSH": "касиер"}
    mandates = json.loads(raw) if status == 200 else []
    failures += [] if mandates else [f"{path}: the API gives no mandate in force today (HTTP {status}) — nothing to read off the page"]
    def standing(s):               # PM-GOV-005 — how identity says each office stands, read back as the page words it
        if s["kind"] == "IN_TERM":
            return "в мандат" + (f" до {date(s['until'])}" if s.get("until") else "")
        if s["kind"] == "ELECTION_DUE":
            return "изборът се дължи" + (f" до {date(s['electBy'])}" if s.get("electBy") else "")
        if s["kind"] == "EXPIRED_ACTING":
            return "мандатът е изтекъл — изпълнява" + (f" от {date(s['actingSince'])}" if s.get("actingSince") else "")
        return s["kind"]
    for m in mandates:
        row = f"{offices.get(m['role'], m['role'])} {m['partyId'][:8]} {date(m['validFrom'])} {date(m['validTo'])} {standing(m['standing'])}"
        failures += [] if row in text else [f"{path}: does not show the office {row!r}"]

    day = datetime.datetime.now(ZoneInfo("Europe/Sofia")).date() + datetime.timedelta(days=30)
    place = f"Входът, партер — проверката {uuid.uuid4().hex[:6]}"
    status, location, _ = submit_form(web, path, page, 'name="convenedAs"',
                                      {"convenedAs": "MB", "scheduledAt": f"{day}T18:30", "place": place, "mode": "IN_PERSON"})
    made = re.fullmatch(rf"(?:{re.escape(web)})?/entrance/assembly\?entrance={entrance}&assembly=([0-9a-f-]{{36}})", location)
    if status != 303 or not made:
        return failures + [f"{path}: convening through the page's form answers HTTP {status} → {location or 'nowhere'} — expected the new assembly's page"]
    shown = visible_text(fetch(web, location.removeprefix(web))[1])
    of = f"/api/assembly/entrances/{entrance}/assemblies/{made.group(1)}"
    # WEB-26 (PM-GA-002): the new assembly is among the entrance's, as the API lists them, marked as the one open —
    # and on the screen with no assembly asked for, it is a link to its own page
    status, raw = fetch(api, f"/api/assembly/entrances/{entrance}/assemblies")
    listed = json.loads(raw) if status == 200 else []
    failures += [] if any(a["id"] == made.group(1) for a in listed) else [f"GET …/assemblies: HTTP {status} — the new assembly is not listed"]
    errors = list(Draft202012Validator(response_schema("GET", "/api/assembly/entrances/{entranceId}/assemblies", 200),
                                       format_checker=Draft202012Validator.FORMAT_CHECKER).iter_errors(listed))   # with one in it, unlike the seed's
    failures += [f"GET …/assemblies: {'/'.join(map(str, e.absolute_path)) or '(root)'} — {e.message}" for e in errors]
    row = f"{date(str(day))} 18:30 чернова — поканата не е обявена член на управителния съвет отворено"
    failures += [] if row in shown else [f"{path}: the list does not show the new assembly as {row!r}"]
    _, again = fetch(web, path)
    failures += [] if html.escape(f"/entrance/assembly?entrance={entrance}&assembly={made.group(1)}", quote=True) in again else [
        f"{path}: the list does not link to the new assembly"]
    for suffix, path_ in (("", ""), ("/attendance", "/attendance"), ("/proxies", "/proxies"), ("/quorum", "/quorum")):
        status, raw = fetch(api, of + suffix)
        if status != 200:
            failures += [] if suffix == "/quorum" else [f"GET {of + suffix}: HTTP {status} {raw[:200]}"]   # a draft's meter may be refused, below
            continue
        errors = list(Draft202012Validator(response_schema("GET", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}" + path_, 200),
                                           format_checker=Draft202012Validator.FORMAT_CHECKER).iter_errors(json.loads(raw)))
        failures += [f"GET …/assemblies/{{assemblyId}}{path_}: {'/'.join(map(str, e.absolute_path)) or '(root)'} — {e.message}" for e in errors]
    status, raw = fetch(api, of)
    stored = json.loads(raw) if status == 200 else {}
    # the instant stored, against Python's own reading of 18:30 in Sofia — not the web's code checking itself
    at = datetime.datetime.combine(day, datetime.time(18, 30), ZoneInfo("Europe/Sofia")).astimezone(datetime.timezone.utc)
    if stored.get("scheduledAt") and datetime.datetime.fromisoformat(stored["scheduledAt"].replace("Z", "+00:00")) != at:
        failures.append(f"{path}: the form asked for 18:30 in Sofia on {day}; the API stored {stored['scheduledAt']}, not {at.isoformat()}")
    if stored.get("place") != place or stored.get("convenedAs") != "MB" or stored.get("status") != "DRAFT":
        failures.append(f"{path}: the API stored {stored or status} — not the assembly the form asked for")
    wanted = [f"Кога {date(str(day))} 18:30", f"Къде {place}", "Начин присъствено", "Свикано от член на управителния съвет",
              "Покана не е обявена", "Неотложно не", "чернова — поканата не е обявена", "Дневният ред е празен."]
    failures += [f"{path}: the new assembly does not show {w!r}" for w in wanted if w not in shown]
    status, raw = fetch(api, f"/api/assembly/entrances/{entrance}/assemblies/{made.group(1)}/quorum")
    if status == 200:
        q = json.loads(raw)
        meter = f"Присъстват {q['representedPct']}% от идеалните части · праг {q['thresholdPct']}%"
        failures += [] if meter in shown else [f"{path}: the quorum meter does not show {meter!r}"]
    elif (refusal_words(raw) or f"HTTP {status}") not in shown:
        failures.append(f"{path}: the quorum is refused (HTTP {status}) and the page does not say the API's words")

    # WEB-27 (PM-GA-006, PM-VOTE-004): an item put on the agenda through the page's own form, bound to the majority the
    # API has in force for its type and shown as stored; a type whose majority waits on counsel, refused in the API's words.
    # WEB-33 (PM-GA-017): the item is tagged for absentee ballots through the form's own checkbox, and the API keeps it.
    here = location.removeprefix(web)
    _, assembly_page = fetch(web, here)
    item = f"Отчет на управителя — проверката {uuid.uuid4().hex[:6]}"
    status, back, _ = submit_form(web, here, assembly_page, 'name="itemType"', {"text": item, "itemType": "GENERAL", "absenteeEligible": "on"})
    if status != 303 or back.removeprefix(web) != here:
        failures.append(f"{here}: adding an agenda item answers HTTP {status} → {back or 'nowhere'} — expected the assembly's page")
    else:
        status, raw = fetch(api, of)
        agenda = json.loads(raw).get("agenda", []) if status == 200 else []
        put = [i for i in agenda if i["text"] == item]
        if len(put) != 1 or put[0]["itemType"] != "GENERAL" or put[0].get("absenteeEligible") is not True:
            failures.append(f"{here}: the API stored the agenda {agenda or status} — not the item the form asked for, tagged for absentee ballots (PM-GA-017)")
        else:
            row = f"{put[0]['ordinal']} {item} GENERAL · и неприсъствено {put[0]['majorityRuleId']}"
            failures += [] if row in visible_text(fetch(web, here)[1]) else [f"{here}: the agenda does not show {row!r}"]
    pending = item + " (чака юрист)"
    status, back, refused_page = submit_form(web, here, assembly_page, 'name="itemType"', {"text": pending, "itemType": "COMMON_PART_USE_RIGHT"})
    said = visible_text(refused_page)
    if status != 200 or back or "Системата не прие това:" not in said or "PM-VOTE-004" not in said or f'value="{html.escape(pending, quote=True)}"' not in refused_page:
        failures.append(f"{here}: an item whose majority waits on counsel answers HTTP {status} → {back or 'the page'} — expected the API's refusal (PM-VOTE-004), the form kept")

    failures += proxies_on(api, web, entrance, here, of, owner, owner_party)
    failures += moved_and_noticed(api, web, here, of, day, place, owner_party)

    # an office the person does not hold: refused in the API's words, and nothing convened
    status, location, refused_page = submit_form(web, path, page, 'name="convenedAs"',
                                                 {"convenedAs": "BM", "scheduledAt": f"{day}T19:30", "place": place + " (отказано)", "mode": "IN_PERSON"})
    said = visible_text(refused_page)
    kept = f'value="{html.escape(place + " (отказано)", quote=True)}"' in refused_page        # the field itself, not the page's script payload
    if status != 200 or location or "Нямате право да свикате общо събрание" not in said or not kept:
        failures.append(f"{path}: convening as an office not held answers HTTP {status} → {location or 'the page'} — expected the API's refusal, the form kept")
    print(f"{'ok ' if not failures else 'BAD'} /entrance/assembly  {len(mandates)} offices as identity gives them; this year's assembly in the API's count; "
          f"convened through the form, listed and shown as stored; an agenda item put on it and one awaiting counsel refused; the meter in the API's figures; "
          f"an office not held refused in the API's words; moved, a notice refused and one posted, its sitting weighed, its register kept and no vote offered before the opening, one by who does not run it refused, moved again and void")
    return failures


def proxies_on(api, web, entrance, here, of, owner=None, owner_party=None):
    """
    WEB-48b (PM-GA-009, PM-GA-010, PM-GA-011, PM-SEC-001) on the draft assembly_screen convened, with an item on its agenda.
    Signed in as a board member, who holds no title, appointing a proxy is the API's 403 in its words, the form kept, nothing
    registered. Signed in as the seeded owner (`--owner-user`): items the assembly does not have are the API's 400 in its
    words; a proxy for the whole agenda, appointed through the page for oneself, is registered as the API keeps it — read
    back with the check's own token and validated against the contract, and shown in the register; a second one for the same
    principal is the API's 409. The agent is the fund's holder, a registered party — a third party, which anybody may be.
    No limit is the check's: the 4th proxy for one agent (PM-GA-010) would need three more principals' written
    authorisations, which the seed does not hold, so the limit is the API's and pinned in the unit tests.
    """
    agent, fields = fund_holder(entrance), {"agentKind": "THIRD_PARTY", "form": "WRITTEN", "scope": "WHOLE_AGENDA"}
    proxies = of + "/proxies"
    if owner_party and agent == owner_party:
        return [f"{here}: the fund's holder is the seeded owner — a proxy for oneself is the API's 400 (nobody is their own proxy); pick another agent"]
    registered = lambda: (lambda st, raw: json.loads(raw) if st == 200 else None)(*fetch(api, proxies))
    status, page = fetch(web, here)
    failures = [f"{here}: HTTP {status}"] if status != 200 else []
    failures += [f"{here}: the proxy form is missing"] if 'name="agentPartyId"' not in page else []
    s, back, refused = submit_form(web, here, page, 'name="agentPartyId"', {**fields, "agentPartyId": agent})
    failures += [] if s == 200 and not back and "Нямате право да впишете пълномощно:" in visible_text(refused) and f'value="{agent}"' in refused else [
        f"{here}: PM-SEC-001 — a board member appointing a proxy answered HTTP {s} → {back or 'the page'}, without the API's 403 in its words, or the form not kept"]
    failures += [] if registered() == [] else [f"{here}: a proxy was registered for a board member"]
    if not owner:
        print("—   /entrance/assembly  the owner's proxy NOT checked: no --owner-user")
        return failures
    board = CARRIED.get(web)
    CARRIED[web] = owner                                    # the page, signed in as the owner
    try:
        page = fetch(web, here)[1]
        s, back, refused = submit_form(web, here, page, 'name="agentPartyId"', {**fields, "agentPartyId": agent, "scope": "LISTED_ITEMS", "items": "999"})
        failures += [] if s == 200 and not back and "Системата не прие това:" in visible_text(refused) and "999" in visible_text(refused) and registered() == [] else [
            f"{here}: PM-GA-009 — a proxy for an agenda item the assembly does not have answered HTTP {s} → {back or 'the page'}, without the API's words, or one was registered"]
        s, back, _ = submit_form(web, here, page, 'name="agentPartyId"', {**fields, "agentPartyId": agent})
        if s != 303 or back.removeprefix(web) != here:
            return failures + [f"{here}: appointing a proxy through the page answers HTTP {s} → {back or 'nowhere'} — expected the assembly's page"]
        status, raw = fetch(api, proxies)
        kept = json.loads(raw) if status == 200 else []
        errors = list(Draft202012Validator(response_schema("GET", "/api/assembly/entrances/{entranceId}/assemblies/{assemblyId}/proxies", 200),
                                           format_checker=Draft202012Validator.FORMAT_CHECKER).iter_errors(kept))
        failures += [f"GET …/proxies: {'/'.join(map(str, e.absolute_path)) or '(root)'} — {e.message}" for e in errors]
        want = {"agentKind": "THIRD_PARTY", "agentPartyId": agent, "form": "WRITTEN", "scope": "WHOLE_AGENDA", "items": []}
        if len(kept) != 1 or any(kept[0].get(k) != v for k, v in want.items()) or (owner_party and kept[0].get("principalPartyId") != owner_party) or kept[0].get("authorisationHash"):
            failures.append(f"{here}: the API holds {kept or status}, not the one proxy the form asked for, in person (no document)")
        # names beside the ids (PM-BOOK-006, PM-BOOK-011): to the controller's own token the principal's and the agent's, a name only;
        # to the seeded owner's the ids alone — no name of another person. (The register is empty here: its names are the IT's.)
        if kept:
            given = kept[0].get("names") or {}
            failures += [] if set(given) == {kept[0]["principalPartyId"], agent} and all(isinstance(n, str) and n and n not in given for n in given.values()) else [
                f"{here}: PM-BOOK-006 — the controller reads the proxy with names {given}, not the principal's and the agent's"]
            st_owner, raw_owner = fetch(api, proxies, headers=owner)
            as_owner = json.loads(raw_owner) if st_owner == 200 else None
            failures += [] if as_owner and len(as_owner) == 1 and "names" not in as_owner[0] and not any(n in raw_owner for n in given.values()) else [
                f"{here}: PM-SEC-002 — the owner reads the proxies (HTTP {st_owner}) with a name of another person, or not at all"]
        row = f"{kept[0]['principalPartyId'][:8]} {agent[:8]} писмено целия дневен ред" if kept else ""
        failures += [] if row in visible_text(fetch(web, here)[1]) else [f"{here}: the register does not show the proxy as {row!r}"]
        s, back, again = submit_form(web, here, fetch(web, here)[1], 'name="agentPartyId"', {**fields, "agentPartyId": agent})
        failures += [] if s == 200 and not back and "Системата не прие това:" in visible_text(again) and len(registered() or []) == 1 else [
            f"{here}: PM-GA-009 — a second proxy for the same principal answered HTTP {s} → {back or 'the page'}, without the API's words, or a second was kept"]
    finally:
        CARRIED[web] = board
    print(f"{'ok ' if not failures else 'BAD'} PM-GA-009 the proxies: a board member's refused in the API's 403, an unknown agenda item in its 400; "
          "the owner's proxy appointed through the page, read back and shown in the register; a second for the same principal refused")
    return failures


def moved_and_noticed(api, web, here, of, day, place, owner_party):
    """
    WEB-28 (PM-GA-004, PM-GA-006, PM-GA-007, PM-SYS-013) on the draft assembly_screen convened: moved through the page's
    own form and stored as asked; a posting act co-signed by a party who owns nothing refused in the API's words, the
    form kept; then — given an owner of the entrance — the notice posted through the form with a photograph, the
    assembly NOTICED as the API keeps it; and moved once more, the API's draft again, the page saying the notice is void.
    """
    failures, date = [], lambda iso: ".".join(reversed(iso.split("-")))
    sofia = lambda d, hm: datetime.datetime.combine(d, hm, ZoneInfo("Europe/Sofia")).astimezone(datetime.timezone.utc)
    stored = lambda: (lambda st, raw: json.loads(raw) if st == 200 else {})(*fetch(api, of))
    def move(to_day, to_place):
        status, back, _ = submit_form(web, here, fetch(web, here)[1], 'name="wasNoticed"', {"scheduledAt": f"{to_day}T19:00", "place": to_place})
        return status, back.removeprefix(web)
    moved_day, moved_place = day + datetime.timedelta(days=1), place + " (преместено)"
    status, back = move(moved_day, moved_place)
    a = stored()
    if status != 303 or back != here:
        return [f"{here}: moving the draft answers HTTP {status} → {back or 'nowhere'} — expected the assembly's page"]
    if a.get("place") != moved_place or not a.get("scheduledAt") or \
            datetime.datetime.fromisoformat(a["scheduledAt"].replace("Z", "+00:00")) != sofia(moved_day, datetime.time(19, 0)):
        failures.append(f"{here}: the form moved it to 19:00 in Sofia on {moved_day}, {moved_place!r}; the API stored {a or 'nothing'}")
    shown = visible_text(fetch(web, here)[1])
    failures += [f"{here}: the moved assembly does not show {w!r}" for w in (f"Кога {date(str(moved_day))} 19:00", f"Къде {moved_place}") if w not in shown]
    # PM-GA-004: six days out leaves no room for a seven-day notice — refused in the API's words, nothing moved
    too_soon = datetime.datetime.now(ZoneInfo("Europe/Sofia")).date() + datetime.timedelta(days=6)
    status, back, refused_page = submit_form(web, here, fetch(web, here)[1], 'name="wasNoticed"', {"scheduledAt": f"{too_soon}T19:00", "place": moved_place})
    if status != 200 or back or "Системата не прие това:" not in visible_text(refused_page) or stored().get("place") != moved_place or \
            datetime.datetime.fromisoformat(stored()["scheduledAt"].replace("Z", "+00:00")) != sofia(moved_day, datetime.time(19, 0)):
        failures.append(f"{here}: PM-GA-004 — moving it six days out answers HTTP {status} → {back or 'the page'}; expected the API's refusal, the meeting where it was")

    time.sleep(1.1)              # the posting is after the move to the second: a notice posted before it stated another time (PM-GA-006)
    posted = datetime.datetime.now(ZoneInfo("Europe/Sofia")).replace(microsecond=0, tzinfo=None).isoformat()
    photo = {"photo": ("notice.jpg", b"\xff\xd8\xff\xe0" + uuid.uuid4().bytes)}   # any bytes: the API keeps their SHA-256
    nobody = str(uuid.uuid4())
    status, back, refused_page = submit_form(web, here, fetch(web, here)[1], 'name="coSignatoryPartyId"',
                                             {"postedAt": posted, "coSignatoryPartyId": nobody}, photo)
    said = visible_text(refused_page)
    if status != 200 or back or "Системата не прие това:" not in said or f'value="{nobody}"' not in refused_page or stored().get("status") != "DRAFT":
        failures.append(f"{here}: PM-GA-007 — a posting act co-signed by no owner answers HTTP {status} → {back or 'the page'} — expected the API's refusal (PM-GA-007), the form kept, the assembly a draft")
    if not owner_party:
        if os.environ.get("CI"):                    # CI seeds an owner and passes it: a missing one is a broken run, not a skip
            return failures + ["PM-GA-007 — the notice posted through the page NOT checked in CI: no --owner-party"]
        print("—   /entrance/assembly  the notice posted and the noticed assembly moved NOT checked: no --owner-party to co-sign")
        return failures

    status, back, _ = submit_form(web, here, fetch(web, here)[1], 'name="coSignatoryPartyId"', {"postedAt": posted, "coSignatoryPartyId": owner_party}, photo)
    a = stored()
    if status != 303 or back.removeprefix(web) != here or a.get("status") != "NOTICED" or not a.get("noticePostedAt"):
        return failures + [f"{here}: posting the notice answers HTTP {status} → {back or 'nowhere'}; the API keeps it {a.get('status', 'unread')} — expected NOTICED (PM-GA-007, PM-SYS-013)"]
    shown = visible_text(fetch(web, here)[1])
    failures += [] if f"Покана обявена на {date(posted[:10])} {posted[11:16]}" in shown and "Обявяване на поканата" not in shown else [
        f"{here}: the noticed assembly does not show the posting at {posted}, or still offers to post it"]
    failures += sitting_too_soon(api, web, here, of)
    failures += register_too_soon(api, web, here, of, owner_party)
    failures += votes_before_open(api, web, here, of, owner_party)
    failures += decision_before_open(api, web, here, of)
    failures += absentee_before_open(api, web, here, of)
    failures += protocol_before_open(api, web, here, of)

    status, back = move(moved_day + datetime.timedelta(days=1), moved_place)
    a = stored()
    if status != 303 or back != here + "&moved=1" or a.get("status") != "DRAFT" or a.get("noticePostedAt"):
        failures.append(f"{here}: PM-GA-006 — moving the noticed assembly answers HTTP {status} → {back or 'nowhere'}; the API keeps it {a.get('status', 'unread')} — expected a draft whose notice is void (PM-GA-006)")
    elif "Събранието е преместено след обявяването на поканата" not in visible_text(fetch(web, back)[1]):
        failures.append(f"{back}: the page does not say the notice is void")
    elif 'name="weigh"' in fetch(web, here)[1]:
        failures.append(f"{here}: PM-GA-012 — the draft again still offers to weigh a sitting")
    elif 'name="attendeePartyId"' in fetch(web, here)[1]:
        failures.append(f"{here}: PM-GA-012 — the draft again still offers to enter a person in the register")
    return failures


def sitting_too_soon(api, web, here, of):
    """
    WEB-29 (PM-GA-012, PM-GA-013, PM-GA-014) on the assembly moved_and_noticed has just noticed, weeks out: weighing its
    first sitting through the page's own form is refused in the API's words — not before the hour its notice stated —
    nothing weighed, the assembly still NOTICED. An opened sitting cannot be reached here: the API's clock is real.
    """
    page = fetch(web, here)[1]
    status, raw = fetch(api, of + "/quorum")
    at_hand = json.loads(raw).get("sessionNo") if status == 200 else None
    if f"открий заседание № {at_hand}" not in visible_text(page):
        return [f"{here}: PM-GA-012 — the noticed assembly does not offer to weigh sitting № {at_hand} (the meter's, HTTP {status})"]
    status, back, refused_page = submit_form(web, here, page, 'name="weigh"', {})
    said = visible_text(refused_page)
    status_q, raw = fetch(api, of + "/quorum")
    if status_q != 200:
        return [f"{here}: GET …/quorum after the refused weighing: HTTP {status_q} — cannot tell whether a sitting was weighed"]
    weighed = json.loads(raw).get("sittings")
    st, raw = fetch(api, of)
    kept = json.loads(raw).get("status") if st == 200 else None
    if status != 200 or back or "Системата не прие това:" not in said or "does not open before" not in said or weighed != [] or kept != "NOTICED":
        return [f"{here}: PM-GA-013 — weighing the first sitting weeks before its hour answers HTTP {status} → {back or 'the page'}; "
                f"sittings {weighed}, the assembly {kept} — expected the API's refusal, nothing weighed, still NOTICED"]
    return []


def register_too_soon(api, web, here, of, owner_party):
    """
    WEB-30 (PM-GA-012, PM-GA-016, PM-SEC-001) on the same noticed assembly, weeks out: entering an owner in the register
    through the page's own form is refused in the API's words — the register is kept on the sitting's day — the
    party id kept in the form, the register still empty. A line on the day, and its identity verified, cannot be
    reached here: the API's clock is real.
    """
    status, back, refused_page = submit_form(web, here, fetch(web, here)[1], 'name="attendeePartyId"', {"attendeePartyId": owner_party, "channel": "IN_PERSON"})
    said = visible_text(refused_page)
    st, raw = fetch(api, of + "/attendance")
    register = json.loads(raw) if st == 200 else None
    if status != 200 or back or "Системата не прие това:" not in said or "is kept on" not in said or f'value="{owner_party}"' not in refused_page or register != []:
        return [f"{here}: PM-GA-012 — entering an owner in the register weeks before the day answers HTTP {status} → {back or 'the page'}; "
                f"the register {register if st == 200 else f'unread (HTTP {st})'} — expected the API's refusal, the form kept, nobody entered"]
    return []


def votes_before_open(api, web, here, of, owner_party):
    """
    WEB-31 (PM-VOTE-001, PM-SEC-001, PM-GOV-008) on the same noticed assembly: the page offers no vote and reads no
    record before the sitting opens; a vote posted on its agenda item by a member of the board who did not convene it
    is refused by the API naming PM-SEC-001 — a vote is recorded by whoever runs the assembly — and the item's record,
    read as the check's controller (PM-GOV-008), is empty. A vote at an opened sitting cannot be reached here: the
    API's clock is real, and only the person signed in to the web runs this assembly.
    """
    page = fetch(web, here)[1]
    if 'name="choice"' in page or "Гласуване — точка" in visible_text(page):
        return [f"{here}: PM-VOTE-001 — the noticed assembly offers a vote, or a vote record, before its sitting opens"]
    st, raw = fetch(api, of)
    agenda = json.loads(raw).get("agenda", []) if st == 200 else []
    if not agenda:
        return [f"{of}: the noticed assembly has no agenda item to vote on (HTTP {st})"]
    votes = f"{of}/agenda/{agenda[0]['id']}/votes"
    # posted as the board's other member (E2E_MANAGER_TOKEN): of the same office, but not the person who convened it
    status, said = fetch(api, votes, body={"ownerPartyId": owner_party, "choice": "FOR"}, headers=as_board())
    st, raw = fetch(api, votes)
    record = json.loads(raw) if st == 200 else None
    if status != 403 or "PM-SEC-001" not in said or record != []:
        return [f"{votes}: PM-SEC-001 — a vote by a board member who does not run the assembly answers HTTP {status} {said[:120]!r}; "
                f"the record {record if st == 200 else f'unread (HTTP {st})'} — expected the API's 403 naming PM-SEC-001 and an empty record"]
    return []


def decision_before_open(api, web, here, of):
    """
    WEB-32 (PM-VOTE-014, PM-VOTE-016, PM-SEC-001) on the same noticed assembly: the page offers no close and shows no
    decision before the sitting opens; closing its agenda item, posted by the board's other member (the same office, not
    the person who convened it), is refused by the API naming PM-SEC-001, and the item, read as the check's controller,
    has no decision — the API's 404 for an item not closed. A close at an opened sitting cannot be reached here (the
    API's clock is real); its tally and status are the assembly lane's rule-named tests.
    """
    page = fetch(web, here)[1]
    if 'name="final"' in page or "Гласуване неприсъствено до" in visible_text(page):   # the close form's checkbox; a line only a decision shows
        return [f"{here}: PM-VOTE-014 — the noticed assembly offers to close an item, or shows a decision, before its sitting opens"]
    st, raw = fetch(api, of)
    agenda = json.loads(raw).get("agenda", []) if st == 200 else []
    if not agenda:
        return [f"{of}: the noticed assembly has no agenda item to close (HTTP {st})"]
    decision = f"{of}/agenda/{agenda[0]['id']}/decision"
    status, said = fetch(api, decision, body={}, headers=as_board())
    read = fetch(api, decision)[0]
    if status != 403 or "PM-SEC-001" not in said or read != 404:
        return [f"{decision}: PM-SEC-001 — closing by a board member who does not run the assembly answers HTTP {status} {said[:120]!r}; "
                f"the decision reads HTTP {read} — expected the API's 403 naming PM-SEC-001 and a 404 (not closed)"]
    return []


def absentee_before_open(api, web, here, of):
    """
    WEB-33 (PM-GA-017, PM-VOTE-013, PM-SEC-001) on the same noticed assembly: its item is tagged for absentee ballots
    but not closed, so the page offers no ballot and no merge; an absent owner's ballot and the merge, posted by the
    board's other member (not the person who convened it), are each refused by the API naming PM-SEC-001. A ballot or a
    merge on an item closed at an opened sitting cannot be reached here (the API's clock is real); they are the assembly
    lane's rule-named tests. No ballot is read: the API serves none (#298).
    """
    page = fetch(web, here)[1]
    if 'name="documentSha256"' in page or "Слей бюлетините" in visible_text(page):
        return [f"{here}: PM-GA-017 — the noticed assembly offers an absentee ballot or a merge on an item not closed"]
    st, raw = fetch(api, of)
    agenda = json.loads(raw).get("agenda", []) if st == 200 else []
    tagged = [i for i in agenda if i.get("absenteeEligible")]
    if not tagged:
        return [f"{of}: the noticed assembly has no item tagged for absentee ballots (HTTP {st})"]
    item = f"{of}/agenda/{tagged[0]['id']}"
    ballot = {"ownerPartyId": str(uuid.uuid4()), "choice": "FOR", "signature": "HANDWRITTEN", "documentRef": "e2e",
              "documentSha256": "0" * 64, "receivedOn": datetime.datetime.now(ZoneInfo("Europe/Sofia")).date().isoformat()}
    failures = []
    for what, path, body in (("a ballot", f"{item}/absentee-ballots", ballot), ("the merge", f"{item}/absentee-merge", {})):
        status, said = fetch(api, path, body=body, headers=as_board())
        if status != 403 or "PM-SEC-001" not in said:
            failures.append(f"{path}: PM-SEC-001 — {what} by a board member who does not run the assembly answers HTTP {status} {said[:120]!r} — expected the API's 403 naming PM-SEC-001")
    return failures


def protocol_before_open(api, web, here, of):
    """
    WEB-34 (PM-GA-023, PM-SEC-001) on the same noticed assembly: no sitting opened, so the page offers no protocol
    posting; a posting by the board's other member (not the person who convened it) is the API's 403 naming PM-SEC-001.
    The convener's own 409 (no sitting opened) is not asked: the convener signs in only through the web, and the API asks
    the policy before the state, so every bearer this check holds is a 403. A posting after an opened sitting cannot be
    reached here (the API's clock is real); it is the assembly lane's rule-named tests (DecisionStandingTest,
    VotePersistenceIT).
    """
    if 'name="postedOn"' in fetch(web, here)[1]:
        return [f"{here}: PM-GA-023 — the noticed assembly offers to enter its protocol's posting before any sitting opened"]
    path, body = f"{of}/protocol-posting", {"postedOn": datetime.datetime.now(ZoneInfo("Europe/Sofia")).date().isoformat()}
    failures = []
    status, said = fetch(api, path, body=body, headers=as_board())
    if status != 403 or "PM-SEC-001" not in said:
        failures.append(f"{path}: PM-SEC-001 — a posting by a board member who does not run the assembly answers HTTP {status} {said[:120]!r} — expected the API's 403 naming PM-SEC-001")
    return failures


def presets(page):
    """The field each column's select is preset to, in the form's order."""
    selects = re.findall(r'<select[^>]*name="field-(\d+)"[^>]*>(.*?)</select>', page, re.S)
    chosen = lambda options: next(iter(re.findall(r'<option(?=[^>]*\bselected\b)[^>]*value="([^"]*)"', options)), "")
    return [chosen(options) for _, options in sorted(selects, key=lambda s: int(s[0]))]


def charges_issue(api, web, entrance):
    """
    WEB-38 (PM-FEE-012, PM-FEE-015, PM-SEC-001) — the issue form on `/entrance/charges`: the decisions it offers are the
    entrance's PASSED ones as the assembly API stores them, read here the same way; with none it says no run is issued.
    An issue posted through the form, as a browser with no script posts it, citing a decision that is not one (the demo
    basis's) is refused by the web, and the API then holds no run for the period. A passed decision cannot be reached in
    CI (the API's clock is real, WEB-32), so an issue through the page is not; the API's own refusals are its tests.
    """
    period = "2027-01"
    path = f"/entrance/charges?period={period}&entrance={entrance}"
    status, page = fetch(web, path)
    if status != 200 or "Издаване на начисленията" not in page:
        return [f"{path}: HTTP {status}, or no issue form"]
    of = f"/api/assembly/entrances/{entrance}/assemblies"
    passed = []
    for a in json.loads(fetch(api, of)[1] or "[]"):
        for item in json.loads(fetch(api, f"{of}/{a['id']}")[1]).get("agenda", []):
            st, raw = fetch(api, f"{of}/{a['id']}/agenda/{item['id']}/decision")
            passed += [json.loads(raw)["id"]] if st == 200 and json.loads(raw).get("outcome") == "PASSED" else []
    offered = re.findall(r'<option value="([0-9a-f-]{36})"', next(iter(re.findall(r'<select[^>]*name="decision-0".*?</select>', page, re.S)), ""))
    failures = [] if sorted(offered) == sorted(passed) else [f"{path}: offers the decisions {offered}, the API's PASSED ones are {passed}"]
    if not passed and "Няма прието решение на общото събрание" not in visible_text(page):
        failures.append(f"{path}: with no passed decision, does not say no run can be issued")
    fields = {"stream-0": "MANAGEMENT", "key-0": "PER_PERSON", "per-0": "rate", "amount-0": "6,00", "decision-0": "GA-2026-03-12-4", "step": "issue"}
    status, _, answered = submit_form(web, path, page, "Издаване на начисленията", fields)
    text = visible_text(answered)
    if status != 200 or "Ред 1: изберете прието решение" not in text or "Издадено:" in text:
        failures.append(f"{path}: an issue citing no passed decision answered HTTP {status} without the web's refusal")
    held = fetch(api, f"/api/money/entrances/{entrance}/charge-runs/{period}/headcount", headers=as_board())[0]
    failures += [] if held == 404 else [f"{period}: PM-FEE-012 — the API holds a run (headcount HTTP {held}) after an issue citing no passed decision"]
    print(f"{'ok ' if not failures else 'BAD'} PM-FEE-012 the issue form: {len(passed)} passed decision(s) offered; an issue on none refused, no run held")
    return failures


BAND = {"CURRENT": "в срок", "0-30": "0–30 дни", "31-60": "31–60 дни", "61-90": "61–90 дни", "90+": "над 90 дни"}   # web/lib/statement.ts


def statement_screen(api, web, entrance):
    """
    WEB-40 (PM-FEE-018, PM-FEE-017, PM-DEBT-001) — `/entrance/statement`: a unit's statement and arrears as of a date
    show what the API serves for that unit — the balance, every period, every line's derivation and amount, and the
    arrears' total, advance and net. Both reads are validated against the contract in CALLS; this checks the page.
    """
    unit, as_of = a_unit(entrance), "2026-09-30"
    path = f"/entrance/statement?entrance={entrance}&unit={unit}&asOf={as_of}"
    s_status, s_raw = fetch(api, f"/api/money/units/{unit}/statement")
    a_status, a_raw = fetch(api, f"/api/money/units/{unit}/arrears?asOf={as_of}")
    if s_status != 200 or a_status != 200:
        return [f"{path}: the API's reads answer HTTP {s_status} (statement), {a_status} (arrears)"]
    statement, arrears = json.loads(s_raw), json.loads(a_raw)
    status, page = fetch(web, path)
    text = visible_text(page)
    want = [f"Извлечение · салдо {eur(statement['balanceMinor'])}",
            f"Дължимо {eur(arrears['totalMinor'])} · аванс {eur(arrears['advanceMinor'])} · нетно {eur(arrears['netMinor'])}",
            *[f"Период {p}" for p in dict.fromkeys(line["period"] for line in statement["lines"])],
            *[f"{line['derivation']} {eur(line['amountMinor'])}" for line in statement["lines"]]]
    want += [f"{BAND.get(b['band'], b['band'])}: {eur(b['amountMinor'])}" for b in arrears["buckets"]]
    if any(line["allocationKey"] == "METERED" for line in statement["lines"]):
        want.append("По потребление — отделно от законовите ключове")   # PM-FEE-017: consumption lines apart
    if arrears.get("oldestDebt"):
        want.append(".".join(reversed(arrears["oldestDebt"]["dueOn"].split("-"))))
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    failures += [f"{path}: does not show {w!r}, the API's" for w in want if w not in text]
    print(f"{'ok ' if not failures else 'BAD'} PM-FEE-018 the unit's statement: {len(statement['lines'])} line(s), "
          f"balance {eur(statement['balanceMinor'])}; arrears {eur(arrears['netMinor'])} net as of {as_of}")
    return failures


def fund_accounts_screen(api, web, entrance):
    """
    WEB-41 (PM-FUND-004, PM-FUND-001) — `/entrance/fund/accounts`: every account the API lists for the entrance is
    shown under its purpose, with its IBAN and its holder; the seeded entrance holds both purposes, so the page flags
    no missing fund and offers no form. A second registration is the API's 409, validated in CALLS.
    """
    path = f"/entrance/fund/accounts?entrance={entrance}"
    a_status, raw = fetch(api, f"/api/money/entrances/{entrance}/fund-accounts")
    if a_status != 200:
        return [f"{path}: the API's list answers HTTP {a_status}"]
    accounts = json.loads(raw)
    status, page = fetch(web, path)
    text = visible_text(page)
    words = {"REPAIR_RENEWAL": "Сметка на фонд „Ремонт и обновяване“", "OPERATING": "Оперативна сметка"}
    want = [w for a in accounts for w in (words.get(a["purpose"], a["purpose"]), f"IBAN {a['iban']}", f"Титуляр: {a['holderName']}", f"({a['holderKind']})")]
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    failures += [f"{path}: does not show {w!r}, the API's" for w in want if w not in text]
    if {a["purpose"] for a in accounts} >= set(words):
        failures += [f"{path}: both accounts are held, yet it flags a missing fund (PM-FUND-001)"] if "Входът няма регистрирана сметка" in text else []
        failures += [f"{path}: both accounts are held, yet it offers a registration form (PM-FUND-004)"] if 'name="iban"' in page else []
    # PM-FUND-005: the seeded fund's IBAN, offered again, is refused for the IBAN — the API checks it before the purpose
    p_status, p_raw = fetch(api, f"/api/money/entrances/{entrance}/fund-accounts",
                            {"purpose": "OPERATING", "iban": SEEDED_FUND_IBAN, "holderName": "e2e", "holderKind": "MANAGER"}, headers=as_board())
    if p_status != 409 or "PM-FUND-005" not in (refusal_words(p_raw) or ""):
        failures.append(f"POST …/fund-accounts: the seeded fund's IBAN again answers HTTP {p_status} {p_raw[:160]!r}, not the 409 naming PM-FUND-005")
    print(f"{'ok ' if not failures else 'BAD'} PM-FUND-004, PM-FUND-001, PM-FUND-005 the entrance's accounts: {len(accounts)} listed, each with its IBAN and holder")
    return failures


def disbursements_screen(api, web, entrance, signed_in):
    """
    WEB-42 (PM-FUND-006…008, PM-SEC-001), WEB-42b (PM-FUND-007) — `/entrance/fund/disbursements`: every COMMITTED
    disbursement the API lists is on the page, signed in with a pay and a cancel form each; no paid or cancelled one
    is listed or offered either. A sign-off posted
    through the page's form by the person signed in — a board member (e2e.yml), not the fund's holder — is refused in the
    API's words naming PM-FUND-007, the form kept, nothing recorded. An emergency above what is available, signed by the
    holder with the board's token, is the API's 409 naming PM-FUND-008.
    """
    path = f"/entrance/fund/disbursements?entrance={entrance}"
    a_status, raw = fetch(api, f"/api/money/entrances/{entrance}/fund")
    if a_status != 200:
        return [f"{path}: the API's fund answers HTTP {a_status}"]
    listed = json.loads(raw)["disbursements"]
    status, page = fetch(web, path)
    text = visible_text(page)
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    committed = [d for d in listed if d["status"] == "COMMITTED"]
    failures += [f"{path}: the committed {d['id']} is not listed" for d in committed if f"{eur(d['amountMinor'])} ·" not in text
                 or (d.get("emergencyJustification") or d.get("decisionId")) not in text]
    failures += [f"{path}: the {d['status'].lower()} {d['id']} is listed" for d in listed if d["status"] != "COMMITTED"
                 and f"{eur(d['amountMinor'])} ·" in text and (d.get("emergencyJustification") or d.get("passportMeasure") or d["decisionId"]) in text]
    failures += [f"{path}: the {d['status'].lower()} {d['id']} is offered a pay or cancel form" for d in listed if d["status"] != "COMMITTED" and d["id"] in page]
    if signed_in:
        failures += [f"{path}: the committed {d['id']} has no pay and cancel form" for d in committed if page.count(f'value="{d["id"]}"') != 2]
        fields = {"purpose": "WORKS", "amount": "1,00", "measure": "", "basis": "EMERGENCY", "decision": "", "justification": "e2e: signed by the board"}
        status, _, refused_page = submit_form(web, path, page, 'name="justification"', fields)
        said = visible_text(refused_page)
        if status != 200 or "Системата не прие това:" not in said or "PM-FUND-007" not in said or 'value="e2e: signed by the board"' not in refused_page:
            failures.append(f"{path}: a sign-off by a party not holding the fund answered HTTP {status} without the API's words, or the form not kept")
        after = fetch(api, f"/api/money/entrances/{entrance}/fund")[1]
        failures += [] if len(json.loads(after)["disbursements"]) == len(listed) else [f"{path}: a refused sign-off recorded a disbursement"]
    holder = fund_holder(entrance)
    e_status, e_raw = fetch(api, f"/api/money/entrances/{entrance}/fund/disbursements",
                            {"amountMinor": 1_000_000, "purpose": "WORKS", "authorisedBy": holder, "emergencyJustification": "e2e: above what is available"},
                            headers={**(as_board() or {}), "Idempotency-Key": str(uuid.uuid4())})
    if as_board() and (e_status != 409 or "PM-FUND-008" not in (refusal_words(e_raw) or "")):
        failures.append(f"POST …/fund/disbursements: an emergency above what is available answers HTTP {e_status} {e_raw[:160]!r}, not the 409 naming PM-FUND-008")
    print(f"{'ok ' if not failures else 'BAD'} PM-FUND-006…008 the disbursements: {len(committed)} committed listed, "
          f"{len(listed) - len(committed)} closed not; {'a sign-off by a non-holder refused' if signed_in else 'forms NOT checked: no sign-in'}"
          f"{'' if as_board() else '; the emergency 409 NOT checked: no board token'}")
    return failures


# The book and its log are served only to a reader the sign-in names (PM-BOOK-006): with no token, both are the 403.
BOOK_READS = ("/api/registry/entrances/{entranceId}/book", "/api/registry/entrances/{entranceId}/book/access-log")
# A title's role in the page's words (web/lib/book.ts).
ROLE = {"OWN": "собственик", "USR": "ползвател"}


def book_screen(api, web, entrance, signed_in):
    """
    WEB-44 (PM-BOOK-001, PM-BOOK-002, PM-BOOK-006, PM-BOOK-007, PM-BOOK-011, PM-SEC-001) — `/entrance/book`: the page
    reads nothing of the book until a purpose is typed; a read with none is refused by the page, and the API's log gains
    no read. Signed in (a reader: the board), the seeded book is shown as the API serves it for the same purpose — every
    unit, its parties by name, role and share, complete or not — and the log, read through the same form, lists that
    read with the purpose typed, oldest first. With no sign-in (the api open), the read is the API's 403, in its words.
    """
    path = f"/entrance/book?entrance={entrance}"
    status, page = fetch(web, path)
    text = visible_text(page)
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    failures += [] if "Четене на книгата" in text and "Книгата към" not in text else [f"{path}: the page is not the purpose form alone — it read the book unasked"]
    reads = lambda: [v for v in json.loads(fetch(api, f"/api/registry/entrances/{entrance}/book/access-log?purpose=e2e%3A%20count")[1]) if v["kind"] == "BOOK_READ"]
    before = reads() if signed_in else None
    s_status, _, no_purpose = submit_form(web, path, page, 'name="purpose"', {"on": "", "purpose": "  ", "what": "book"})
    if s_status != 200 or "Посочете с каква цел четете книгата" not in visible_text(no_purpose) or "Книгата към" in visible_text(no_purpose):
        failures.append(f"{path}: a read with no purpose answered HTTP {s_status} without the page's refusal, or showed a book")
    if signed_in and len(reads()) != len(before):
        failures.append(f"{path}: a read with no purpose reached the API — its log gained a read")
    purpose = f"e2e: проверка {uuid.uuid4().hex[:8]}"
    b_status, _, read_page = submit_form(web, path, page, 'name="purpose"', {"on": "", "purpose": purpose, "what": "book"})
    shown = visible_text(read_page)
    a_status, raw = fetch(api, f"/api/registry/entrances/{entrance}/book?purpose=" + urllib.parse.quote(purpose))
    if signed_in:
        book = json.loads(raw) if a_status == 200 else None
        if not book or not book["units"]:
            return failures + [f"{path}: the API serves the board no book (HTTP {a_status}), or one with no unit — the seed enters six"]
        want = [f"Книгата към {'.'.join(reversed(book['asOf'].split('-')))} · {'пълно' if book['complete'] is True else 'непълно'}"]
        for u in book["units"]:
            want += [u["designation"]] + [f"{p['name']} · {ROLE.get(p['role'], p['role'])} · дял {p['share']}" for p in u["parties"]]
        failures += [f"{path}: HTTP {b_status} reading the book"] if b_status != 200 else []
        failures += [f"{path}: the book read does not show {w!r}" for w in want if w not in shown]
        failures += [] if shown.count("пълно") >= len(book["units"]) else [f"{path}: not every unit says whether it is complete"]
        l_status, _, log_page = submit_form(web, path, page, 'name="purpose"', {"on": "", "purpose": purpose, "what": "log"})
        logged = visible_text(log_page)
        mine = [v for v in reads() if v["purpose"] == purpose]
        failures += [] if l_status == 200 and "Дневник на книгата" in logged and f"цел: {purpose}" in logged else [f"{path}: the log read through the form does not list the read made for {purpose!r}"]
        failures += [] if len(mine) == 2 and all(v["outcome"] == "SERVED" for v in mine) else [f"{path}: the API logged {len(mine)} reads for the purpose typed, not the page's one and the check's one, served"]
    else:
        words = refusal_words(raw)
        if a_status != 403 or "Нямате право да четете книгата на етажната собственост:" not in shown or not words or words not in shown or "Книгата към" in shown:
            failures.append(f"{path}: with no sign-in the API answers HTTP {a_status}; the page does not show its 403 in its words ({words!r})")
    print(f"{'ok ' if not failures else 'BAD'} PM-BOOK-007 the book: read only with a purpose, none refused by the page; "
          f"{'the seeded book shown as served, and the read in its log' if signed_in else 'the API refusal shown in its words (no sign-in: a non-reader)'}")
    return failures


def household_screen(api, web, entrance, signed_in):
    """
    WEB-45 (PM-BOOK-008, PM-FEE-005, PM-FEE-008, PM-BOOK-005, PM-SEC-001) — `/entrance/household`: every unit the API
    lists is offered. Signed in (a board member, e2e.yml: the book is entered by the manager or the board), a resident
    entered through the page's form is read back in the book's own household count for the days it holds; the leaving
    day recorded through the page's second form closes the stay as the API says (the half-open range: the day itself
    is no longer counted), and a day not after the move-in is refused in the API's words, the form kept. The check's
    own token is the controller's: its entry is the API's 403, which writes nothing.
    """
    path = f"/entrance/household?entrance={entrance}"
    a_status, raw = fetch(api, f"/api/registry/entrances/{entrance}/units")
    if a_status != 200 or not json.loads(raw):
        return [f"{path}: the API's units answer HTTP {a_status}, or list none (the seed enters six)"]
    units = json.loads(raw)
    status, page = fetch(web, path)
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    failures += [f"{path}: the unit {u['designation']} ({u['id']}) is not offered" for u in units if f'value="{u["id"]}"' not in page]
    failures += [] if 'name="kind0"' in page and 'name="member"' in page else [f"{path}: the household form or the leaving form is missing"]
    if signed_in:
        unit = sorted(units, key=lambda u: u["designation"])[0]["id"]
        count = lambda on: next((u["householdCount"] for u in json.loads(fetch(api, f"/api/registry/entrances/{entrance}/book?purpose=e2e%3A%20household&on={on}")[1]).get("units", []) if u["unitId"] == unit), None)
        inside, after = count("2026-10-10"), count("2026-10-20")
        if inside is None or after is None:
            return failures + [f"{path}: the book the API serves holds no unit {unit}, or no book is served to the check's token"]
        # PM-SEC-001 — the controller (this check's own token) may not enter the book: the API's 403, in its words, nothing written
        c_status, c_raw = fetch(api, f"/api/registry/entrances/{entrance}/units/{unit}/household", {"members": [{"isChildUnder6": False, "validFrom": "2026-10-01"}]})
        failures += [] if c_status == 403 and refusal_words(c_raw) else [f"POST …/household: the controller's entry answers HTTP {c_status} {c_raw[:120]!r}, not the API's 403 in its words"]
        failures += [] if count("2026-10-10") == inside else [f"{path}: the controller's refused entry changed the household count"]
        # PM-FEE-005, PM-FEE-008, PM-BOOK-008 — a resident entered through the form, from a day, as typed
        fields = {"unit": unit, "kind0": "adult", "from0": "2026-10-01", "kind1": "", "from1": "", "kind2": "", "from2": "", "kind3": "", "from3": ""}
        e_status, _, entered = submit_form(web, path, page, 'name="kind0"', fields)
        shown = visible_text(entered)
        member = re.search(r"Номера им, за записване на напускане: ([0-9a-f-]{36})", shown)
        if e_status != 200 or "Вписани са 1 живущи." not in shown or not member:
            return failures + [f"{path}: the household form answered HTTP {e_status} without the API's member id: {shown[:200]!r}"]
        failures += [] if count("2026-10-10") == inside + 1 else [f"{path}: the book's household count on 2026-10-10 is not {inside} + the resident entered"]
        # PM-BOOK-008 — a day not after the move-in is the API's refusal, in its words; the form is kept, nothing closed
        l_fields = {"unit": unit, "member": member.group(1), "on": "2026-10-01"}
        r_status, _, refused = submit_form(web, path, entered, 'name="member"', l_fields)
        said = visible_text(refused)
        failures += [] if r_status == 200 and "Системата не прие това:" in said and 'value="2026-10-01"' in refused else [f"{path}: a leaving on the day of the move-in answered HTTP {r_status} without the API's words, or the form not kept"]
        failures += [] if count("2026-10-20") == after + 1 else [f"{path}: a refused leaving closed the stay"]
        # a day after it closes the stay: [2026-10-01, 2026-10-15)
        c_status, _, left = submit_form(web, path, entered, 'name="member"', {**l_fields, "on": "2026-10-15"})
        failures += [] if c_status == 200 and "Престоят е затворен: от 2026-10-01 до 2026-10-15." in visible_text(left) else [f"{path}: the leaving form does not show the stay closed as the API says"]
        failures += [] if count("2026-10-10") == inside + 1 and count("2026-10-20") == after else [f"{path}: after the leaving day the book's household count is not as the range says"]
    print(f"{'ok ' if not failures else 'BAD'} PM-BOOK-008 the household: {len(units)} units offered; "
          f"{'a resident entered, its stay closed, read back in the book; the controller refused' if signed_in else 'forms NOT checked: no sign-in'}")
    return failures


def animals_screen(api, web, entrance, signed_in):
    """
    WEB-46 (PM-BOOK-005, PM-BOOK-008, PM-FEE-006, PM-FEE-007, PM-SEC-001) — `/entrance/animals`: every unit the API lists
    is offered. Signed in (a board member, e2e.yml: the book is entered by the manager or the board), an animal entered
    through the page's form with a veterinary passport number answers its id; a leaving on the day of the move-in is
    refused in the API's words, the form kept, and a later day closes the stay as the API says. An absence entered
    through the third form is read back in the book's own `nonUse`. The check's own token is the controller's: its
    entries are the API's 403, which writes nothing.
    """
    path = f"/entrance/animals?entrance={entrance}"
    a_status, raw = fetch(api, f"/api/registry/entrances/{entrance}/units")
    if a_status != 200 or not json.loads(raw):
        return [f"{path}: the API's units answer HTTP {a_status}, or list none (the seed enters six)"]
    units = json.loads(raw)
    status, page = fetch(web, path)
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    failures += [f"{path}: the unit {u['designation']} ({u['id']}) is not offered" for u in units if f'value="{u["id"]}"' not in page]
    failures += [] if 'name="species0"' in page and 'name="animal"' in page and 'name="absentFrom0"' in page else [f"{path}: the animals, leaving or absences form is missing"]
    failures += [] if "#215" in visible_text(page) and "Животните още не се четат в книгата" in visible_text(page) else [f"{path}: the page does not state that an absence entered late is stamped with the day it is entered (#215) and that the book does not serve animals yet"]
    if signed_in:
        unit = sorted(units, key=lambda u: u["designation"])[0]["id"]
        spans = lambda: [(n["from"], n["to"]) for u in json.loads(fetch(api, f"/api/registry/entrances/{entrance}/book?purpose=e2e%3A%20animals")[1]).get("units", []) if u["unitId"] == unit for n in u["nonUse"]]
        before = spans()
        # PM-SEC-001 — the controller (this check's own token) may not enter the book: the API's 403, in its words, nothing written
        for what, url, body in (("animals", "animals", {"animals": [{"species": "куче", "vetPassportNo": "BG-E2E-1"}]}),
                                ("absences", "absences", {"absences": [{"absentFrom": "2026-11-02", "absentTo": "2026-11-09"}]})):
            c_status, c_raw = fetch(api, f"/api/registry/entrances/{entrance}/units/{unit}/{url}", body)
            failures += [] if c_status == 403 and refusal_words(c_raw) else [f"POST …/{what}: the controller's entry answers HTTP {c_status} {c_raw[:120]!r}, not the API's 403 in its words"]
        failures += [] if spans() == before else [f"{path}: the controller's refused entry changed the book's non-use spans"]
        # PM-BOOK-005, PM-BOOK-008 — an animal entered through the form, with its passport number and a day, as typed
        fields = {"unit": unit, "species0": "куче", "passport0": "BG-E2E-1", "from0": "2026-10-01",
                  **{f"{k}{i}": "" for i in (1, 2, 3) for k in ("species", "passport", "from")}}
        e_status, _, entered = submit_form(web, path, page, 'name="species0"', fields)
        shown = visible_text(entered)
        animal = re.search(r"Номера им, за записване на напускане: ([0-9a-f-]{36})", shown)
        if e_status != 200 or "Вписани са 1 животни." not in shown or not animal:
            return failures + [f"{path}: the animals form answered HTTP {e_status} without the API's animal id: {shown[:200]!r}"]
        # PM-BOOK-008 — a day not after the move-in is the API's refusal, in its words; the form is kept, nothing closed
        l_fields = {"unit": unit, "animal": animal.group(1), "on": "2026-10-01"}
        r_status, _, refused = submit_form(web, path, entered, 'name="animal"', l_fields)
        said = visible_text(refused)
        failures += [] if r_status == 200 and "Системата не прие това:" in said and 'value="2026-10-01"' in refused else [f"{path}: a leaving on the day of the move-in answered HTTP {r_status} without the API's words, or the form not kept"]
        # a day after it closes the stay: [2026-10-01, 2026-10-15)
        c_status, _, left = submit_form(web, path, entered, 'name="animal"', {**l_fields, "on": "2026-10-15"})
        failures += [] if c_status == 200 and "Престоят е затворен: от 2026-10-01 до 2026-10-15." in visible_text(left) else [f"{path}: the leaving form does not show the stay closed as the API says"]
        # PM-BOOK-008, PM-FEE-006, PM-FEE-007 — an absence entered through the form, read back in the book's `nonUse`
        a_fields = {"unit": unit, "absentFrom0": "2026-11-02", "absentTo0": "2026-11-09", **{f"{k}{i}": "" for i in (1, 2) for k in ("absentFrom", "absentTo")}}
        s_status, _, absent = submit_form(web, path, entered, 'name="absentFrom0"', a_fields)
        failures += [] if s_status == 200 and "Вписани са 1 отсъствия." in visible_text(absent) else [f"{path}: the absences form answered HTTP {s_status} without the API's absence id"]
        after = spans()
        failures += [] if after.count(("2026-11-02", "2026-11-09")) == before.count(("2026-11-02", "2026-11-09")) + 1 else [f"{path}: the book's non-use spans do not hold the absence entered, once more than before"]
    print(f"{'ok ' if not failures else 'BAD'} PM-BOOK-005 the animals: {len(units)} units offered; "
          f"{'an animal entered and its stay closed, an absence read back in the book; the controller refused' if signed_in else 'forms NOT checked: no sign-in'}")
    return failures


def declarations_screen(api, web, entrance, signed_in, owner_party=None):
    """
    WEB-47 (PM-BOOK-003, PM-BOOK-004, PM-BOOK-005, PM-BOOK-010, PM-SEC-001) — `/entrance/declarations`: every unit the API
    lists is offered, with the two kinds of declaration and the retention form's confirmation. Signed in (a board member),
    a declaration recorded through the page for the seeded owner Стефан Димов (`--owner-party`) shows the date and the
    template the API answered, with its id, and the owner leaves the API's overdue list; a declarant who holds no such
    unit is the API's refusal in its words, the form kept. The check's own token is the controller's: its filing in
    another's name is the API's 403. The retention run is NEVER made here (it anonymises for good; the seeded demo stays as
    seeded, the owner 2026-10-29): the page's form is checked for its confirmation only (the guard in the action is the
    source test's), and the controller's 403 is checked in the contract loop above.
    """
    path = f"/entrance/declarations?entrance={entrance}"
    a_status, raw = fetch(api, f"/api/registry/entrances/{entrance}/units")
    if a_status != 200 or not json.loads(raw):
        return [f"{path}: the API's units answer HTTP {a_status}, or list none (the seed enters six)"]
    units = json.loads(raw)
    status, page = fetch(web, path)
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    failures += [f"{path}: the unit {u['designation']} ({u['id']}) is not offered" for u in units if f'value="{u["id"]}"' not in page]
    failures += [f"{path}: the kind {k} is not offered" for k in ("ACQUISITION", "CHANGE") if f'value="{k}"' not in page]
    failures += [] if 'name="declarant"' in page and re.search(r'<input(?=[^>]*name="confirm")(?=[^>]*type="checkbox")(?=[^>]*\brequired\b)[^>]*>', page) else [f"{path}: the declarant field or the retention run's confirmation is missing"]
    # PM-BOOK-010 — the run is never made here, not even without its confirmation: the page's guard is the source test's, and
    # a broken one must not anonymise the demo. The log is read only to see that no run was SERVED (the controller's refused one is logged too).
    if signed_in:
        l_status, l_raw = fetch(api, f"/api/registry/entrances/{entrance}/book/access-log?purpose=e2e%3A%20declarations")
        served = [v for v in json.loads(l_raw) if v["kind"] == "RETENTION" and v["outcome"] == "SERVED"] if l_status == 200 else None
        failures += [f"{path}: the book's log is not served to the check (HTTP {l_status})"] if served is None else \
                    [f"{path}: the book's log holds a retention run served — it must never be made against the seeded demo"] if served else []
    if signed_in and owner_party:
        overdue = lambda: {(v["partyName"], v["unitId"]) for v in json.loads(fetch(api, f"/api/registry/entrances/{entrance}/book/declarations/overdue")[1])}
        mine = [unit for name, unit in overdue() if name == "Стефан Димов"]
        if len(mine) != 1:
            return failures + [f"{path}: the API's overdue list does not name Стефан Димов once (the seed enters one title and files no declaration)"]
        unit = mine[0]
        # PM-SEC-001 — the controller (this check's own token) records nothing in another's name: the API's 403, in its words
        c_status, c_raw = fetch(api, f"/api/registry/entrances/{entrance}/book/declarations", {"unitId": unit, "partyId": owner_party, "kind": "ACQUISITION"})
        failures += [] if c_status == 403 and refusal_words(c_raw) else [f"POST …/declarations: the controller's recording answers HTTP {c_status} {c_raw[:120]!r}, not the API's 403 in its words"]
        failures += [] if ("Стефан Димов", unit) in overdue() else [f"{path}: the controller's refused recording changed the overdue list"]
        # PM-BOOK-005 — a declarant who holds no such unit is the API's 404, in its words; the form is kept, nothing filed
        other = next(u["id"] for u in units if u["id"] != unit)
        d_status, _, refused = submit_form(web, path, page, 'name="declarant"', {"unit": other, "kind": "ACQUISITION", "declarant": owner_party})
        said = visible_text(refused)
        failures += [] if d_status == 200 and "Системата не прие това:" in said and f'value="{owner_party}"' in refused and "Декларацията е подадена" not in said else [f"{path}: a declarant who holds no such unit answered HTTP {d_status} without the API's words, or the form not kept"]
        # PM-BOOK-003, PM-BOOK-004 — recorded in the owner's name (the 15-day window and the day-16 overdue task are the API's): the date and the template are the API's, shown with the id
        f_status, _, filed = submit_form(web, path, page, 'name="declarant"', {"unit": unit, "kind": "ACQUISITION", "declarant": owner_party})
        shown = visible_text(filed)
        made = re.search(r"Декларацията е подадена на (\d{4}-\d{2}-\d{2}), по образец (\S+)\. Номер: ([0-9a-f-]{36})", shown)
        if f_status != 200 or not made:
            return failures + [f"{path}: the declaration form answered HTTP {f_status} without the API's date, template and id: {shown[:200]!r}"]
        failures += [] if ("Стефан Димов", unit) not in overdue() else [f"{path}: the owner is still on the overdue list after the declaration was recorded"]
    print(f"{'ok ' if not failures else 'BAD'} PM-BOOK-003 the declarations: {len(units)} units offered; "
          f"{'a declaration recorded and dated by the API, the overdue list cleared of the owner; the controller refused; the retention run never made' if signed_in and owner_party else 'recording NOT checked: no sign-in or no --owner-party'}")
    return failures


def petitions_screen(api, web, entrance, signed_in, owner=None):
    """
    WEB-48 (PM-GA-003, PM-SEC-001) — `/entrance/petitions`. Signed in as a board member, who is no owner, opening, signing and
    convening are the API's 403 in its words, nothing opened. Signed in as the seeded owner Стефан Димов (`--owner-user`, a
    login the administrator tied to `--owner-party`), a petition opened through the page is shown as the API weighs it —
    held share, threshold, signatories, unlocked or not — read back with the check's own token; signing it again is the
    API's "once" in its words; convening on it is refused in its words while the API holds it locked (the owner holds less than
    the threshold: no assembly is made in the demo). No number is the check's: it compares the page with the API's answer.
    """
    path = f"/entrance/petitions?entrance={entrance}"
    status, page = fetch(web, path)
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    failures += [f"{path}: the {what} form is missing" for what, marker in (("opening", 'name="subject"'), ("reading", 'name="petition"')) if marker not in page]
    if not signed_in:
        print("—   /entrance/petitions  NOT checked: no sign-in")
        return failures
    word = lambda said: "Системата не прие това:" in said      # a 400/409 of the API, in its words — not a 403: the lock and the "once" are the cause, not a role
    day = datetime.datetime.now(ZoneInfo("Europe/Sofia")).date() + datetime.timedelta(days=30)
    s, _, refused = submit_form(web, path, page, 'name="subject"', {"subject": "e2e: ремонт на покрива"})
    said = visible_text(refused)
    failures += [] if s == 200 and "Нямате право да откриете петиция:" in said and "ремонт на покрива" in refused else [f"{path}: a board member opening a petition answered HTTP {s} without the API's 403 in its words, or the form not kept"]
    if not owner:
        print("—   /entrance/petitions  the owner's path NOT checked: no --owner-user")
        return failures
    board = CARRIED.get(web)
    CARRIED[web] = owner                                    # the page, signed in as the owner
    try:
        subject = f"e2e: ремонт на покрива {uuid.uuid4().hex[:6]}"
        s, location, _ = submit_form(web, path, page, 'name="subject"', {"subject": subject})
        made = re.fullmatch(rf"(?:{re.escape(web)})?(/entrance/petitions\?entrance={entrance}&petition=([0-9a-f-]{{36}}))", location)
        if s != 303 or not made:
            return failures + [f"{path}: the owner opening a petition answers HTTP {s} → {location or 'nowhere'} — expected the petition's page"]
        here, petition = made.group(1), made.group(2)
        a_status, a_raw = fetch(api, f"/api/assembly/entrances/{entrance}/petitions/{petition}")
        if a_status != 200:
            return failures + [f"GET …/petitions/{{petitionId}}: HTTP {a_status} {a_raw[:120]}"]
        weighed = json.loads(a_raw)
        errors = list(Draft202012Validator(response_schema("GET", "/api/assembly/entrances/{entranceId}/petitions/{petitionId}", 200), format_checker=Draft202012Validator.FORMAT_CHECKER).iter_errors(weighed))
        failures += [f"GET …/petitions/{{petitionId}}: the answer breaks its contract: {errors[0].message}"] if errors else []
        shown = visible_text(fetch(web, here)[1])
        want = [subject, f"Подписали: {len(weighed['signatories'])}", f"Събрани {weighed['heldPct']}%", f"праг {weighed['thresholdPct']}%", petition,
                "Отключена: подписалите" if weighed["unlocked"] else "Не е отключена"]
        failures += [f"{here}: does not show {w!r}" for w in want if w not in shown]
        # PM-GA-003 — a signature is once: the owner opened it, so signing it again is the API's refusal in its words, and still one signature
        s, _, again = submit_form(web, here, fetch(web, here)[1], "Подпиши петицията", {})
        failures += [] if s == 200 and word(visible_text(again)) else [f"{here}: signing a second time answered HTTP {s} without the API's words"]
        failures += [] if len(json.loads(fetch(api, f"/api/assembly/entrances/{entrance}/petitions/{petition}")[1])["signatories"]) == 1 else [f"{here}: a second signature was taken"]
        # PM-GA-003 — locked while the owner holds less than the threshold (the API's call): convening is refused in its words, the form kept
        if weighed["unlocked"]:
            print("—   /entrance/petitions  convening NOT checked: the seeded owner's petition is unlocked, and convening would add an assembly to the demo")
        else:
            place = f"Входът — {uuid.uuid4().hex[:6]}"
            s, _, locked = submit_form(web, here, fetch(web, here)[1], "Свикай събранието",
                                       {"demandUnmet": "не отговориха", "scheduledAt": f"{day}T18:30", "place": place, "mode": "IN_PERSON"})
            failures += [] if s == 200 and word(visible_text(locked)) and place in locked and "Събранието е свикано" not in visible_text(locked) else [f"{here}: convening on a locked petition answered HTTP {s} without the API's refusal in its words, or the form not kept"]
    finally:
        CARRIED[web] = board
    # PM-SEC-001 — the board member reads the same weighing, and signs or convenes nothing: the API's 403, in its words
    page = fetch(web, here)[1]
    failures += [] if f"Събрани {weighed['heldPct']}%" in visible_text(page) else [f"{here}: the board member's page does not show the weighing the API gave"]
    s, _, signing = submit_form(web, here, page, "Подпиши петицията", {})
    failures += [] if s == 200 and "Нямате право да подпишете петицията:" in visible_text(signing) else [f"{here}: a board member signing answered HTTP {s} without the API's 403 in its words"]
    s, _, convening = submit_form(web, here, page, "Свикай събранието", {"demandUnmet": "x", "scheduledAt": f"{day}T18:30", "place": "x", "mode": "IN_PERSON"})
    failures += [] if s == 200 and "Нямате право да свикате събрание по петицията:" in visible_text(convening) else [f"{here}: a board member convening answered HTTP {s} without the API's 403 in its words"]
    print(f"{'ok ' if not failures else 'BAD'} PM-GA-003 the petitions: opened by the owner through the page and shown as the API weighs it "
          f"({weighed['heldPct']}% of {weighed['thresholdPct']}%, {'unlocked' if weighed['unlocked'] else 'locked'}); signed once; the board member refused in the API's words")
    return failures


def mandates_screen(api, web, entrance, signed_in, administrator=None, spare_party=None):
    """
    WEB-49 (PM-GOV-004, PM-GOV-005, PM-SEC-001) — `/entrance/mandates`. Every mandate the API lists is on the page, with its
    number, and the four offices the API accepts are offered. Signed in as a board member, who is not the administrator,
    recording a mandate is the API's 403 in its words, the form kept, and the API's list unchanged.
    Signed in as the administrator (`--admin-user`, a login tied to nobody and named in DOMUVAI_AUTH_ADMINS), a controller's
    mandate for `--spare-party` (a party holding no other office) is recorded through the page and read back in
    the API's list, then ended on its own first day — which leaves it no day in force, so the board stays as seeded; ending
    it a second time is the API's "once" in its words.
    WEB-49b (PM-SEC-001) — logins tied and untied: a board member tying a login (for no party, as the record above) is the API's 403 in its words, the form kept.
    The administrator ties a throwaway subject `e2e-spare-<hex>` to `--spare-party` (the page says so), ties it again (the API's
    409 in its words, form kept), unties it (the page says untied) and unties it again (the page says it was tied to none).
    Only that subject is ever tied — never a seeded login or a service client — and it is untied before the check ends.
    """
    path = f"/entrance/mandates?entrance={entrance}"
    listed = lambda: json.loads(fetch(api, f"/api/identity/entrances/{entrance}/mandates")[1]) if CARRIED.get(api) else []
    status, page = fetch(web, path)
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    for what, marker in (("recording", "Впиши мандата"), ("ending", "Запиши края"), ("tying", "Свържи входа"), ("untying", "Развържи входа")):
        failures += [] if marker in page else [f"{path}: the {what} form is missing"]
    offered = [dict(re.findall(r'(\w[\w$:-]*)="([^"]*)"', tag)).get("value") for tag in re.findall(r"<option\b[^>]*>", page)]
    failures += [] if offered == ["BM", "MB", "CTL", "CSH"] else [f"{path}: offers the offices {offered}, not the API's BM, MB, CTL, CSH"]
    before = listed()
    failures += [f"{path}: does not show the mandate {m['mandateId']}" for m in before if m["mandateId"] not in page]
    if not signed_in:
        print("—   /entrance/mandates  NOT checked: no sign-in")
        return failures
    word = lambda said: "Системата не прие това:" in said           # a 400/409 of the API, in its words — not a 403: the rule, not the role, is the cause
    protocol = f"e2e: протокол {uuid.uuid4().hex[:6]}"
    today = datetime.datetime.now(ZoneInfo("Europe/Sofia")).date()
    first, last = str(today - datetime.timedelta(days=2)), str(today + datetime.timedelta(days=30))
    # PM-SEC-001 — a board member is not the administrator: the API's 403 in its words, the form kept, nothing recorded
    s, _, refused = submit_form(web, path, page, "Впиши мандата", {"body": "CTL", "party": NO_ONE, "validFrom": first, "validTo": last, "protocolRef": protocol})
    failures += [] if s == 200 and "Нямате право да впишете мандат:" in visible_text(refused) and f'value="{protocol}"' in refused else [f"{path}: a board member recording a mandate answered HTTP {s} without the API's 403 in its words, or the form not kept"]
    failures += [] if listed() == before else [f"{path}: a refused attempt changed the API's mandates"]
    # PM-SEC-001 — nor does a board member tie a login: the API's 403 in its words, the form kept
    spare = f"e2e-spare-{uuid.uuid4().hex[:8]}"
    s, _, refused = submit_form(web, path, page, "Свържи входа", {"party": NO_ONE, "subject": spare})
    failures += [] if s == 200 and "Нямате право да свържете вход с лице:" in visible_text(refused) and f'value="{spare}"' in refused else [f"{path}: a board member tying a login answered HTTP {s} without the API's 403 in its words, or the form not kept"]
    if not (administrator and spare_party):
        print("—   /entrance/mandates  the administrator's path NOT checked: no --admin-user or --spare-party")
        return failures
    board = CARRIED.get(web)
    CARRIED[web] = administrator                            # the page, signed in as the administrator
    try:
        s, _, recorded = submit_form(web, path, page, "Впиши мандата", {"body": "CTL", "party": spare_party, "validFrom": first, "validTo": last, "protocolRef": protocol})
        made = re.search(r"Мандатът е вписан\. Номер: ([0-9a-f-]{36})", visible_text(recorded))
        if s != 200 or not made:
            return failures + [f"{path}: the administrator recording a mandate answered HTTP {s} without the mandate's number: {visible_text(recorded)[:200]!r}"]
        mandate = made.group(1)
        # PM-GOV-004 — the API's list now holds it: a controller's mandate for that party, in term, up to the day typed
        held = [m for m in listed() if m["mandateId"] == mandate]
        failures += [] if len(held) == 1 and held[0]["role"] == "CTL" and held[0].get("partyId") == spare_party and held[0]["validTo"] == last and held[0]["standing"]["kind"] == "IN_TERM" else [f"{path}: the API's list does not hold the mandate recorded as typed: {held}"]
        shown = visible_text(fetch(web, path)[1])
        failures += [] if mandate in shown else [f"{path}: the page does not show the mandate recorded"]
        # ended on its own first day, it has no day in force: the board stands as seeded
        s, _, ended = submit_form(web, path, page, "Запиши края", {"mandate": mandate, "on": first, "protocolRef": protocol})
        failures += [] if s == 200 and f"е приключен на {first}" in visible_text(ended) else [f"{path}: ending the mandate answered HTTP {s} without the API's day: {visible_text(ended)[:200]!r}"]
        failures += [] if [m["mandateId"] for m in listed()] == [m["mandateId"] for m in before] else [f"{path}: after the end the API's mandates are not as seeded"]
        failures += [] if [m["standing"] for m in listed()] == [m["standing"] for m in before] else [f"{path}: after the end a seeded mandate's standing is not as seeded"]
        s, _, again = submit_form(web, path, page, "Запиши края", {"mandate": mandate, "on": first, "protocolRef": protocol})
        failures += [] if s == 200 and word(visible_text(again)) and f'value="{mandate}"' in again else [f"{path}: ending a second time answered HTTP {s} without the API's words, or the form not kept"]
        # PM-SEC-001 — the throwaway subject is tied to the spare party, refused a second time, untied, and said to be tied to none
        try:
            s, _, tied = submit_form(web, path, page, "Свържи входа", {"party": spare_party, "subject": spare})
            failures += [] if s == 200 and f"Входът {spare} е свързан с лице {spare_party}" in visible_text(tied) else [f"{path}: the administrator tying {spare} answered HTTP {s} without the page saying so: {visible_text(tied)[:200]!r}"]
            s, _, twice = submit_form(web, path, page, "Свържи входа", {"party": spare_party, "subject": spare})
            failures += [] if s == 200 and word(visible_text(twice)) and f'value="{spare}"' in twice else [f"{path}: tying {spare} a second time answered HTTP {s} without the API's words, or the form not kept"]
        finally:
            s, _, untied = submit_form(web, path, page, "Развържи входа", {"subject": spare, "issuer": ""})
            failures += [] if s == 200 and f"Входът {spare} е развързан" in visible_text(untied) else [f"{path}: untying {spare} answered HTTP {s} without the page saying so — it may still be tied: {visible_text(untied)[:200]!r}"]
        s, _, nothing = submit_form(web, path, page, "Развържи входа", {"subject": spare, "issuer": ""})
        failures += [] if s == 200 and f"Входът {spare} не е бил свързан с лице" in visible_text(nothing) else [f"{path}: untying {spare} a second time answered HTTP {s} without saying it was tied to none: {visible_text(nothing)[:200]!r}"]
    finally:
        CARRIED[web] = board
    print(f"{'ok ' if not failures else 'BAD'} PM-GOV-004 the mandates: {len(before)} on the page as the API lists them; recorded by the administrator through the page, read back, "
          "ended with the board as seeded; a throwaway login tied, refused twice and untied; the board member refused in the API's words")
    return failures


def units_screen(api, web, entrance, signed_in, owner=None):
    """
    WEB-50b (PM-ORG-002, PM-ORG-003, PM-SEC-001) — `/entrance/units`. The seeded entrance's units are on the page as the API lists
    them, with the form and the kinds already used. Nothing is written to the seeded entrance: the board member's set of units
    with ideal parts declared for some and not others is the API's refusal in its words (the set is refused whole, before any
    unit is inserted), the form kept, and the API's list unchanged; the seeded owner is refused in the API's 403 words.
    """
    path = f"/entrance/units?entrance={entrance}"
    status, page = fetch(web, path)
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    failures += [] if "Впиши обектите" in page else [f"{path}: the units form is missing"]
    listed = lambda: json.loads(fetch(api, f"/api/registry/entrances/{entrance}/units")[1]) if CARRIED.get(api) else []
    before = listed()
    shown = visible_text(page)
    if not before:
        print("—   /entrance/units  the units on the page NOT compared: the API's list was not read")
    failures += [f"{path}: does not show the unit {u['designation']} ({u['unitType']})" for u in before if f"{u['designation']} · {u['unitType']} · " not in shown]
    if not signed_in:
        print("—   /entrance/units  the refusals NOT checked: no sign-in")
        return failures
    tag = uuid.uuid4().hex[:6]
    mixed = {"units": f"e2e-{tag};APARTMENT;60;50;не;не\ne2e-{tag}b;APARTMENT;40;;не;не"}       # declared for one, not the other: refused whole, nothing inserted
    s, _, refused = submit_form(web, path, page, "Впиши обектите", mixed)
    failures += [] if s == 200 and "Системата не прие това:" in visible_text(refused) and f"e2e-{tag};APARTMENT;60;50;не;не" in refused else [f"{path}: a mixed set answered HTTP {s} without the API's words, or the list not kept: {visible_text(refused)[:200]!r}"]
    s, _, unreadable = submit_form(web, path, page, "Впиши обектите", {"units": f"e2e-{tag};APARTMENT;abc;;не;не"})
    failures += [] if s == 200 and "Ред 1:" in visible_text(unreadable) else [f"{path}: an unreadable line answered HTTP {s} without its number"]
    s, _, unflagged = submit_form(web, path, page, "Впиши обектите", {"units": f"e2e-{tag};APARTMENT;60\ne2e-{tag}b;APARTMENT;40;;да;не"})
    failures += [] if s == 200 and "Ред 1:" in visible_text(unflagged) and "липсват полета" in visible_text(unflagged) else [f"{path}: a unit line without its two flags answered HTTP {s} without its number and the flags named (WEB-51)"]
    failures += [] if listed() == before else [f"{path}: a refused set changed the API's units"]
    # WEB-51 — the title form is on the page, with the seeded entrance's units to pick from; a title for a party nothing has is the
    # API's refusal in its words, whoever is asked (the owner's: the 403), so nothing is written to the seeded demo either way
    failures += [] if "Впиши правото" in page else [f"{path}: the title form is missing"]
    nobody_title = lambda unit: {"unit": unit, "party": NO_ONE, "role": "OWN", "share": "1", "validFrom": "", "validTo": ""}
    if before:
        s, _, no_party = submit_form(web, path, page, "Впиши правото", nobody_title(before[0]["id"]))
        failures += [] if s == 200 and "Системата не прие това:" in visible_text(no_party) and "Правото е вписано" not in no_party else [f"{path}: a title for a party nothing has answered HTTP {s} without the API's words"]
    if owner:
        board = CARRIED.get(web)
        CARRIED[web] = owner
        try:
            s, _, refused = submit_form(web, path, page, "Впиши обектите", mixed)
            failures += [] if s == 200 and "Нямате право да впишете обекти:" in visible_text(refused) else [f"{path}: the owner's set answered HTTP {s} without the API's 403 in its words"]
            if before:
                s, _, refused = submit_form(web, path, page, "Впиши правото", nobody_title(before[0]["id"]))
                failures += [] if s == 200 and "Нямате право да впишете право върху обект:" in visible_text(refused) and "Правото е вписано" not in refused else [f"{path}: the owner's title answered HTTP {s} without the API's 403 in its words (PM-SEC-001)"]
        finally:
            CARRIED[web] = board
        failures += [] if listed() == before else [f"{path}: the owner's refused set changed the API's units"]
    else:
        print("—   /entrance/units  the owner's refusal NOT checked: no --owner-user")
    print(f"{'ok ' if not failures else 'BAD'} PM-ORG-002 the units: {len(before)} on the page as the API lists them; a mixed set refused in the API's words by the board member and a 403 for the owner, nothing written")
    return failures


def units_in(web, entrance, administrator, owner, owner_party, party, party_name):
    """
    WEB-50b — units registered through the page in an entrance `registry_screen` has just registered (it holds none). The
    administrator reads no book: the API's 403. The administrator gives `--owner-party` a member-of-the-board mandate in
    that entrance only (through the mandates page, as WEB-49), so the seeded owner's login may enter its book; the owner
    is refused a set summing to 99.98% (PM-ORG-002), then registers two units with the ideal parts left out (derived by area,
    PM-ORG-003), reads them back on the page, and is refused a second set. WEB-51: the owner records a title in one of the
    two units for `party` (the one `registry_screen` registered, held by nobody else) — a share of 1.5 and a party nothing has
    are the API's words, the form kept; the title lands, its number is shown and the unit's owners are read back by name, role and
    share. The mandate is ended on its own first day, so the seeded demo is as it was: the units, the party and the title
    are in this entrance only, which holds no other unit.
    """
    path, mandates = f"/entrance/units?entrance={entrance}", f"/entrance/mandates?entrance={entrance}"
    as_ = lambda who: CARRIED.__setitem__(web, who)
    board = CARRIED.get(web)
    tag = uuid.uuid4().hex[:6]
    today = datetime.datetime.now(ZoneInfo("Europe/Sofia")).date()
    first, last = str(today - datetime.timedelta(days=2)), str(today + datetime.timedelta(days=30))
    protocol = f"e2e: протокол {tag}"
    failures, mandate = [], None
    derived = {"units": f"e2e-{tag}a;APARTMENT;60;;не;не\ne2e-{tag}b;SHOP;40;;да;не"}
    try:
        as_(administrator)
        page = fetch(web, path)[1]
        s, _, refused = submit_form(web, path, page, "Впиши обектите", derived)
        failures += [] if s == 200 and "Нямате право да впишете обекти:" in visible_text(refused) else [f"{path}: the administrator's set answered HTTP {s} without the API's 403 in its words (PM-SEC-001)"]
        mpage = fetch(web, mandates)[1]
        s, _, recorded = submit_form(web, mandates, mpage, "Впиши мандата", {"body": "MB", "party": owner_party, "validFrom": first, "validTo": last, "protocolRef": protocol})
        made = re.search(r"Мандатът е вписан\. Номер: ([0-9a-f-]{36})", visible_text(recorded))
        if s != 200 or not made:
            return failures + [f"{mandates}: the administrator's mandate for the owner's party in the new entrance answered HTTP {s} without its number: {visible_text(recorded)[:200]!r}"]
        mandate = made.group(1)
        as_(owner)
        page = fetch(web, path)[1]
        failures += [] if "Впиши обектите" in page and "Във входа няма вписан обект." in visible_text(page) else [f"{path}: the new entrance's page is missing the form or the empty-list words for the manager"]
        s, _, short = submit_form(web, path, page, "Впиши обектите", {"units": f"e2e-{tag}a;APARTMENT;;50;не;не\ne2e-{tag}b;APARTMENT;;49.98;не;не"})
        failures += [] if s == 200 and re.search(r"Системата не прие това:.*99\.98", visible_text(short)) and f"e2e-{tag}a;APARTMENT;;50;не;не" in short else [f"{path}: a set summing to 99.98% answered HTTP {s} without the API's words naming the sum, or the list not kept: {visible_text(short)[:200]!r}"]
        s, _, made_units = submit_form(web, path, page, "Впиши обектите", derived)
        ids = re.search(r"Вписани са 2 обекта\. Номера: ([0-9a-f-]{36}) ([0-9a-f-]{36})", visible_text(made_units))
        failures += [] if s == 200 and ids else [f"{path}: two units with no ideal parts answered HTTP {s} without their numbers: {visible_text(made_units)[:200]!r}"]
        read = visible_text(fetch(web, path)[1])
        for name, kind in ((f"e2e-{tag}a", "APARTMENT"), (f"e2e-{tag}b", "SHOP")):
            failures += [] if re.search(rf"{name} · {kind} · [^·]*кв\. м · идеални части [0-9.]+% \(изчислени по площ\)", read) else [f"{path}: {name} is not read back as derived by area"]
        failures += [] if re.search(rf"e2e-{tag}b · SHOP · [^·]*кв\. м · идеални части [0-9.]+% \(изчислени по площ\) · стопанска дейност", read) else [f"{path}: the unit typed with a business use does not read back as one"]
        if ids:
            titles = fetch(web, path)[1]
            unit = {"unit": ids.group(1), "party": party, "role": "OWN", "share": "1", "validFrom": "", "validTo": ""}
            failures += [] if f'value="{ids.group(1)}"' in titles else [f"{path}: the unit just registered is not offered to the title form"]
            for what, bad, words in (("a share of 1.5", {"share": "1.5"}, "Системата не прие това:"), ("a party nothing has", {"party": NO_ONE}, "Системата не прие това:")):
                s, _, refused = submit_form(web, path, titles, "Впиши правото", {**unit, **bad})
                key = bad.get("share") or bad.get("party")
                failures += [] if s == 200 and words in visible_text(refused) and f'value="{key}"' in refused and "Правото е вписано" not in refused else [f"{path}: {what} answered HTTP {s} without the API's words, or the form not kept: {visible_text(refused)[:200]!r}"]
            s, _, titled = submit_form(web, path, titles, "Впиши правото", unit)
            number = re.search(r"Правото е вписано\.\s*Номер:\s*([0-9a-f-]{36})\s*\.\s*Лица с право върху обекта към\s*\d{4}-\d{2}-\d{2}\s*:\s*([^.]*)\.", visible_text(titled))
            failures += [] if s == 200 and number and re.search(rf"(^|; ){re.escape(party_name)} · собственик · дял 1\s*(;|$)", number.group(2).strip()) else [f"{path}: the title answered HTTP {s} without its number and the unit's owners read back by name, role and share: {visible_text(titled)[:240]!r}"]
        s, _, again = submit_form(web, path, page, "Впиши обектите", {"units": f"e2e-{tag}c;APARTMENT;50;;не;не\ne2e-{tag}d;APARTMENT;50;;не;не"})
        failures += [] if s == 200 and "Системата не прие това:" in visible_text(again) else [f"{path}: a second derived set answered HTTP {s} without the API's words (the entrance has units)"]
    finally:
        if mandate:
            as_(administrator)
            s, _, ended = submit_form(web, mandates, fetch(web, mandates)[1], "Запиши края", {"mandate": mandate, "on": first, "protocolRef": protocol})
            failures += [] if s == 200 and f"е приключен на {first}" in visible_text(ended) else [f"{mandates}: ending the owner's mandate answered HTTP {s} without the API's day — it may still stand: {visible_text(ended)[:200]!r}"]
        CARRIED[web] = board
    print(f"{'ok ' if not failures else 'BAD'} PM-ORG-003 units in a registered entrance: refused to the administrator; 99.98% refused in the API's words; two registered with ideal parts derived by area and read back; a second set refused; "
          "WEB-51 a title for a party refused for its share and for a party nothing has, then recorded and the owners read back")
    return failures


def registry_screen(api, web, signed_in, administrator=None, owner=None, nobody=None, owner_party=None):
    """
    WEB-50 (PM-SEC-001, PM-ORG-001, PM-BOOK-011, #363) — `/registry`. Both forms are offered, with the API's closed lists. Signed in as a
    board member (`--user`, not the administrator), a party is registered through the page and shown by its number, never its
    identity number, and an entrance is the API's 403 in its words; the seeded owner is refused both. The administrator registers
    an entrance through the page (a new building at an address, then a second entrance joining it) and reads both back on the
    entrance screen, which the board member's list does not hold. Nothing seeded changes: a party holds no title, an entrance no
    unit and nobody's role — and this runs last, so no screen above reads the new entrance. A login holding no role reads
    `200 []` and is told so in the words that tell nobody to register one (#363).
    """
    path = "/registry"
    status, page = fetch(web, path)
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    for what, marker in (("entrance", "Регистрирай входа"), ("party", "Регистрирай лицето")):
        failures += [] if marker in page else [f"{path}: the {what} form is missing"]
    offered = [dict(re.findall(r'(\w[\w$:-]*)="([^"]*)"', tag)).get("value") for tag in re.findall(r"<option\b[^>]*>", page)]
    failures += [] if offered == ["GA", "ASSOCIATION", "CLOSED_COMPLEX", "", "EGN", "LNCH", "BULSTAT", "PASSPORT"] else [f"{path}: offers {offered}, not the API's closed lists"]
    if not signed_in:
        print("—   /registry  NOT checked: no sign-in")
        return failures
    tag = uuid.uuid4().hex[:6]
    label = f"ЯЯЯ e2e {tag}"
    entrance_form = {"label": label, "managementForm": "GA", "address": f"ул. e2e {tag} 1", "condominiumId": ""}
    party_form = {"fullName": f"e2e {tag}", "idType": "EGN", "idValue": f"E2E{tag}"}
    board = CARRIED.get(web)

    def as_(who, marker, form):          # the page signed in as [who], and one form posted
        CARRIED[web] = who
        try:
            s, _, answer = submit_form(web, path, page, marker, form)
            return s, answer
        finally:
            CARRIED[web] = board

    def sees(who, entrance):             # whether the entrance screen, signed in as [who], names the entrance
        CARRIED[web] = who
        try:
            return label in visible_text(fetch(web, f"/entrance/mandates?entrance={entrance}")[1])
        finally:
            CARRIED[web] = board

    # PM-SEC-001 — a board member registers a party (R2a) and is refused an entrance (R3a), in the API's words, the form kept
    s, made = as_(board, "Регистрирай лицето", party_form)
    registered_party = re.search(rf"Лицето e2e {tag} е регистрирано\. Номер: ([0-9a-f-]{{36}})", visible_text(made))
    failures += [] if s == 200 and registered_party else [f"{path}: a board member registering a party answered HTTP {s} without the party's number: {visible_text(made)[:200]!r}"]
    failures += [] if f"E2E{tag}" not in made else [f"{path}: the party's identity number is shown back (PM-BOOK-011)"]
    s, refused = as_(board, "Регистрирай лицето", {**party_form, "idType": "SSN"})     # a refusal of the API's or the page's own: the number is not shown back either
    failures += [] if s == 200 and "не е вид на идентификатора" in visible_text(refused) and f"E2E{tag}" not in refused else [f"{path}: a party refused for its id type answered HTTP {s} without the refusal, or shows the identity number back (PM-BOOK-011)"]
    s, refused = as_(board, "Регистрирай входа", entrance_form)
    failures += [] if s == 200 and "Нямате право да регистрирате вход:" in visible_text(refused) and f'value="{label}"' in refused else [f"{path}: a board member registering an entrance answered HTTP {s} without the API's 403 in its words, or the form not kept"]
    for marker, form, words in (("Регистрирай лицето", party_form, "регистрирате лице"), ("Регистрирай входа", entrance_form, "регистрирате вход")):
        s, refused = as_(owner, marker, form) if owner else (0, "")
        failures += [] if not owner or (s == 200 and f"Нямате право да {words}:" in visible_text(refused)) else [f"{path}: the owner's attempt ({words}) answered HTTP {s} without the API's 403 in its words"]
    registered = []
    if administrator:
        s, made = as_(administrator, "Регистрирай входа", entrance_form)
        first = re.search(r"Номер на входа: ([0-9a-f-]{36}) ?, номер на сградата: ([0-9a-f-]{36})", visible_text(made))
        failures += [] if s == 200 and first else [f"{path}: the administrator registering an entrance answered HTTP {s} without its numbers: {visible_text(made)[:200]!r}"]
        if first:                                           # the rest needs the first entrance's building; the no-role words below are read either way
            s, joined = as_(administrator, "Регистрирай входа", {**entrance_form, "label": f"{label} б", "address": "", "condominiumId": first.group(2)})
            second = re.search(r"Номер на входа: ([0-9a-f-]{36}) ?, номер на сградата: ([0-9a-f-]{36})", visible_text(joined))
            failures += [] if s == 200 and second and second.group(2) == first.group(2) else [f"{path}: the second entrance did not join the first one's building: {visible_text(joined)[:200]!r}"]
            s, neither = as_(administrator, "Регистрирай входа", {**entrance_form, "address": ""})
            failures += [] if s == 200 and "Системата не прие това:" in visible_text(neither) else [f"{path}: an entrance with no address and no building answered HTTP {s} without the API's words"]
            registered = [first.group(1)] + ([second.group(1)] if second else [])
        for entrance in registered:
            failures += [] if sees(administrator, entrance) else [f"/entrance/mandates?entrance={entrance}: the administrator's screen does not show the entrance registered"]
            failures += [] if not sees(board, entrance) else [f"/entrance/mandates?entrance={entrance}: the board member sees an entrance they hold no role in"]
        if registered and owner and owner_party:
            failures += units_in(web, registered[0], administrator, owner, owner_party, registered_party.group(1), f"e2e {tag}") if registered_party else [f"{path}: the party registered above has no number, so no title is checked in the new entrance"]
        elif registered:
            print("—   /entrance/units  the units of a registered entrance NOT checked: no --owner-user or --owner-party")
    else:
        print("—   /registry  the administrator's path NOT checked: no --admin-user")
    if nobody:
        CARRIED[web] = nobody
        try:
            for url in ("/portfolio", "/entrance"):
                said = visible_text(fetch(web, url)[1])
                failures += [] if "не заемате роля в нито един" in said and "Регистрирайте вход" not in said else [f"{url}: a login holding no role is not told so, or is told to register an entrance"]
        finally:
            CARRIED[web] = board
    else:
        print("—   /registry  the no-role words NOT checked: no --none-user")
    print(f"{'ok ' if not failures else 'BAD'} PM-ORG-001 the registry: a party registered by the board member without its identity number shown, "
          f"an entrance refused to the board member and the owner in the API's words, {len(registered)} registered by the administrator and seen by nobody else; the no-role words for a login with no role")
    return failures


def handover_screen(api, web, entrance, signed_in):
    """
    WEB-43 (PM-FUND-010, PM-SEC-001) — `/entrance/fund/handover`: every statement the API lists is on the page with its
    "not signed" line; opened (`?statement=`), the seeded one shows its hash, its basis text verbatim and the web's own
    "съвпада" check. Signed in, the issue form posted with the seeded manager on both sides is refused in the API's
    words naming PM-FUND-010, the form kept, nothing issued.
    """
    path = f"/entrance/fund/handover?entrance={entrance}"
    a_status, raw = fetch(api, f"/api/money/entrances/{entrance}/fund/handover-statements")
    if a_status != 200 or not json.loads(raw):
        return [f"{path}: the API's list answers HTTP {a_status}, or lists none (the seed issues one)"]
    listed = json.loads(raw)
    status, page = fetch(web, path)
    text = visible_text(page)
    not_signed = "Не е подписан от страните — подписването чака избора на доставчик на квалифициран електронен подпис (#57)."
    failures = [f"{path}: HTTP {status}"] if status != 200 else []
    failures += [f"{path}: the statement {v['id']} is not listed" for v in listed if f"statement={v['id']}" not in page.replace("&amp;", "&")]
    failures += [f"{path}: {len(listed)} statements listed, the line 'not signed' shown {text.count(not_signed)} times"] if text.count(not_signed) != len(listed) else []
    o_status, o_page = fetch(web, f"{path}&statement={listed[0]['id']}")
    o_text = visible_text(o_page)
    first = listed[0]
    want = [first["basisHash"], " ".join(first["basis"].split()), "Хешът съвпада с показания текст на основата", f"закон {first['lawVersion']} · двигател {first['engineVersion']}"]
    failures += [f"{path}&statement=…: HTTP {o_status}"] if o_status != 200 else []
    failures += [f"{path}&statement=…: does not show {w[:60]!r}" for w in want if w not in o_text]
    failures += [f"{path}&statement=…: {o_text.count(not_signed)} 'not signed' lines for {len(listed)} statements and the one opened"] if o_text.count(not_signed) != len(listed) + 1 else []
    failures += [f"{path}: {word!r} on a statement that is not signed"] if re.search(r"подписан|съгласуван|приет|окончателен", text.replace(not_signed, ""), re.I) else []
    if signed_in:
        holder = fund_holder(entrance)
        fields = {"handoverOn": "2026-10-01", "from": "", "outgoing": holder, "incoming": holder, "bankBalance": "1,23"}
        s_status, _, refused_page = submit_form(web, path, page, 'name="bankBalance"', fields)
        said = visible_text(refused_page)
        if s_status != 200 or "Системата не прие това:" not in said or "PM-FUND-010" not in said or 'value="1,23"' not in refused_page:
            failures.append(f"{path}: a handover with one party on both sides answered HTTP {s_status} without the API's words, or the form not kept")
        after = json.loads(fetch(api, f"/api/money/entrances/{entrance}/fund/handover-statements")[1])
        failures += [] if len(after) == len(listed) else [f"{path}: a refused handover recorded a statement"]
    print(f"{'ok ' if not failures else 'BAD'} PM-FUND-010 the handover statements: {len(listed)} listed, each not signed; the first opened with its hash checked; "
          f"{'a handover with one party on both sides refused' if signed_in else 'the form NOT checked: no sign-in'}")
    return failures


def payment_screen(api, web, entrance):
    """
    WEB-39 (PM-DEBT-008) — `/entrance/payments`: a payment designating a debt the unit does not owe, posted
    through the page's form as a browser with no script posts it, is refused in the API's words, the form kept. A
    payment in cash, today, is then recorded through the same form and lands on its allocation; the same form posted
    again lands on the same payment (one Idempotency-Key per form). The allocation shown is the API's, read back with
    GET …/payments/{paymentId} and validated against the contract: the rule applied and what each debt received.
    """
    path, unit = f"/entrance/payments?entrance={entrance}", a_unit(entrance)
    status, page = fetch(web, path)
    if status != 200 or 'name="designated"' not in page:
        return [f"{path}: HTTP {status}, or no payment form"]
    today = datetime.datetime.now(ZoneInfo("Europe/Sofia")).date().isoformat()
    fields = {"unit": unit, "amount": "1,00", "valueDate": today, "receivedInto": "CASH", "designated": ""}
    failures = []
    status, _, refused_page = submit_form(web, path, page, 'name="designated"', {**fields, "designated": "2020-01-01"})
    if status != 200 or "owes no debt dated 2020-01-01" not in visible_text(refused_page) or 'value="1,00"' not in refused_page:
        failures.append(f"{path}: a designation of a debt not owed answered HTTP {status} without the API's words, or the form not kept")
    landed, page = [], fetch(web, path)[1]             # a fresh form: its own key
    for _ in range(2):                                 # the same form, the same key, twice: one payment
        status, location, _ = submit_form(web, path, page, 'name="designated"', fields)
        made = re.fullmatch(rf"(?:{re.escape(web)})?/entrance/payments\?entrance={entrance}&payment=([0-9a-f-]{{36}})", location)
        if status != 303 or not made:
            return failures + [f"{path}: recording through the page's form answers HTTP {status} → {location or 'nowhere'}, not the payment's page"]
        landed.append(made.group(1))
    failures += [] if landed[0] == landed[1] else [f"{path}: the same form sent twice recorded two payments {landed}"]
    of = "/api/money/entrances/{entranceId}/payments/{paymentId}"
    status, raw = fetch(api, f"/api/money/entrances/{entrance}/payments/{landed[0]}", headers=as_board())
    if status != 200:
        return failures + [f"GET {of}: HTTP {status} {raw[:200]}"]
    validator = Draft202012Validator(response_schema("GET", of, 200), format_checker=Draft202012Validator.FORMAT_CHECKER)
    failures += [f"GET {of}: {e.message}" for e in validator.iter_errors(json.loads(raw))]
    payment = json.loads(raw)
    text = visible_text(fetch(web, location.removeprefix(web))[1])
    want = [f"Плащане {eur(payment['amountMinor'])}", f"({payment['allocationRule']})", f"Неразпределено (аванс): {eur(payment['unallocatedMinor'])}",
            *[f"Задължение от {p['debtDate']}: {eur(p['amountMinor'])}" for p in payment["allocation"]]]
    failures += [f"{location}: does not show {w!r}, the API's allocation" for w in want if w not in text]
    print(f"{'ok ' if not failures else 'BAD'} PM-DEBT-008 the payment form: {payment['allocationRule']}, {len(payment['allocation'])} debt(s); "
          "a resend is the same payment; a debt not owed refused")
    return failures


def intake_screen(api, web, entrance):
    """
    WEB-35 (ADR-012, PM-SEC-001) — `/entrance/intake`: a sheet uploaded through the page's own form, as a browser with no
    script posts it, shows every column of the API's own proposal for the same sheet, each select preset to the API's
    field, and names the columns the API could not place; confirming with the one it could not place given a field
    shows that choice as confirmed. Nothing here is typed of the proposal: it is taken from the API. With a token of its
    own, the check asks as the controller too — the API's 403 naming PM-BOOK-005 (G-1).
    """
    path = f"/entrance/intake?entrance={entrance}"
    status, page = fetch(web, path)
    if status != 200 or 'name="sheet"' not in page:
        return [f"{path}: HTTP {status}, or no form to upload a sheet"]
    of, failures = f"/api/intake/entrances/{entrance}/fee-sheet/profile", []
    if api in CARRIED and as_board():
        status, said = fetch(api, of, {"csv": INTAKE_SHEET})
        failures += [] if status == 403 and "PM-BOOK-005" in said else [f"{of}: the controller profiled a sheet (HTTP {status} {said[:120]!r}), not the API's 403 naming PM-BOOK-005"]
    else:
        print(f"—   {of}  the controller's 403 NOT checked: this run holds no token of its own and no board's")
    status, raw = fetch(api, of, {"csv": INTAKE_SHEET}, headers=as_board())
    if status != 200:
        return failures + [f"{of}: HTTP {status} {raw[:200]}"]
    proposal = json.loads(raw)
    if "м2" not in proposal["unmappedColumns"]:
        return failures + [f"{of}: the API placed 'м2' ({proposal['mapping'].get('м2')}) — the check corrects a column it could not place; pick another"]
    columns = list(proposal["mapping"]) + proposal["unmappedColumns"]

    status, _, answered = submit_form(web, path, page, 'name="sheet"', {}, files={"sheet": ("fees.csv", INTAKE_SHEET.encode())})
    text = visible_text(answered)
    want = [proposal["mapping"].get(c, "") for c in columns]
    failures += [] if status == 200 else [f"{path}: the upload answered HTTP {status}"]
    failures += [f"{path}: does not show the column {c!r} the API profiled" for c in columns if f"„{c}“" not in text]
    failures += [] if presets(answered) == want else [f"{path}: the selects are preset to {presets(answered)}, the API proposed {want}"]
    failures += [f"{path}: does not name {c!r} among the columns the API could not place" for c in proposal["unmappedColumns"]
                 if not re.search(rf"Системата не разпозна:[^.]*„{re.escape(c)}“", text)]
    if failures:
        return failures
    chosen = {f"field-{i}": f for i, f in enumerate(want)}
    corrected = columns.index("м2")
    chosen[f"field-{corrected}"] = "BUILT_AREA"
    status, _, confirmed = submit_form(web, path, answered, 'name="sheetName"', chosen)
    text = visible_text(confirmed)
    failures += [] if status == 200 and "Потвърдено разпределение" in text else [f"{path}: the confirmation answered HTTP {status}, and no confirmed mapping"]
    failures += [] if "„м2“ → Застроена площ (BUILT_AREA)" in text else [f"{path}: the corrected column is not shown confirmed as BUILT_AREA"]
    failures += [] if presets(confirmed)[corrected] == "BUILT_AREA" else [f"{path}: the form does not keep the corrected choice"]
    print(f"{'ok ' if not failures else 'BAD'} {path}  {len(columns)} columns as the API profiled them, one corrected and confirmed")
    return failures + intake_run(api, web, entrance, path, confirmed, {**proposal["mapping"], "м2": "BUILT_AREA"})


def eur(minor):
    """The web's own rendering of minor units (lib/console.ts `eur`) — what the page must show for the API's figure."""
    whole, cents = divmod(abs(minor), 100)
    return f"{'−' if minor < 0 else ''}€{whole:,}".replace(",", ".") + f",{cents:02d}"


def intake_run(api, web, entrance, path, page, mapping):
    """
    WEB-36 (ADR-012, PM-FEE-014, PM-FEE-012, PM-SEC-001) — the confirmed sheet dry-run through the page's third form,
    the rate typed in euros: the page shows the verdict the API gives for the same request — the counts and each
    differing bill, theirs, ours and the difference — and recording it shows the id of an import the API reads back.
    Nothing of the verdict is typed here: it is the API's.
    """
    line = INTAKE_RUN["lines"][0]
    typed = {"period": INTAKE_RUN["period"], "legalDate": INTAKE_RUN["legalDate"], "stream-0": line["stream"], "key-0": line["key"],
             "per-0": "rate", "amount-0": "2,50", "decision-0": line["decisionId"]}
    of = f"/api/intake/entrances/{entrance}/fee-sheet/dry-run"
    status, raw = fetch(api, of, {"csv": INTAKE_SHEET, "mapping": mapping, **INTAKE_RUN}, headers=as_board())
    if status != 200:
        return [f"{of}: HTTP {status} {raw[:200]}"]
    report = json.loads(raw)
    if not report["differences"]:
        return [f"{of}: the sheet reproduced — the check wants a differing bill to show; pick another rate"]

    def verdict(text):
        said = [f"Редове: {report['rowsParsed']} · съвпадат: {report['matched']} · различават се: {report['differing']}"]
        said += [f"{d['designation']} {eur(d['theirMinor'])} {eur(d['ourMinor'])} {eur(d['deltaMinor'])}" for d in report["differences"]]
        said += report["violations"]
        return [f"{path}: does not show the API's {s!r}" for s in said if s not in text]

    status, _, answered = submit_form(web, path, page, 'name="period"', {**typed, "step": "dry-run"})
    failures = ([] if status == 200 else [f"{path}: the dry-run answered HTTP {status}"]) + verdict(visible_text(answered))
    if failures:
        return failures
    status, _, recorded = submit_form(web, path, answered, 'name="period"', {**typed, "step": "record"})
    text = visible_text(recorded)
    found = re.search(r"Внасянето е записано: ([0-9a-f-]{36})", text)
    if status != 200 or not found:
        return [f"{path}: the record answered HTTP {status}, and no import id"]
    failures += verdict(text)
    failures += import_page(api, web, entrance, found[1], {"differing": report["differing"], "rowsParsed": report["rowsParsed"]})[0]
    print(f"{'ok ' if not failures else 'BAD'} {path}  dry-run and record show the API's verdict, {report['differing']} bill(s) differing")
    return failures + intake_commit(api, web, entrance, path, recorded_page=recorded, typed=typed, refused_id=found[1])


def api_words(raw):
    """A refusal's words as the web shows them (lib/refusal.ts `refused`): the body's `error`."""
    try:
        return json.loads(raw).get("error", "")
    except ValueError:
        return ""


def import_page(api, web, entrance, id, expected=None):
    """
    WEB-37 (PM-SEC-001) — `/entrance/intake/<id>`: the import as the API reads it — its status, counts, content hash
    and what blocked a commit or a revert. A commit or a revert settles after its answer, so the API is read before and
    after the page, and the page must show one of the two. [expected]: figures the record answered, which the read must agree with.
    """
    url = f"/entrance/intake/{id}?entrance={entrance}"
    of = f"/api/intake/imports/{id}"
    before = fetch(api, of, headers=as_board())
    status, page = fetch(web, url)
    after = fetch(api, of, headers=as_board())
    if before[0] != 200 or after[0] != 200:
        return [f"{of}: HTTP {after[0]} {after[1][:160]!r}"], page, {}
    reads = [json.loads(before[1]), json.loads(after[1])]
    text = visible_text(page)
    failures = [] if status == 200 else [f"{url}: HTTP {status}"]
    failures += [f"{of}: reads {k}={reads[1][k]}, the record answered {v}" for k, v in (expected or {}).items() if reads[1].get(k) != v]
    shown = [r for r in reads if f"({r['status']})" in text and
             f"Редове: {r['rowsParsed']} · различават се: {r['differing']} · нарушения: {r['violations']}" in text and r["sourceSha"] in text
             and all(f"{word}: {r[k]}" in text for k, word in (("commitBlockedBy", "Приемането е спряно"), ("revertBlockedBy", "Отмяната е спряна")) if r.get(k))]
    failures += [] if shown else [f"{url}: does not show the import as the API reads it ({reads[1]['status']}, {reads[1]['rowsParsed']} rows)"]
    print(f"{'ok ' if not failures else 'BAD'} {url}  the import's page as the API reads it ({reads[1]['status']})")
    return failures, page, reads[1]


def intake_commit(api, web, entrance, path, recorded_page, typed, refused_id):
    """
    WEB-37 (PM-SEC-001, PM-FEE-014) — committing through the page. The import WEB-36 recorded does not reproduce: its
    commit is refused in the API's words, as the API answers the same request. A sheet that reproduces is uploaded,
    confirmed as the API proposed, recorded and committed through the page, which shows the commit asked; the import's
    page shows it as the API reads it; and its revert through that page shows the API's answer — REVERTING — or its
    refusal in its words. Nothing is typed of the answers: each is the API's.
    """
    failures = []
    status, _, answered = submit_form(web, path, recorded_page, 'name="period"', {**typed, "step": "commit"})
    of = f"/api/intake/imports/{refused_id}/commit"
    _, raw = fetch(api, of, {"sheet": {"csv": INTAKE_SHEET, **INTAKE_RUN}}, headers=as_board())
    words = api_words(raw)
    failures += [] if words and f"Системата не прие това: {words}" in visible_text(answered) else [
        f"{path}: the commit of a sheet that does not reproduce does not show the API's refusal {words!r}"]

    status, page = fetch(web, path)
    status, _, uploaded = submit_form(web, path, page, 'name="sheet"', {}, files={"sheet": ("reproduces.csv", INTAKE_REPRO.encode())})
    status, _, confirmed = submit_form(web, path, uploaded, 'name="sheetName"', {f"field-{i}": f for i, f in enumerate(presets(uploaded))})
    line = INTAKE_REPRO_RUN["lines"][0]
    tariff = {"period": INTAKE_REPRO_RUN["period"], "legalDate": INTAKE_REPRO_RUN["legalDate"], "stream-0": line["stream"],
              "key-0": line["key"], "per-0": "rate", "amount-0": "10,00", "decision-0": line["decisionId"]}
    status, _, recorded = submit_form(web, path, confirmed, 'name="period"', {**tariff, "step": "record"})
    found = re.search(r"Внасянето е записано: ([0-9a-f-]{36})", visible_text(recorded))
    if not found or "Таблицата е възпроизведена до стотинка." not in visible_text(recorded):
        return failures + [f"{path}: the sheet that reproduces was not recorded as reproduced (HTTP {status})"]
    status, _, committed = submit_form(web, path, recorded, 'name="period"', {**tariff, "step": "commit"})
    text = visible_text(committed)
    page_failures, imported, read = import_page(api, web, entrance, found[1])
    failures += page_failures
    # the API creates one unit per row it parsed (rowsCreated = the rows, ImportService.commit); the answer is not read twice
    failures += [] if "Приемането е поискано: Приема се (COMMITTING)" in text and f"Нови обекти: {read.get('rowsParsed')}" in text else [
        f"{path}: the commit through the page does not show COMMITTING and {read.get('rowsParsed')} new unit(s)"]
    url = f"/entrance/intake/{found[1]}?entrance={entrance}"
    status, _, reverted = submit_form(web, url, imported, 'name="reason"', {"reason": "e2e: проверка на отмяната"})
    text = visible_text(reverted)
    # the API asked the same revert again: refused for an import it has just been asked to revert, or refused in the
    # same words as the page was, since nothing has changed
    _, raw = fetch(api, f"/api/intake/imports/{found[1]}/revert", {"reason": "e2e: проверка на отмяната"}, headers=as_board())
    _, read = fetch(api, f"/api/intake/imports/{found[1]}", headers=as_board())
    now = json.loads(read).get("status", "?") if read.startswith("{") else "?"
    asked = "Отмяната е поискана: Отменя се (REVERTING)" in text and now in ("REVERTING", "REVERTED", "REVERT_BLOCKED")
    refused = bool(api_words(raw)) and f"Системата не прие това: {api_words(raw)}" in text
    failures += [] if asked or refused else [
        f"{url}: the revert shows neither REVERTING nor the API's refusal {api_words(raw)!r} (HTTP {status}, the API reads {now})"]
    print(f"{'ok ' if not failures else 'BAD'} {path}  commit and revert through the pages, as the API answers ({now})")
    return failures


def visible_text(page):
    page = re.sub(r"<!--.*?-->", "", page, flags=re.S)
    page = re.sub(r"<(script|style)\b.*?</\1>", " ", page, flags=re.S)
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", page)))


def main():
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--api", required=True)
    parser.add_argument("--web", required=True)
    parser.add_argument("--closed-web", required=True, help="the same build, with nothing set (PM-DEBT-011), with no address to write to (#79)")
    parser.add_argument("--bad-contact-web", required=True, help="the same build, started with a DOMUVAI_CONTACT_EMAIL that is not an address (#79)")
    parser.add_argument("--contact", required=True, help="the address --web was started with, DOMUVAI_CONTACT_EMAIL (#79)")
    parser.add_argument("--signin-web", help="the same build, with sign-in configured and nobody signed in (PM-DEBT-011); --web itself when --user is given")
    parser.add_argument("--open-web", help="the same build, told to run without sign-in (DOMUVAI_AUTH=off); --web itself when no --user is given")
    parser.add_argument("--issuer", help="the realm's issuer URL, when --web signs people in")
    parser.add_argument("--user", help="the login to sign in with at the issuer; its password is read from E2E_PASSWORD, never from the command line")
    parser.add_argument("--subject", help="the issuer's own id for --user")
    parser.add_argument("--entrance", required=True)
    parser.add_argument("--owner-party", help="an owner's party id in --entrance, to co-sign a posting act (WEB-28); seed_demo.py writes them to SEED_OWNERS_FILE")
    parser.add_argument("--owner-user", help="the login of the seeded owner (--owner-party), tied to it by the administrator; signs in with E2E_PASSWORD (WEB-48)")
    parser.add_argument("--owner-subject", help="the issuer's own id for --owner-user")
    parser.add_argument("--admin-user", help="a login named in DOMUVAI_AUTH_ADMINS and tied to nobody; signs in with E2E_PASSWORD (WEB-49)")
    parser.add_argument("--admin-subject", help="the issuer's own id for --admin-user")
    parser.add_argument("--none-user", help="a login tied to nobody and holding no role anywhere; signs in with E2E_PASSWORD (WEB-50)")
    parser.add_argument("--none-subject", help="the issuer's own id for --none-user")
    parser.add_argument("--spare-party", help="a party holding no login and no office, for the administrator to give a mandate and tie a throwaway login to (WEB-49)")
    parser.add_argument("--next-dir", type=Path, default=ROOT / "web/.next", help="the build both servers run")
    args = parser.parse_args()
    api, web, entrance = args.api.rstrip("/"), args.web.rstrip("/"), args.entrance
    API[0] = api
    # Two ways to run. In CI (--user): --web signs people in at a real issuer and the api is closed — this check signs
    # in as a person does and reads every screen signed in, and asks the api with a bearer of its own (E2E_API_TOKEN).
    # On a machine with no issuer: --web runs without sign-in, the api open, and nobody is signed in or out.
    if os.environ.get("CI") and not args.user:
        sys.exit("e2e: in CI the chain is checked signed in — give --user, --subject, --issuer, E2E_PASSWORD and E2E_API_TOKEN")
    signin_web = (args.signin_web or (web if args.user else "")).rstrip("/")
    open_web = (args.open_web or ("" if args.user else web)).rstrip("/")
    if not signin_web or not open_web or (args.user and not (args.issuer and args.subject and os.environ.get("E2E_PASSWORD"))):
        sys.exit("e2e: give --signin-web (without --user), or --open-web, --issuer, --subject and E2E_PASSWORD (with --user)")
    if os.environ.get("E2E_API_TOKEN"):
        CARRIED[api] = {"Authorization": f"Bearer {os.environ['E2E_API_TOKEN']}"}
    failures = console_closed(open_web, args.closed_web.rstrip("/"), args.next_dir)
    failures += signed_in_only(signin_web, args.closed_web.rstrip("/"), args.next_dir)
    browser = owner = administrator = nobody = None
    if args.user:
        problems, browser = sign_in(web, args.issuer.rstrip("/"), args.user, os.environ["E2E_PASSWORD"], args.subject)
        failures += problems
        if browser:
            CARRIED[web] = {"Cookie": browser.cookie_header(web)}      # every screen below is read signed in
        if args.owner_user and args.owner_subject:       # the seeded owner, signed in as a second person (WEB-48)
            problems, owner_browser = sign_in(web, args.issuer.rstrip("/"), args.owner_user, os.environ["E2E_PASSWORD"], args.owner_subject)
            failures += problems
            owner = {"Cookie": owner_browser.cookie_header(web)} if owner_browser else None
        if args.admin_user and args.admin_subject:       # the deployment's administrator, signed in as a third person (WEB-49)
            problems, admin_browser = sign_in(web, args.issuer.rstrip("/"), args.admin_user, os.environ["E2E_PASSWORD"], args.admin_subject, tied=False)
            failures += problems
            administrator = {"Cookie": admin_browser.cookie_header(web)} if admin_browser else None
        if args.none_user and args.none_subject:         # a login holding no role at all, signed in as a fourth person (WEB-50)
            problems, none_browser = sign_in(web, args.issuer.rstrip("/"), args.none_user, os.environ["E2E_PASSWORD"], args.none_subject, tied=False)
            failures += problems
            nobody = {"Cookie": none_browser.cookie_header(web)} if none_browser else None
    else:
        print("—   sign-in, who-am-I and sign-out NOT checked: no --user (a machine with no issuer). CI checks them.")
    without_address = {"no address to write to": args.closed_web.rstrip("/"),
                       "a value that is not an address": args.bad_contact_web.rstrip("/")}
    failures += demo_request(web, without_address, args.contact, args.next_dir)

    (called, unreadable), checked = web_calls(), set(CALLS)
    failures += unreadable
    failures += [f"the web calls {m} {p}, which this check does not validate — add it to CALLS" for m, p in sorted(called - checked - ON_AN_ASSEMBLY - ON_A_PAYMENT - ON_A_PETITION - ON_A_MANDATE - ON_A_REGISTRATION)]
    failures += [f"CALLS lists {m} {p}, which no screen calls any more — remove it" for m, p in sorted((checked | ON_AN_ASSEMBLY | ON_A_PAYMENT | ON_A_PETITION | ON_A_MANDATE | ON_A_REGISTRATION) - called)]

    for (method, path), call in CALLS.items():
        url, body, *sent = call(entrance)              # a third element: headers the call needs (an Idempotency-Key)
        sent = sent[0] if sent else {}
        if path in ("/api/identity/me", "/api/identity/entrances/{entranceId}/mandates", *BOOK_READS) and api not in CARRIED:
            print(f"—   {method:4} {path}  NOT checked: it answers only a request with a token, and this run has none")
            continue
        if (method, path) in NEVER_RUN:                 # irreversible: asked with the controller's token only, never run (PM-BOOK-010, PM-SEC-001)
            if api not in CARRIED:
                print(f"—   {method:4} {path}  NOT checked: the refusal is the controller's, and this run has no token; the run itself is never made")
                continue
            refused, raw = fetch(api, url, body, method=method, headers=sent)
            bad = [] if refused == 403 and refusal_words(raw) else [f"{method} {path}: the controller's run answers HTTP {refused} {raw[:120]!r}, not the API's 403 in its words (PM-SEC-001)"]
            failures += bad
            print(f"{'ok ' if not bad else 'BAD'} {method:4} {path}  refused for the controller, never run")
            continue
        board = as_board() if (method, path) in KEPT_BY_THE_BOARD else None
        if board:                                       # the check's own token is the controller's: refused (G-3, PM-SEC-001)
            refused, _ = fetch(api, url, body, headers=sent)
            failures += [] if refused == 403 else [f"{method} {path}: the controller kept the accounts (HTTP {refused}), not refused (PM-SEC-001)"]
        status, raw = fetch(api, url, body, headers={**sent, **(board or {})})
        if status not in ((REFUSED_BY_DESIGN[(method, path)],) if (method, path) in REFUSED_BY_DESIGN else (200, 201)):
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
    failures += entrance_screen(api, web, entrance)
    failures += compliance_screen(api, web, entrance)
    failures += assembly_screen(api, web, entrance, signed_in=bool(args.user), owner_party=args.owner_party, owner=owner)
    failures += intake_screen(api, web, entrance)
    failures += statement_screen(api, web, entrance)
    failures += fund_accounts_screen(api, web, entrance)
    failures += disbursements_screen(api, web, entrance, signed_in=bool(args.user))
    failures += handover_screen(api, web, entrance, signed_in=bool(args.user))
    failures += book_screen(api, web, entrance, signed_in=bool(args.user))
    failures += household_screen(api, web, entrance, signed_in=bool(args.user))
    failures += animals_screen(api, web, entrance, signed_in=bool(args.user))
    failures += declarations_screen(api, web, entrance, signed_in=bool(args.user), owner_party=args.owner_party)
    failures += petitions_screen(api, web, entrance, signed_in=bool(args.user), owner=owner)
    failures += mandates_screen(api, web, entrance, signed_in=bool(args.user), administrator=administrator, spare_party=args.spare_party)
    failures += charges_issue(api, web, entrance)
    failures += payment_screen(api, web, entrance)     # last: it records a payment, so the seeded figures above are read first

    # WEB-22 (PM-SYS-010) — every screen under the console's layout ends in the catalogue's version, the API's own
    status, raw = fetch(api, "/api/law/version")
    served = json.loads(raw) if status == 200 else {}
    footer = f"Каталог на правилата v{served.get('catalogueVersion')} · изчислител {served.get('engineVersion')}"
    under = ["/portfolio", "/debts", "/compliance", f"/entrance?entrance={entrance}", f"/entrance/charges?entrance={entrance}&period=2026-09",
             f"/entrance/fund?entrance={entrance}", f"/entrance/payments?entrance={entrance}",
             f"/entrance/statement?entrance={entrance}", f"/entrance/fund/accounts?entrance={entrance}",
             f"/entrance/fund/disbursements?entrance={entrance}", f"/entrance/fund/handover?entrance={entrance}",
             f"/entrance/household?entrance={entrance}", f"/entrance/animals?entrance={entrance}",
             f"/entrance/declarations?entrance={entrance}"]
    # once, inside the page's <footer> — the sidebar may be streamed after it, so its place in the text says nothing
    in_footer = re.compile(r"<footer[^>]*>(.*?)</footer>", re.S)
    bare = [url for url in under if [visible_text(f).strip() for f in in_footer.findall(fetch(web, url)[1])] != [footer]]
    failures += [f"{url}: no single footer saying {footer!r}" for url in bare] if served else ["/api/law/version: no version served"]
    print(f"{'ok ' if served and not bare else 'BAD'} PM-SYS-010 the footer  {len(under) - len(bare)}/{len(under)} screens show {footer!r}")

    # WEB-24 — no console screen carries the design's sample person, firm or "recently opened" addresses any more
    names = ["Мария Петрова", "Домоуправител Про", "Последно отваряни", "Витоша 102", "Младост 3"]
    pages = {url: fetch(web, url) for url in under}
    failures += [f"{url}: HTTP {status} — not read for sample names" for url, (status, _) in pages.items() if status != 200]
    named = [(url, name) for url, (_, page) in pages.items() for name in names if name in visible_text(page)]
    failures += [f"{url}: still shows the design's sample ({name!r})" for url, name in named]
    print(f"{'ok ' if not named else 'BAD'} the sidebars  {len(under)} screens carry none of the design's sample names")

    # WEB-21 — a parameter given twice arrives as a list: every live screen answers it, none fails on it
    twice = ["/compliance?period=2026-09&period=2026-08", "/debts?asOf=2026-09-30&asOf=2026-10-20", "/portfolio?asOf=2026-09-30&asOf=2026-10-20", "/portfolio?show=owing&show=fund",
             f"/entrance?entrance={entrance}&entrance={entrance}", f"/entrance/fund?entrance={entrance}&entrance={entrance}",
             f"/entrance/charges?entrance={entrance}&entrance={entrance}", f"/entrance/fund?entrance={entrance}&period=2026-09&period=2026-08",
             f"/entrance/fund?entrance={entrance}&account=fund&account=cash", f"/entrance/fund?entrance={entrance}&status=paid&status=committed",
             f"/entrance/charges?entrance={entrance}&period=2026-09&period=2026-08", f"/entrance/charges?entrance={entrance}&multiple=3&multiple=4"]
    broken = [(url, status) for url in twice if (status := fetch(web, url)[0]) != 200]
    failures += [f"{url}: HTTP {status} for a parameter given twice" for url, status in broken]
    print(f"{'ok ' if not broken else 'BAD'} a parameter given twice  {len(twice) - len(broken)}/{len(twice)} screens answer")

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

    failures += units_screen(api, web, entrance, signed_in=bool(args.user), owner=owner)
    failures += registry_screen(web=web, api=api, signed_in=bool(args.user), administrator=administrator, owner=owner, nobody=nobody, owner_party=args.owner_party)   # last: it registers an entrance

    if browser:
        failures += sign_out(web, args.issuer.rstrip("/"), browser)

    for failure in failures:
        print(f"  ✗ {failure}")
    print(f"e2e: {'FAILED — ' + str(len(failures)) + ' problem(s)' if failures else 'the chain holds'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
