# Event contracts

**Generated. Do not hand-edit any `.json` in this folder** — change `tools/build_events.py` and re-run it.

```bash
cd tools && python3 build_events.py && python3 validate_events.py
```

`build_events.py` fails if a module publishes an event in `docs/DEVBRIEF.md` with no schema, or if a schema exists that nothing publishes. `validate_events.py` fails if any example violates the envelope or its payload schema. Both run in CI.

## The envelope

Every message carries `event_id`, `type`, `version`, `entrance_id`, `occurred_at`, `causation_id`, `correlation_id`, `law_version`, `producer`, `payload`.

| Field | Why it is not optional |
|---|---|
| `event_id` | The idempotency key. Delivery is at least once, so every consumer deduplicates on it |
| `entrance_id` | The only tenant key (ADR-005). Ordering is guaranteed **per entrance only** |
| `law_version` | Which catalogue produced this (ADR-001). Without it a past event cannot be re-read correctly |
| `causation_id` | The event that caused this one. Null only for an act a human initiated |

## Types that are enforced, not suggested

| Type | Shape | Rule |
|---|---|---|
| `Money` | `{amount_minor: integer, currency: "EUR"}` | Integer minor units. A float fails validation (ADR-006, PM-FEE-016) |
| `IdealParts` | string, `^\d{1,3}\.\d{1,6}$` | Exact decimal. A float fails validation (ADR-006, PM-ORG-002) |
| `LegalDate` | `date` | Europe/Sofia calendar day (PM-SYS-004) |
| `Instant` | `date-time` | UTC |
| `Denominator` | `TOTAL` \| `REPRESENTED` | Never implicit (ADR-008, PM-VOTE-012) |

`ChargeIssued` additionally carries `basis_hash`, `law_version` and `engine_version` — the receipt that makes a past charge reproducible (ADR-001 amendment).

## Versioning

`version` starts at 1. Adding an optional field is a minor change and does not bump it. Removing a field, renaming one, or narrowing a type is a **new version**: publish both until every consumer has moved, then retire the old one. Consumers ignore unknown fields; producers never reuse a field name with a different meaning.

## Delivery

At least once, through the outbox. The producer writes the state change and the outbox row in **one transaction**. Ordering holds per `entrance_id` and nowhere else — no consumer may assume a global order.
