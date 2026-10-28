import assert from "node:assert/strict";
import { test } from "node:test";
import { accountOf, byPurpose, fundMissing, registrationRefused, unheld, type Account, type AccountInput } from "./fundAccount.ts";

const fund: Account = { id: "a-1", purpose: "REPAIR_RENEWAL", iban: "BG80BNBG96611020345678", holderName: "Иван Петров", holderKind: "MANAGER" };
const operating: Account = { id: "a-2", purpose: "OPERATING", iban: "BG18RZBB91550123456789", holderName: "СС „Изгрев 5“", holderKind: "ASSOCIATION" };
const typed = (p: Partial<AccountInput>): AccountInput =>
  ({ purpose: "REPAIR_RENEWAL", iban: " BG80 BNBG 9661 1020 3456 78 ", holderName: " Иван Петров ", holderKind: "MANAGER", ...p });

// That the fund's account cannot be the operating account is the API's (one account per purpose, one IBAN anywhere: a 409);
// the web offers no held purpose and shows the API's refusal (the PM-SEC-001 test below, and tools/check_e2e.py).
test("PM-FUND-004 the account is in a named holder's name, one per purpose, the fund's apart from the operating account", () => {
  assert.deepEqual(accountOf(typed({})), { purpose: "REPAIR_RENEWAL", iban: "BG80 BNBG 9661 1020 3456 78", holderName: "Иван Петров", holderKind: "MANAGER" });
  for (const p of [{ holderName: " " }, { holderKind: "" }, { holderKind: "FIRM" }, { iban: "" }, { purpose: "CASH" }]) {
    assert.ok("refused" in accountOf(typed(p)), JSON.stringify(p));
  }
  assert.deepEqual(unheld([fund, operating]), []);
  assert.deepEqual(unheld([operating]), ["REPAIR_RENEWAL"]);
  assert.deepEqual(byPurpose([operating, fund]).map((g) => [g.purpose, g.accounts.map((a) => a.id)]),
    [["REPAIR_RENEWAL", ["a-1"]], ["OPERATING", ["a-2"]]]);
  assert.deepEqual(byPurpose([{ ...fund, purpose: "SAVINGS" }]).map((g) => [g.purpose, g.accounts.length]),
    [["REPAIR_RENEWAL", 0], ["OPERATING", 0], ["SAVINGS", 1]]);
});

test("PM-FUND-001 an entrance with no repair-and-renewal account is flagged, and that purpose is offered first", () => {
  assert.equal(fundMissing([]), true);
  assert.equal(fundMissing([operating]), true);
  assert.equal(fundMissing([fund]), false);
  assert.deepEqual(unheld([]), ["REPAIR_RENEWAL", "OPERATING"]);
});

test("PM-SEC-001 who may register an account is the API's: its refusal and its conflicts are shown in its words", () => {
  const said = (status: number, error: string) => registrationRefused({ error: { error }, response: { status } });
  assert.equal(said(403, "PM-SEC-001: the entrance's accounts are kept by its manager or board"),
    "Нямате право да регистрирате сметка на входа: PM-SEC-001: the entrance's accounts are kept by its manager or board");
  assert.match(said(409, "IBAN BG80BNBG96611020345678 is already registered — fund monies are not commingled (PM-FUND-005)"), /PM-FUND-005/);
  assert.match(registrationRefused({ response: { status: 400 } }), /HTTP 400/);
});
