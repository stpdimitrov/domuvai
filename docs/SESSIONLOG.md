# Session log

Append only. Never edit an earlier entry. Commit the entry with the work it describes.

---

## S-00 · 2026-09-13 · Stage 1 closed, repo seeded

**Did** — moved Stage 1 out of a personal vault and into this repo, so any session (or any person) boots from the same place. Nine ADRs written; ADR-001 Accepted, eight Proposed.

**Decisions** — ADR-001 … ADR-009. Three changed the design materially:
- **ADR-003** replaced thirteen independently deployed services with three deployables and fourteen CI-enforced module boundaries. The trigger was a correctness bug, not taste: `money` consumed ideal parts and occupancy as *events*, so a charge run computed from projections that could lag. A wrong bill with a valid `basis_hash` and a clean audit trail is worse than an obvious error.
- **ADR-001 amendment** added `engine_version` to every receipt. `law_version` pinned the data but nothing pinned the code, so a bug fix in `computeCharge` would silently rewrite history.
- **ADR-004** settled shared facilities as a cost-sharing agreement with a custodian entrance, keeping `entrance_id` as the only tenant key.

**Added** — `intake` as the fourteenth module. Gate 1 says "reproduces the firm's spreadsheet to the cent" and nothing could read a spreadsheet.

**Open** — A7 traceability · A8 event schemas · A9 OpenAPI · A10 DDL · A11 test plan. Eight ADRs await Accept. Counsel and the pilot firm are unblocked by nothing in here.

**Read first next time** — `docs/INDEX.md`, this entry, `docs/adr/ADR-003`, `docs/SEQUENCE.md`.

---

## S-01 · 2026-09-13 · A8 — event contracts

**Did** — generated the envelope plus one JSON Schema and one worked example per event, from a single catalogue in `tools/build_events.py`. Schemas are generated, never hand-written, so the contract cannot drift from the catalogue.

**Found** — the documents said **17 events**. There are **38**. The count had been stale since the deployables table grew; corrected in `docs/INDEX.md` and ADR-003. Third stale count found in this project by generating instead of reading.

**Two guards, both proved against a real failure:**
- `build_events.py` fails if a module publishes an event with no schema, or if a schema exists that nothing publishes. The first version of the check scanned whole documents and flagged `IdealParts` and `ManagementCharge` as missing events — entity names, not events. Rewritten to read only the *Publishes* column.
- `validate_events.py` validates every example against the envelope and its payload schema. Broke it deliberately twice: a float in `Money.amount_minor` → rejected; a missing `entrance_id` → rejected. Restored, green.

**Types that now fail validation rather than review:** money as a float, ideal parts as a float, an implicit majority denominator, an event without `entrance_id` or `law_version`.

**Open** — A7 traceability · A9 OpenAPI · A10 DDL · A11 test plan. Eight ADRs still Proposed.

**Read first next time** — `docs/INDEX.md`, this entry, `docs/events/README.md`.
