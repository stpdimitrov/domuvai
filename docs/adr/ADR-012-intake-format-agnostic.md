# ADR-012 — Intake is format-agnostic: a per-import column mapping, not a canonical spreadsheet

| | |
|---|---|
| **Status** | **Accepted** · 2026-09-21 — the owner accepted; format-agnostic intake, with the go-live gate and preventive/recovery in §7. Building assumes the first client sheet arrives **after** the planned implementation |
| **Date** | 2026-09-21 |
| **Deciders** | Stoyan Dimitrov |
| **Supersedes** | `docs/STAGE1-ADDENDUM.md` §1's *"Ask the pilot firm for one real spreadsheet … it defines the intake schema"* — the schema is the domain's, not any one sheet's |
| **Blocks** | The remaining Gate-1 intake work (the "rich mapping") and its slices |

---

## 1 Context

Intake — the migration seam every pilot starts with — was planned around one real fee spreadsheet: `STAGE1-ADDENDUM.md` §1 says *"Ask the pilot firm for one real spreadsheet … it defines the intake schema."* Two facts, stated by the owner, break that premise:

1. **A pilot sheet may never arrive.** Planning that treats it as a prerequisite leaves the last Gate-1 gap — and the whole intake feature — blocked indefinitely on an external file the project does not control.
2. **Every firm's format differs.** Even with one sheet in hand, its columns, order, headers, number formats and extra data are that firm's, not a standard. Hard-coding one firm's layout is wrong the moment a second firm appears — and the product is multi-firm SaaS (ADR-005, `SAASPLAN.md`).

So *"the spreadsheet defines the intake schema"* is a category error. **The target schema is ours and already known** — the domain fields the book and the engine require: unit designation and built area, ideal parts, occupants, owner/user names, periods of non-use (PM-BOOK-002), and the fee the firm charges (for the reproduce-to-the-cent check, PM-FEE-014). What is *unknown and variable* is the **source** — which of a firm's columns carry those fields. That is a per-import mapping problem, not a schema problem, and `STAGE1-ADDENDUM.md` §1 already anticipated it (step 2 *Profile* — detect columns, propose a mapping; step 3 *Map* — a human confirms, HUMAN_RELEASE). The earlier "wait for the sheet" framing under-used that design.

## 2 Decision

**Intake is format-agnostic. It maps each uploaded file onto the known domain fields, per import; it never assumes a canonical spreadsheet.**

The pipeline (`STAGE1-ADDENDUM.md` §1, built as the mechanism rather than left as a wish):

1. **Profile** — read the uploaded file, detect its columns; nothing is guessed silently — an unmapped column is surfaced, not dropped.
2. **Propose** — suggest a mapping of detected columns → domain fields by header heuristics.
3. **Confirm** — a human accepts or corrects the mapping (HUMAN_RELEASE; an import writes the legal record).
4. **Validate → dry-run → commit** — the existing path (S-31 dry-run, S-36 commit), now driven by the confirmed mapping instead of a fixed 4-column CSV.

**What this changes:** the remaining intake work is **unblocked** — it proceeds now, without a pilot sheet, because the mechanism handles any format. **What it does not change:** a real pilot sheet is still wanted — but as **validation** (reproduce-to-the-cent against real numbers) and as one more input to the mapping step, **never** as the definition of a schema.

## 3 Options considered

| Option | Complexity | Handles "no sheet / every format differs" | Verdict |
|---|---|---|---|
| **A · Wait for a pilot sheet, then hard-code its format** | Low | No — may never arrive; one firm's layout ≠ the next's | Rejected. Blocks indefinitely on an external file, and is wrong on arrival for firm #2 |
| **B · A fixed "superset" schema of all likely columns** | Medium | No — you cannot enumerate every firm's format; brittle | Rejected. Still guessing, and every new firm breaks it |
| **C · Per-import column mapping (profile → propose → confirm → dry-run → commit)** ✅ | Medium | Yes — source discovered at import time, target from our rules | **Chosen.** Buildable now; robust to heterogeneity; already the addendum's steps 2–3 |

The distinction between C and A is the whole decision: A makes the source format authoritative and external; C makes the **domain** authoritative and the source a **mapped input**.

## 4 Consequences

**Good**

- The last Gate-1 intake gap is **unblocked without external data** — work resumes now.
- Robust to format heterogeneity by construction: firm #2's sheet is a new mapping, not a rewrite.
- Matches `STAGE1-ADDENDUM.md` §1 and keeps the human-confirm gate on the legal write.

**Bad, and accepted**

- Without a real sheet, the **reproduce-to-the-cent proof runs against constructed fixtures** — several synthetic sheets in *different* shapes, which exercises the mapping and the engine but cannot prove a real firm's numbers. That final validation stays open until a real sheet (or a pilot) appears — a documented residual risk, not a blocker.
- A mapping step is more UX and more code than a fixed importer — accepted, because a fixed importer is the wrong artifact.

**How it is enforced**

- The dry-run gate (reproduce & diff, S-31) runs on every import; an import cannot **commit** with a required domain field unmapped or unreadable.
- Intake fixtures in the test suite carry **at least two distinct column layouts**, so a regression to a single hard-coded format fails a test.

## 5 What would reverse this

A single pilot firm signs, their format is stable, and they are the only customer for the foreseeable future — then a fixed importer for that one layout could be simpler. Unlikely while the product is multi-firm SaaS; if it happens, that is a measured trigger, not a judgement call.

## 6 Deferred

- The **profiling heuristics** (how columns are matched to fields) — start simple (header-name match), improve iteratively.
- **XLSX** parsing and **locale number formats** — CSV / typed input first; format breadth grows behind the same mapping seam.
- The **mapping UI** — a frontend concern (ADR-011), gated on the frontend green light; the API exposes profile + confirm.

## 7 Preventive measures & recovery — the risk boundary

Building the fee engine and intake before a real sheet exists leaves one validation open (§4): our figures are proved against constructed fixtures, not a real firm's numbers. The owner's plan is that the first client sheet arrives **after** the planned implementation, so this boundary must hold for the whole build. It is **bounded, not eliminated** — and proceeding carries no *additional* risk over waiting, because of the following.

**The line: build freely; do not go live on real money until a real sheet reproduces to the cent.**

- **Go-live gate (blocks production, not construction).** No entrance is billed for real, and no firm is onboarded onto live charges, until at least one real fee sheet reproduces to the cent through the dry-run. This is Gate 1's original kill-signal, held as a go-live gate; building, testing and demoing proceed freely.

**Preventive measures (folded into the intake slices, not a separate work stream):**

- **Every sheet-contradictable assumption stays dated config, never a literal** (ADR-001) — a correction is then a *data* change, not a code change. The 24 unconfirmed numbers already are.
- **An adversarial fixture corpus** stands in for the pilot sheet: several synthetic sheets in **different shapes** with edge cases (no-owner unit, business use, absence, children, zero occupants, BGN legacy, rounding boundaries). A regression to a single hard-coded format fails a test (the §4 enforcement, strengthened).
- **A reproduction harness** — the dry-run is built so a real sheet, when it arrives, is validated in one step → an instant per-cent diff.

**Recovery — most of it already exists by design:**

- **Versioned, reproducible charges** (ADR-001: `basis` · `basis_hash` · `law_version` · `engine_version`) → if the engine is later corrected, the exact charges computed under the old logic are identifiable and recomputable.
- **Revertible imports** (S-36, by `import_id`) → a wrong import is undone cleanly.
- **Dated config** (ADR-001) → a wrong number is corrected forward, never by rewriting history.

So the missing sheet delays **validation**, not **construction**; the downside is capped by the go-live gate and made cheap by the recovery mechanisms already in place.
