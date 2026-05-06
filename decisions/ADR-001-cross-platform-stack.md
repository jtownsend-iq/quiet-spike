# ADR-001: Cross-Platform Stack for Phase 0/1 (iOS + Android)

**Status:** Proposed
**Date:** 2026-05-06
**Deciders:** Jon Townsend (solo)

## Context

Phase 0/1 ships iOS + Android. The product's gating variable is sub-3s P50 capture latency (Risko & Gilbert 2016; Gilbert 2015). Trust is the moderator: a single missed or slow capture restores the loop the system exists to release. The capture sheet keystroke target is ≤0.8s perceived; the design system forbids elements that add load.

Capture surfaces in scope for Phase 0/1: voice (hold-to-talk + system speech), share intent / share extension, post-call calendar prompt, in-app sheet. The system hotkey (Ctrl/Cmd+Shift+.) is a desktop concern revisited in a later ADR but is criterion #2 because the chosen stack must not foreclose it. Solo developer, Windows host, four-month runway to a usable Phase 0 build.

Prior art: the v1 architecture spec recommends native on every platform and trades 5x dev cost against the latency budget (§13). This ADR re-tests that decision against three options under the explicit Phase 0/1 scope.

## Decision

**Option B — Split native (Swift + SwiftUI on iOS; Kotlin + Compose on Android)**, with shared SQL schema and shared protobuf wire definitions in a single repo.

## Options Considered

| Dimension | A. RN + Expo | B. Split native | C. Tauri + web |
|---|---|---|---|
| P50 ≤3s / P95 ≤6s capture | Achievable with care; JSI/Fabric closes the historical gap, but capture extensions on iOS (share, Siri, App Intents) must be Swift regardless | Native ceiling; no bridge in the hot path | Not applicable to mobile in Phase 0/1 |
| Hotkey (later) | Desktop is a separate Tauri/native build either way | Same | Native to Tauri; mobile is the gap |
| Voice / share / post-call | Voice via expo-speech-recognition; share extension still native; App Intents bleeding-edge in Expo | First-class on both OSes | None of these on mobile |
| Solo-dev velocity to Phase 0 | ~30–40% time saving on triage/forecast/waiting screens; saving partially eaten by native capture-extension work | Slowest in raw weeks; fewest moving parts; one debugger, one profiler per platform | Fast for desktop; doesn't deliver the Phase 0 product |
| Offline-first | op-sqlite + Drizzle is mature | GRDB / SQLDelight, both proven | N/A |

**Pros / cons summary**

A. RN+Expo: single codebase for the non-capture surfaces, but the capture surfaces — the trust-bearing path — are Swift/Kotlin no matter what. Hybrid surface area is the worst of both stacks: two debuggers, JS↔native data hand-offs at the edges, version skew between the RN store and the share-extension store via App Group / Content Provider.

B. Split native: highest latency ceiling, no bridge in the hot path, share extensions and intents are first-class. Cost is roughly 1.7x the engineering hours of A for screens that do not move the trust needle. Mitigated by keeping non-capture screens minimal — the design system itself is a subtraction discipline, so the parity surface is small.

C. Tauri + web for desktop, native mobile later: defers Phase 0/1. Out of scope.

## Trade-off Analysis

The decision turns on criterion #1, which has veto weight because capture latency is the trust mechanism. RN+Expo can hit the latency budget on the in-app sheet, but the capture surfaces that have to hit it offline, on cold start, from a share extension or Siri intent, are native processes that do not run JS at all. So the headline saving from a single codebase is not on the latency-bearing path.

The realistic comparison is therefore: (B) one native codebase per platform, vs. (A) one RN codebase plus one native share-extension/intents codebase per platform — i.e., three codebases vs. two. A's velocity advantage is real on the triage/forecast/waiting/closed surfaces but those are read-mostly screens where 200 ms of bridge cost is invisible. They are not where solo-dev velocity is bottlenecked.

Criterion #4 (solo-dev velocity) is the closest thing to a tiebreaker for A, but the design system's subtraction discipline keeps total screen count small (five surfaces, hard cap). The absolute hours saved by RN are smaller than they look because there is little screen breadth to amortize across.

## Consequences

- iOS first (weeks 0–8), Android second (weeks 8–16), per the v1 architecture phasing.
- Shared schema lives in a single repo: `schema/*.sql` is the source of truth; SQLDelight on Android consumes it directly, GRDB on iOS via a small build step.
- Shared wire format: protobuf definitions in `proto/` consumed by both clients and the future Go sync service.
- Capture sheet on each platform owns its own latency budget; no cross-platform abstraction sits in the hot path.
- Watch glance, Quick Tile, AirPods press are unblocked when we want them.
- We will revisit at the **6-month mark** if (a) the Today/Forecast/Waiting/Closed screens are evidently the dev-cost bottleneck, or (b) a credible cross-platform abstraction (e.g., Kotlin Multiplatform Mobile for shared business logic only, never UI) emerges from prototype work.

## Action Items

1. [ ] Create `quiet-ios` and `quiet-android` repos; pin Xcode 16 / Android Studio Ladybug.
2. [ ] Land `schema/capture.sql` as the source of truth; verify GRDB and SQLDelight both compile against it.
3. [ ] Spec the protobuf `CaptureItem` and `SyncEvent` messages once, used by both clients.
4. [ ] Build the latency spike (see spike doc) before any other client work.
