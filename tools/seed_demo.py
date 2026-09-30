#!/usr/bin/env python3
"""
Seed one demo entrance through the public API only, never SQL, so every write it makes is exercised as a client
makes it (E2E-01 D2). The end-to-end check (tools/check_e2e.py) expects exactly this entrance; a local run uses it
to have something true on screen. Prints the entrance id.

Seeding is once per database: when the demo entrance is already there, its id is printed and nothing is written
(the accounts' IBANs are unique, so a second copy could not be made anyway). To seed again, recreate the database.

Usage: seed_demo.py [API_URL]   (default http://localhost:8080)
"""
import json
import sys
import urllib.error
import urllib.request
import uuid

API = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")


def call(method, path, body=None, headers=None):
    request = urllib.request.Request(
        API + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **(headers or {})},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        sys.exit(f"seed: {method} {path} → {e.code} {e.read().decode()}")


def iban(bban):
    """A Bulgarian IBAN with a correct mod-97 check (ISO 13616) for a demo BBAN."""
    digits = "".join(str(int(c, 36)) for c in bban + "BG00")
    return f"BG{98 - int(digits) % 97:02d}{bban}"


LABEL = "ул. Шипка 14, вх. Б"
seeded = [e["id"] for e in call("GET", "/api/registry/entrances") if e["label"] == LABEL]
if seeded:
    print(f"seed: {LABEL} is already seeded — nothing written", file=sys.stderr)
    print(seeded[0])
    sys.exit(0)

entrance = call("POST", "/api/registry/entrances",
                {"label": LABEL, "managementForm": "GA", "address": "София, ул. Шипка 14"})["entranceId"]

# (designation, ideal parts — summing to 100%, adults, children under 6)
UNITS = [("ап. 1", "18.500000", 2, 0), ("ап. 2", "16.250000", 2, 1), ("ап. 3", "15.750000", 1, 0),
         ("ап. 4", "17.000000", 2, 2), ("ап. 5", "16.500000", 2, 0), ("ап. 6", "16.000000", 1, 0)]
unit_ids = call("POST", f"/api/registry/entrances/{entrance}/units", {"units": [
    {"designation": d, "idealParts": parts, "separateEntrance": False, "unitType": "APARTMENT"} for d, parts, _, _ in UNITS
]})["unitIds"]

for unit, (_, _, adults, children) in zip(unit_ids, UNITS):
    members = [{"isChildUnder6": False, "validFrom": "2026-01-01"}] * adults + [{"isChildUnder6": True, "validFrom": "2026-01-01"}] * children
    call("POST", f"/api/registry/entrances/{entrance}/units/{unit}/household", {"members": members})


def party(name):
    return call("POST", "/api/registry/parties", {"fullName": name})["partyId"]


OWNERS = [["Иван Петров"], ["Елена Колева", "Георги Колев"], ["Мария Иванова"], ["Стефан Димов"], ["Надя Тодорова"], ["Петър Георгиев"]]
parties = {}
for unit, names in zip(unit_ids, OWNERS):
    for name in names:
        parties[name] = parties.get(name) or party(name)
        call("POST", f"/api/registry/entrances/{entrance}/units/{unit}/titles",
             {"partyId": parties[name], "share": str(1 / len(names)), "titleRole": "OWN", "validFrom": "2020-01-01"})

manager = parties["Мария Иванова"]  # the домоуправител, who holds both accounts (чл. 50)
incoming = party("Красимир Ангелов")  # the manager taking over at the handover
call("POST", f"/api/money/entrances/{entrance}/fund-accounts", {
    "purpose": "REPAIR_RENEWAL", "iban": iban("UNCR70001512998102"), "holderName": "Мария Иванова", "holderKind": "MANAGER", "holderPartyId": manager})
call("POST", f"/api/money/entrances/{entrance}/fund-accounts", {
    "purpose": "OPERATING", "iban": iban("UNCR70001512345678"), "holderName": "Мария Иванова", "holderKind": "MANAGER", "holderPartyId": manager})

# September's charges, issued on the demo decision — the same basis the charges screen previews
call("POST", f"/api/money/entrances/{entrance}/charge-runs", {"period": "2026-09", "legalDate": "2026-09-01", "lines": [
    {"stream": "MANAGEMENT", "key": "PER_PERSON", "decisionId": "GA-2026-03-12-4", "rateMinor": 600},
    {"stream": "MAINTENANCE", "key": "PER_PERSON", "decisionId": "GA-2026-03-12-4", "rateMinor": 450},
    {"stream": "REPAIR_FUND", "key": "BY_IDEAL_PARTS", "decisionId": "GA-2026-03-12-4", "totalMinor": 60_000},
]})

# Payments: four units pay into the fund's account, two into the operating one
for unit, amount, into in zip(unit_ids, [30_000, 25_000, 20_000, 30_000, 4_000, 3_000],
                              ["REPAIR_RENEWAL"] * 4 + ["OPERATING"] * 2):
    call("POST", f"/api/money/entrances/{entrance}/payments",
         {"unitId": unit, "amountMinor": amount, "receivedInto": into, "valueDate": "2026-09-10"},
         {"Idempotency-Key": str(uuid.uuid4())})

# Disbursements from the fund: works on a GA decision, paid; an emergency; a measure, cancelled; one committed
base = f"/api/money/entrances/{entrance}/fund/disbursements"
roof = call("POST", base, {"amountMinor": 48_000, "purpose": "WORKS", "authorisedBy": manager, "decisionId": "GA-2026-03-12-3"})
call("POST", f"{base}/{roof['id']}/pay", {"paidOn": roof["committedOn"], "paidBy": manager})
call("POST", base, {"amountMinor": 12_500, "purpose": "WORKS", "authorisedBy": manager,
                    "emergencyJustification": "теч от покрива над ап. 6 след бурята"})
lift = call("POST", base, {"amountMinor": 9_900, "purpose": "PASSPORT_MEASURE", "authorisedBy": manager,
                           "decisionId": "GA-2026-03-12-5", "passportMeasure": "ТП 2019, мярка 4.2 — асансьорна уредба"})
call("POST", f"{base}/{lift['id']}/cancel", {"cancelledBy": manager, "reason": "изпълнителят се отказа"})
last = call("POST", base, {"amountMinor": 30_000, "purpose": "GA_PURPOSE", "authorisedBy": manager, "decisionId": "GA-2026-03-12-6"})

# The handover to the incoming manager, dated on the last commitment — so every open one is inherited, even if the
# seed ran across midnight — with the bank's balance matching the ledger
fund = call("GET", f"/api/money/entrances/{entrance}/fund")
call("POST", f"/api/money/entrances/{entrance}/fund/handover-statements", {
    "handoverOn": last["committedOn"], "from": "2026-01-01", "outgoingPartyId": manager, "incomingPartyId": incoming,
    "bankBalanceMinor": fund["balanceMinor"]})

print(entrance)
