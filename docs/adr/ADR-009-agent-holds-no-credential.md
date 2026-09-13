# ADR-009 — The agent holds no write credential, and prohibited capabilities are not built

| | |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-09-13 |
| **Deciders** | Stoyan Dimitrov |
| **Supersedes** | — |
| **Blocks** | A34 agent plumbing, and the database role grants in A12 |

---

## 1 Context

The product's differentiator is an AI that does the manager's work. Bulgarian law makes that a narrow path.

**The offices are people.** PM-AI-001: the AI must not appear as manager, chair, controller or cashier in any protocol, contract or statutory register. чл. 19 elects a person; чл. 46б and чл. 47а register a person or a trader. There is no legal slot for software.

**Legal effect requires a named human.** PM-AI-002: every AI-produced act with legal effect is released by a named human holding the corresponding role, and the release is recorded. PM-AI-012: the AI may not decide, vote, exercise a majority or represent an owner — it may compute and display a tally.

**Money is closed to it.** PM-AI-008: the AI must not post a financial entry, move money or alter an issued charge. It may propose a draft posting for human release. ADR-007 already establishes that the platform holds no money at all; this narrows further to the agent.

**Grounding is not optional.** PM-AI-004: no threshold, deadline, majority or penalty that does not resolve to a rule ID and its dated constant. PM-AI-006: the agent executes under the permissions of the *requesting user*, never the manager's or the company's.

The question this ADR answers is not *whether* to constrain the agent — the rules settle that — but **where the constraint lives**. A constraint in a prompt is a suggestion. A constraint in a runtime check is a bug away from failing. A constraint in a credential cannot be argued with.

---

## 2 Decision

**The agent's limits are enforced by credentials and by absence, not by instructions.**

Three mechanisms, in descending order of strength:

**1 · Absence.** A PROHIBITED capability is **not implemented**. There is no code path that posts a journal entry from the agent, no function that casts a vote on someone's behalf, no field anywhere that could name the agent as chair. You cannot misuse what does not exist, and no prompt can reach it.

**2 · Credential.** The `agent` module runs under its own database role with `INSERT` on `agent.proposal*` and nothing else. It has no write grant on `money`, `assembly`, `evidence`, `registry` or `identity-org`. Reads go through `packages/policy` (ADR-002) carrying the **requesting user's** subject, so the agent can never see more than the person asking.

**3 · Release.** A proposal becomes an act only when a named officer releases it. The release record carries who, when, which role they held at that moment, and the proposal's full grounding.

Every capability is classified `AUTONOMOUS`, `HUMAN_RELEASE` or `PROHIBITED` (PM-AI-003). The classification is **versioned product policy, not law** — it lives with the agent module, is reviewed like code, and is never inferred at runtime.

Deadlines and thresholds come from the temporal engine in `@zues/law` (ADR-001). The model may draft the sentence around a date; it may never produce the date.

---

## 3 Options considered

| Option | Fails how | Verdict |
|---|---|---|
| **System-prompt constraints** | A prompt is a suggestion to a probabilistic system. One phrasing away from failure, and it leaves no evidence trail when it fails | Rejected |
| **Runtime guard on a shared credential** | Correct until the guard has a bug, and the blast radius is a financial posting. Also indistinguishable, in an audit, from having no guard | Rejected |
| **Separate credential, runtime classification, prohibited paths absent** ✅ | Prohibited acts are unreachable; permitted-but-released acts fail closed at the database if the release is skipped | **Chosen** |
| **Full autonomy with after-the-fact review** | Directly contrary to PM-AI-002 and PM-AI-008, and an unreleased act with legal effect may be void | Rejected |

---

## 4 Consequences

**Good**

- The strongest limits need no correct code to hold: a missing function and an absent grant fail safe.
- Every AI interaction is evidence — prompt, sources, model version, catalogue version, output, releaser (PM-AI-007) — which is what a ЗАНН defence actually needs.
- The agent can be improved aggressively inside `HUMAN_RELEASE` without touching its boundary.

**Bad, and accepted**

- Adding an autonomous capability is a schema and credential change, not a config toggle. Deliberate: that friction is the control.
- Residents must be told they are talking to an assistant and given a route to the human (PM-AI-011), which costs conversions on the resident surface.
- Draft-then-release is slower than acting. It is also the only lawful shape.

**How it is enforced**

1. The `agent` database role is created with explicit grants only; a migration widening it fails CI.
2. A test runs the agent's full capability list against a database where every other write grant is revoked, and asserts no capability succeeds outside `agent.proposal*`.
3. No `PROHIBITED` capability has an implementation — a test asserts the capability registry's prohibited entries resolve to no handler.
4. Any act with legal effect lacking a release record is rejected by a database constraint, not by application code.
5. The agent's read path may not be called without a requesting-user subject; the type makes it impossible.
6. Per-capability draft-acceptance, correction and escalation rates are measured, and a capability may be auto-demoted (PM-AI-014).

---

## 5 What would reverse this

- A statutory change creating a lawful electronic agent for condominium management. None is in prospect.
- Sustained evidence from PM-AI-014 metrics that a specific `HUMAN_RELEASE` capability is accepted essentially always — and even then the move is to that one capability, argued on its own, never to the boundary as a whole.

---

## 6 Deferred

- Whether personal data reaches an external model provider at all, or only a redacted projection (PM-AI-013). Depends on the processing agreement and the EU-region constraint; decide before A34.
