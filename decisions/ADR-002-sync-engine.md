# ADR-002: Sync Engine for Phase 0/1

**Status:** Proposed
**Date:** 2026-05-06
**Deciders:** Jon Townsend (solo)

## Context

Quiet is local-first by mandate: capture must commit before any network round-trip. Cross-device sync is required for the trust narrative — a capture that exists only on a phone that is later lost is data loss the user observes. The prototype's conflict UX is a single banner: *"This loop was triaged on another device. Showing the latest version."* — i.e., whole-record last-writer-wins with a visible signal, never a merge resolver, never a "pick a side" dialog.

Write set is small and unusually friendly:
- `capture_items` — append-only, no conflicts possible
- `plans` — exactly one per capture item; only mutable fields are `disposition`, `when_at`, `where_text`, `action_text`; LWW keyed on `triaged_at` is acceptable per the design
- `closure_events` — append-only, idempotent
- `waiting_on.nudge_date` — server-driven, races are rare

NFRs: <100ms write-to-local; sync convergence ≤2s when both devices are online (spike target; v1 spec sets ≤5s); offline queue durable across `kill -9`, OS restart, battery exhaustion; schema must evolve without breaking older clients in the wild.

Phase 2 (legal pack, weeks 16–24) introduces an event-sourced audit log and per-tenant DEKs. This ADR is scoped to Phase 0/1 only and explicitly flags the Phase 2 revisit.

## Decision

**Option B — PowerSync** (Postgres → SQLite, op-log replication, mobile-first SDKs), with all writes routed through an `outbox` table and a `(device_id, client_seq)` idempotency key. Plan rows merge LWW on `triaged_at`; capture and closure are append-only.

## Options Considered

| Dimension | A. CRDT (Automerge / Yjs) | B. PowerSync / Replicache / ElectricSQL | C. SQLite + custom delta sync |
|---|---|---|---|
| Banner UX fit (whole-record LWW) | Poor: field-level merges produce Frankenstein records (when from device 1, action from device 2). To get whole-record LWW we'd build it on top of the CRDT, defeating the choice. | Native fit: row-level LWW is the default conflict mode. The banner fires when LWW resolves a non-trivial diff. | Perfect fit by construction; we write the merge rule. |
| Offline durability | Strong; CRDT log is append-only on disk. Lib weight on mobile is non-trivial. | Strong; SDK persists ops in SQLite; replays after `kill -9`. | Strong; outbox is `WAL`-backed SQLite. We own the recovery code. |
| <100ms local write | Yes; in-process. | Yes; SDK writes to local SQLite first, replicates async. | Yes; single SQLite txn. |
| Schema evolvability | Hard. Automerge v2 binary changes are migrations the app must drive on every old client. | Good: PowerSync sync rules + Postgres migrations; old clients see views, additive columns are transparent. | Best: event-sourced log + projection rebuild; we control every migration path. Cost is the rebuild code. |
| Solo-dev cost to ship | ~3 weeks for the lib; many weeks fighting LWW-on-CRDT semantics. | ~1–2 weeks integration; vendor SDK does the heavy work. | ~6 engineer-weeks per the v1 spec; entirely on us. |
| Audit defensibility (Phase 2) | Possible; not the natural shape. | Op-log is exportable but vendor-shaped; SOC 2 / partner-defensibility likely requires self-hosting or migration. | Strongest; event log is hash-chainable and inspectable. v1 spec recommends this for the legal pack. |

## Trade-off Analysis

CRDTs (A) buy conflict-free merging we do not need. The only mutable record is `plans`, and the design explicitly accepts LWW on it with a banner. Field-level CRDT merges would actively break the banner UX by producing per-field winners that no single device ever entered.

Custom (C) is the right Phase-2 answer per the v1 architecture spec: the legal pack needs a hash-chained event log, per-tenant DEKs, and a story a SOC 2 auditor can read in under an hour. Six engineer-weeks of bespoke sync code for a solo dev in Phase 0/1, before product validation, is the wrong place to spend the runway. Bug surface in custom sync code directly harms the trust mandate; a battle-tested SDK has fewer ways to silently lose a write.

PowerSync (B) lands the Phase 0/1 trade. Postgres-native means Phase 2's migration to a custom event-sourced backend preserves schema; we are not throwing away the data plane. LWW per row matches the banner UX exactly. The SDK is mobile-first (Swift and Kotlin packages) and writes to SQLite locally, satisfying the <100ms criterion without any code from us. The outbox abstraction is the SDK's; our app code only writes to local SQLite and trusts the SDK to ship.

## Consequences

- Backend for Phase 0/1 is managed Postgres (Supabase or RDS) + PowerSync Cloud. Single region, US-East.
- All client writes go through SQLite. The SDK handles replication; we do not write our own outbox in Phase 0/1.
- `(device_id, client_seq)` is generated client-side and stored in every row; PowerSync's idempotency uses it for de-dupe across retries.
- Conflict banner fires on the client when a `plans` row's `updated_at` advances *and* the locally-known version was not the last writer. We surface the latest, never a chooser.
- Sync rules in PowerSync constrain what the device receives — Phase 0 device gets only the user's own rows; Phase 2's per-firm tenancy is a sync-rule change, not a schema change.
- Vendor lock-in is real but bounded: data is in Postgres, schema is ours, op-log is documented. Migration to custom (Option C) is a worker-pool rewrite, not a data migration.
- **Revisit triggers (Phase 2 entry, ~week 16):** (a) audit defensibility requirement from first legal-pack design partner; (b) per-tenant DEK + WORM bucket required for SOC 2 readiness; (c) sustained write rate >50/sec or sync latency p95 >5s under load. Any one triggers the migration to Option C.

## Action Items

1. [ ] Stand up Supabase Postgres + PowerSync Cloud project; define `capture_items`, `plans`, `closure_events`, `waiting_on` tables.
2. [ ] Define PowerSync sync rules: per-user filter; no cross-user reads.
3. [ ] Wire the iOS GRDB and Android SQLDelight schemas to match Postgres exactly.
4. [ ] Build the spike (see spike doc) and measure convergence on two real devices.
5. [ ] Document the Phase 2 cutover plan now, while Phase 0 is fresh: schema preservation, event-log backfill from PowerSync op-log, dual-write window.
