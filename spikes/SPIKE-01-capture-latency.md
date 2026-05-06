# Spike 01: Capture Latency

**Status:** Proposed
**Owner:** Jon Townsend (solo)
**Stack:** Per ADR-001 (Swift + Kotlin) and ADR-002 (PowerSync + SQLite)
**Effort:** 5–7 working days
**Pre-requisite for:** any further client work on Phase 0

## Purpose

This spike answers exactly one question, with measurement, before any UI breadth is built:

> **Can the chosen stack hit P50 ≤3.0s, P95 ≤6.0s end-to-end capture latency, and ≤2s sync convergence between two online devices?**

If yes, Phase 0 begins. If no, ADR-001 or ADR-002 reopens. Nothing else in the spike is a goal. No Today, no triage, no nav, no settings, no auth UI. The spike's only acceptance criterion is the measurement.

## Scope

**In:**
- Single-field capture sheet on iOS and Android, identical contract to the prototype's `#capture-input`
- Local commit to SQLite via GRDB (iOS) / SQLDelight (Android)
- Outbox-style queueing handled by PowerSync SDK; durable across kill -9 and OS restart
- Sync against PowerSync Cloud + a Supabase Postgres backend
- Two-device convergence test (one iOS, one Android logged in as the same user)
- Latency instrumentation that emits structured measurements to a local log file

**Out:**
- Triage UI, Today, Forecast, Waiting-On, Closed
- Plan field, verb check, dispositions
- Voice, email forward, browser plugin, system hotkey, file drag, post-call
- Auth UI (use a hardcoded test user)
- Push notifications
- Anything visual beyond the capture sheet itself

## What "capture" means in the spike

Identical surface contract to the production design system, no more:

- One text field, placeholder `"What just landed."`
- Return key submits
- Escape (or back) dismisses without losing the draft
- Field clears on commit — that is the receipt
- No toast, no animation, no spinner
- Single accent token `--accent-ink: #1F3A5F`; no other colors
- Offline marker is a single hairline indicator

This is the minimum surface that lets us measure the production hot path without tilting the result.

## Latency definitions (measure these exactly)

We measure what the user *perceives*, not what the network does.

| Metric | Start | End | Target |
|---|---|---|---|
| `t_keystroke` | First character entered | Field is fully responsive (next keystroke renders) | ≤0.8s perceived |
| `t_local` | Return key pressed | Local SQLite txn fsync'd; field cleared | ≤0.5s |
| `t_e2e` | Return key pressed | Server-side ACK round-trip received | ≤3.0s P50, ≤6.0s P95 |
| `t_converge` | Local commit on device A | Row visible on device B (poll-driven test) | ≤2.0s when both online |

Sub-metrics tracked for diagnosis only: time to acquire share-extension app group SQLite handle (cold start), JSON encoding cost per event, gzip ratio on wire, retry backoff hits.

## Measurement protocol

1. Test corpus: 200 captures per device, four scenarios:
   - **A: Online, foreground.** App is open, network is good. Baseline.
   - **B: Online, cold start.** Force-quit between each capture. The trust-bearing path on first reach for the phone.
   - **C: Offline → online.** 50 captures with airplane mode on, then back online; measure offline `t_local` and online `t_converge` after reconnection.
   - **D: Two-device convergence.** Device A captures while device B is foregrounded; record `t_converge` to first byte rendered on B.
2. Test text: a fixed 8-word string ("the Patel matter — read the deposition before Thursday"). No paste. Each capture is typed. This isolates capture cost from typing speed by holding it constant.
3. Realistic device: iPhone 13 (the iOS floor we plan to support) and Pixel 6a. Real LTE, not Wi-Fi, for online tests.
4. Realistic load: phone runs nothing else but a music app (background audio) and a mail client (network-active). This approximates the user the architecture spec describes.
5. Logging: each measurement is a JSON line in `/tmp/quiet-spike-latency.log` with `device_id`, `scenario`, `metric`, `value_ms`, `client_seq`, `wall_clock`. Pull both devices' logs, merge, percentile.

## Build steps

- **Day 1.** iOS spike target: SwiftUI app, single screen, `TextField` bound to a model. GRDB schema with one table: `capture_items(id TEXT PK, raw_text TEXT, captured_at REAL, device_id TEXT, client_seq INTEGER)`. Wire PowerSync iOS SDK against a Supabase project. No share extension yet.
- **Day 2.** Android spike target: Compose, single screen, same field contract. SQLDelight with the same schema (literally the same `.sq` file). PowerSync Kotlin SDK against the same Supabase project.
- **Day 3.** Latency instrumentation: `CFAbsoluteTimeGetCurrent()` on iOS, `System.nanoTime()` on Android. Bracket every measurement explicitly; the SDK has no opinion about what we call "perceived latency" so we own the brackets. Emit JSON lines.
- **Day 4.** Run scenarios A and B on each device. 200 captures × 2 devices × 2 scenarios = 800 measurements. Compute P50/P95.
- **Day 5.** Run scenarios C and D. Confirm offline durability across `kill -9` and a power cycle.
- **Day 6.** Write up findings against the four targets. If green: ship. If red: name the bottleneck (bridge cost? SQLite fsync? SDK overhead? cold-start JIT on Compose?), name the next experiment.
- **Day 7 (buffer).** Reserved for the bottleneck investigation that will inevitably come out of day 6.

## Acceptance — pass / fail

**Pass:** all four targets met across all four scenarios at the stated percentiles. No exceptions.

**Soft fail:** any one target missed, but the bottleneck has a named, scoped fix (e.g., "GRDB write is hot — switch to WAL mode" or "PowerSync sync rules are over-broad on first connect"). Spike repeats, costs ≤3 days.

**Hard fail:** the platform itself can't hit the budget. Reopens ADR-001 or ADR-002. This is what the spike exists to find out *now* rather than at week 14.

## What the spike deliberately does *not* prove

- That voice, share extension, post-call, or email-forward surfaces hit the budget. Each surface gets its own micro-spike (≤1 day) once the in-app sheet is green.
- That the legal pack's audit log fits inside the latency budget. Phase 2 concern.
- That the conflict banner UX feels right. That's a usability test on the prototype, not a latency spike.
- That the user-authentication round-trip fits inside `t_e2e`. We hardcode a test user; real auth gets its own spike.

## Constraints preserved during the spike

- Design tokens are non-negotiable. The spike imports `--bg-canvas: #FAFAF7`, `--accent-ink: #1F3A5F`, `--text-primary: #1A1A1A`, hairline `#E5E3DD`. No other colors.
- No celebration animation, toast, sound, or shadow on the capture surface.
- No "Captured!" feedback. The empty field is the receipt. Anything else biases the perceived-latency measurement and trains the wrong reflex.
- Single accent — `--accent-ink` only, used on the input's focus underline and nowhere else.
- Reduce-motion preference respected. `motion/calm` collapses to `motion/instant`.

## Repo layout (proposed)

```
quiet/
├─ schema/
│  └─ capture.sql                 # source of truth, consumed by both
├─ proto/
│  └─ capture.proto               # client_seq, captured_at, raw_text
├─ ios/
│  └─ QuietSpike/                 # SwiftUI single-screen target
├─ android/
│  └─ quiet-spike/                # Compose single-screen target
├─ supabase/
│  └─ migrations/                 # capture_items table only
├─ spike/
│  ├─ run-scenarios.md            # the protocol above as a checklist
│  └─ analyze.py                  # P50/P95 from merged JSON lines
└─ decisions/
   ├─ ADR-001-cross-platform-stack.md
   └─ ADR-002-sync-engine.md
```

## After the spike

Green spike → start Phase 0 iOS build per the v1 architecture phasing (capture + triage + Today + Inbox), targeting end-to-end median capture latency under 700ms on iOS over LTE and zero capture loss across 10,000 simulated `kill -9` events (the v1 exit criterion).

Red spike → reopen the relevant ADR before any further client work. The whole point of running this first is that no week-14 surprise can invalidate the trust mandate.
