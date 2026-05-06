# quiet

Local-first cognitive-relief app. Phase 0/1 ships iOS + Android. This repo
holds the **SPIKE-01 capture-latency spike** plus the shared schema, wire
format, sync rules, and CI that the iOS + Android clients will share.

> **Status:** scaffolded — session 1 of 3. No client code yet. Day 1 of the
> spike (iOS) lands next session.

## Why this repo exists, in one paragraph

Trust is the moderator. The product only delivers cognitive relief when the
user trusts the external store enough to release the loop (Risko & Gilbert
2016; Storm & Stone 2015). Sub-3-second capture is the gating variable: a
slow or unreliable capture restores the loop the system exists to release.
Before any UI breadth is built we measure whether the chosen stack can hit
the latency targets below on real devices. If yes, Phase 0 begins. If no,
the relevant ADR reopens — at week 1, not week 14.

## Latency targets (the only acceptance bar that matters this session-pair)

From [`spikes/SPIKE-01-capture-latency.md`](spikes/SPIKE-01-capture-latency.md):

| Metric        | What it measures                                        | Target            |
|---            |---                                                      |---                |
| `t_keystroke` | First char entered → field fully responsive             | ≤ 800 ms perceived|
| `t_local`     | Return pressed → SQLite txn fsync'd, field cleared      | ≤ 500 ms          |
| `t_e2e`       | Return pressed → server ACK round-trip                  | ≤ 3 s P50, ≤ 6 s P95 |
| `t_converge`  | Local commit on device A → row visible on device B      | ≤ 2 s when both online |

Pass = all four met across all four scenarios. Soft fail = one missed with a
named, scoped fix. Hard fail = platform can't hit it; reopen
[ADR-001](decisions/ADR-001-cross-platform-stack.md) or
[ADR-002](decisions/ADR-002-sync-engine.md).

## Decisions you should read first

- [ADR-001 — Cross-platform stack](decisions/ADR-001-cross-platform-stack.md):
  split native — Swift/SwiftUI on iOS, Kotlin/Compose on Android. Shared SQL
  schema and protobuf wire definitions in this repo.
- [ADR-002 — Sync engine](decisions/ADR-002-sync-engine.md): PowerSync against
  managed Supabase Postgres for Phase 0/1. Whole-record LWW with the conflict
  banner: *"This loop was triaged on another device. Showing the latest
  version."* Revisit at Phase 2 in favor of custom event-sourced sync.

## Repo layout

```
quiet/
├─ schema/                    capture.sql              source of truth, both clients
├─ proto/                     capture.proto            wire format, forward-compat
├─ ios/                       (empty — session 3)      SwiftUI single-screen spike target
├─ android/                   (empty — session 2)      Compose single-screen spike target
├─ supabase/
│  ├─ migrations/             0001_capture_items.sql   Postgres mirror + RLS
│  └─ powersync/              sync_rules.yaml          per-user sync filter
├─ spike/
│  ├─ run-scenarios.md        the SPIKE-01 protocol as a tickable field card
│  └─ analyze.py              JSONL → P50/P95 markdown table (PASS/FAIL)
├─ tests/fixtures/            synthetic.jsonl          200 rows, all 4 scenarios
├─ decisions/                 ADR-001, ADR-002
├─ spikes/                    SPIKE-01-capture-latency.md
├─ .github/workflows/ci.yml   lint + android (no-op) + ios (macos-14 verify)
├─ CLAUDE.md                  locked constraints — every Claude session reads this
├─ TOOLCHAIN.md               Mac-less setup, exact versions
├─ .editorconfig, .gitignore, .env.example, LICENSE, README.md
```

## Mac-less toolchain (one-line summary; details in TOOLCHAIN.md)

VS Code + Swift toolchain on Windows for editing → GitHub Actions `macos-14`
runner for iOS builds → Sideloadly for installing onto an iPhone 14 →
[OSLog](https://developer.apple.com/documentation/os/oslog) +
[`idevicesyslog`](https://libimobiledevice.org/) for measurement. Free Apple
ID for sideloading; no paid developer account until product code lands.

The spike repo is **public** so the GitHub Actions `macos-14` runner is free
(unlimited minutes on public repos). Flip to private when product code lands
and we have paid runners.

See [TOOLCHAIN.md](TOOLCHAIN.md) for exact install steps with version numbers.

## Reproducing the spike from a clean machine

1. `git clone <this repo>`
2. Follow [TOOLCHAIN.md](TOOLCHAIN.md) to install Swift, VS Code, Sideloadly,
   libimobiledevice, and to enroll the free Apple ID.
3. Stand up Supabase + PowerSync per the steps in TOOLCHAIN.md
   ("Set up Supabase project" and "Set up PowerSync Cloud sandbox"). Apply
   `supabase/migrations/0001_capture_items.sql` and paste
   `supabase/powersync/sync_rules.yaml` into the dashboard.
4. Copy `.env.example` → `.env`; fill in real values from 1Password.
5. Wait for sessions 2 and 3 to land the iOS and Android spike targets.
6. Run [`spike/run-scenarios.md`](spike/run-scenarios.md) end-to-end on real
   devices; pull JSONL logs.
7. `python spike/analyze.py --strict tests/run/*.jsonl` — PASS or FAIL.

## CI

Three jobs in `.github/workflows/ci.yml`:

- **lint** (ubuntu-latest): SQLite syntax check on `schema/capture.sql`,
  structural check on the Postgres migration, `protoc` syntax check on
  `proto/capture.proto`, and `analyze.py --strict` against the synthetic
  fixture. This is the green light for committing schema changes.
- **android** (ubuntu-latest): placeholder until session 2 wires
  `./gradlew assembleDebug` against `android/quiet-spike/`.
- **ios** (macos-14): runs `xcodebuild -version` and installs `xcodegen`
  to verify the macOS runner works end-to-end **before** session 3 depends
  on it.

## What's locked, in one place

[CLAUDE.md](CLAUDE.md) holds the locked constraints — design tokens, no
toasts/shadows/celebration, single accent `#1F3A5F`, latency targets,
conflict-UX wording. Every future session reads it first. Anything that
violates a constraint is refused with a constraint-respecting alternative.

## License

[MIT](LICENSE).
