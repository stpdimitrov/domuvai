---
name: zues-audit
description: "Audit an implementation against the ЗУЕС rule catalogue — read-only. Reports each rule IMPLEMENTED / PARTIAL / MISSING with file:line evidence, and checks the standing constraints (banned words, legal numbers only in @zues/law, module boundaries, unconfirmed numbers). Never edits — a fix is a separate slice. Use to verify a module or a set of rules, and run it with fresh context, separate from the session that wrote the code."
---

# Read-only audit

Building and checking need different mindsets. Reviewing your own work with the intent still in mind finds nothing — you see what you meant, not what you wrote. This skill reads only what is committed and reports what is true, and it **never edits**. A defect it finds is a finding, not a fix: the fix is a slice, with its own contract and tests.

The gate pack is the mechanical auditor — it proves a rule *has* a test named after it. This skill is the judgement auditor: it asks whether the code actually does what the rule *means*, which no gate can check.

## 1. Trust the code, not the documents

Verify against the source tree, never against a document that claims coverage. Docs drift — a `// Rule:` comment with no test, a README listing a skill that does not exist, a traceability row for a test that only touches the rule in passing. The audit's whole value is catching exactly that gap.

## 2. Boot — read before judging

1. `docs/RULES.md` (and `rules.json`) for **the audited domain only** — the rule texts, quoted, are the yardstick
2. `docs/adr/` for the decisions the code must honour
3. The module's source and tests
4. `docs/TRACEABILITY.md` — but as a claim to check, not a fact to trust

## 3. Verdict per rule — with file:line evidence

| Verdict | Means |
|---|---|
| **IMPLEMENTED** | A test named after the rule ID proves it, and code carries `// Rule: PM-XXX-000` that actually enforces it |
| **PARTIAL** | Code exists but no test named after the ID, or the test is present but does not exercise the rule's acceptance criterion, or only part of the rule is covered |
| **MISSING** | Neither |

Name the file and line for every verdict. "Counting is not checking" — do not report *how many* rules pass; report *which*, and quote the rule text you judged against.

## 4. Standing constraints — check every one

- **No legal number outside `@zues/law`.** A statutory threshold, deadline, majority or multiplier written as a literal anywhere else is a finding, even if a test passes.
- **Unconfirmed numbers** (`verified: false`) are config lookups + `TODO(legal): PM-XXX-000`, never invented literals.
- **Banned words** in identifiers: `tenant`, bare `user` as a domain term, `fee`/`balance` as stored columns, `building` as the tenant key.
- **Module boundaries** (ADR-003): no import between modules except through a published API; no cross-module database access; cross-module reactions go through outbox events.
- **Money** is integer minor units, no floats; **ideal parts** exact decimals summing to 100% per entrance (ADR-006).
- Every stored charge, decision or compliance task carries `basis`, `basis_hash`, `law_version`, `engine_version` (ADR-001 + amendment).
- Votes, decisions, postings and personal-data access are append-only and audited.

## 5. Report — most severe first

A ranked list: MISSING and constraint breaches first, then PARTIAL, then a one-line note of what is solid. Each item: the rule ID, its text, the verdict, `file:line`, and — for a defect — the concrete failure (what input produces what wrong result), never a vague "could be better". End with the single most important thing to fix next.

## 6. Never

- Never edit, even a one-line fix, even a typo. Raise it; the next slice fixes it.
- Never invent a legal threshold to "confirm" a rule. No rule covering the case is itself the finding: stop and say so.
- Never pass a rule because the `// Rule:` tag is present. The tag is a claim; the test is the evidence.
