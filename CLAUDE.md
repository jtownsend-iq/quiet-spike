# CLAUDE.md — Quiet repo, locked constraints

This file is the first thing every future Claude session reads in this repo.
The constraints below are **locked**. They are not opinions, not v1 defaults,
not "we'll see how it feels." They are the cure. Anything that violates them
subtracts the trust the product is built to earn.

If a future session is asked to add something that would violate a constraint,
the answer is: name the constraint, refuse the change, and propose a
constraint-respecting alternative. The change-control rule from the design
system applies — additions require (1) a study, (2) a mapping to one of the
four mechanisms, (3) something removed in exchange.

## Product mandate (one sentence)

**Trust is the moderator. Sub-3s capture is the gating variable. Every
architectural choice is graded against whether it preserves trust under
observed failure.** Source: `Quiet_System_Architecture_v1.txt` § 1; design
system § Core Principle 4; ADR-002 § Context.

## Latency targets (NEVER soften these)

From `spikes/SPIKE-01-capture-latency.md` § Latency definitions:

| Metric        | Definition                                              | Target            |
|---            |---                                                      |---                |
| `t_keystroke` | First char entered → field fully responsive             | ≤ 800 ms perceived|
| `t_local`     | Return pressed → SQLite txn fsync'd, field cleared      | ≤ 500 ms          |
| `t_e2e`       | Return pressed → server ACK round-trip                  | ≤ 3 s P50, ≤ 6 s P95 |
| `t_converge`  | Local commit on device A → row visible on device B      | ≤ 2 s when both online |

Soft fail: any one missed but the bottleneck is named and scoped to a ≤3-day
fix. Hard fail: the platform itself can't hit it — reopen ADR-001 or ADR-002
before any further client work.

## Design tokens (non-negotiable)

From `Quiet_Design_System_v1.txt` § Design Tokens.

```
--bg-canvas:       #FAFAF7   /* warm off-white, lower glare than #FFFFFF */
--bg-surface:      #FFFFFF
--bg-inset:        #F2F1EC   /* read-only / quiet zones (Forecast, archive) */
--text-primary:    #1A1A1A
--text-secondary:  #4A4A4A
--text-tertiary:   #7A7A7A
--accent-ink:      #1F3A5F   /* SINGLE accent. No competing accent, ever. */
--state-success:   #2E5C3A   /* loop-closed moment only */
--state-wait:      #8C6A1F   /* Waiting-On marker only */
--state-danger:    #7A2222   /* destructive confirm only */
--border-hairline: #E5E3DD   /* the ONLY border weight in the system */
```

Type: Söhne or Inter, 2 weights (400, 600), 5 sizes (32 / 20 / 16 / 13 / 11).
Spacing: 4 / 8 / 16 / 24 / 40 / 80 (base 8, four-step scale).
Radii: 0 or 8 px. Borders: 1 px hairline only.

## Forbidden by name

These have been excluded by research. Future sessions: do not add them under
any framing — "just a small celebration", "a tasteful shadow", "one extra
accent" — they are excluded.

| Excluded                                          | Why (cite)                                         |
|---                                                |---                                                 |
| Toasts after capture                              | Empty field is the receipt. DS § Capture Sheet.    |
| Celebration animations / confetti / streaks      | Stothart 2015 (orienting response). DS § Anti-Patterns. |
| Drop shadows on cards / surfaces                  | Decoration cost without info gain. Sweller 1988.   |
| Multiple accent colors                            | DS § Color: single accent rule.                    |
| Gradients, neon, gamified palettes                | Same.                                              |
| Push notifications outside Friday review prompt   | Stothart 2015. DS Principle 7.                     |
| Tags, labels, custom fields, project hierarchies | Sweller 1988; Cowan 2001. DS § Anti-Patterns.      |
| AI summarization of the action list               | Restores eval overhead. DS § Anti-Patterns.        |
| Auto-scheduled tasks on the calendar              | Risko & Gilbert 2016 (broken trust permanent).     |
| Soft-warn at 5-item Today cap                     | Hard cap is structural, not advisory. DS § Today.  |
| User-configurable workflow                        | Configuration is the load. DS § Anti-Patterns.     |
| Sound / audio cues                                | The app is silent. DS § Sound.                     |

## Conflict UX (verbatim)

When sync resolves a non-trivial conflict on a `plans` row, fire **one banner**:

> **This loop was triaged on another device. Showing the latest version.**

Whole-record last-writer-wins, never a chooser, never a merge UI, never a
field-level winner. Source: `decisions/ADR-002-sync-engine.md` § Context, §
Trade-off Analysis. The user's most recent input wins; the prior version
remains in the event log if recovery is ever needed.

## Capture surface contract (verbatim, the trust-bearing path)

From `Quiet_Design_System_v1.txt` § Capture Sheet and SPIKE-01 § What "capture"
means in the spike:

- One text field. Placeholder: `"What just landed."`
- No category picker, no project assignment, no tag chooser, no priority slider.
- Return = send. Escape = dismiss without losing the draft.
- Field clears on commit — that is the receipt. **No** toast, animation, sound,
  spinner, or "Captured!" feedback.
- Single accent `--accent-ink` on the focus underline; nowhere else.
- Offline marker: a single hairline indicator. The user is told once, never
  repeatedly.
- Reduce-motion preference respected. `motion/calm` (240 ms) collapses to
  `motion/instant` (0 ms).
- Tap target ≥ 56 × 56 pt for capture surfaces.
- Body text contrast ≥ 7:1 against canvas. WCAG 2.2 AA minimum across the app.

## Phase 0/1 stack (locked by ADRs)

- **iOS:** Swift + SwiftUI + GRDB. Native share extension, App Intents.
- **Android:** Kotlin + Jetpack Compose + SQLDelight. Share intent, Quick Tile.
- **Sync (Phase 0/1 only):** PowerSync Cloud against managed Supabase Postgres,
  single region (US-East). Per-user sync rule + Postgres RLS.
- **Schema source of truth:** `schema/capture.sql`. Postgres mirror at
  `supabase/migrations/0001_capture_items.sql`. Both consumed by both clients
  and the future Go sync service.
- **Wire format:** `proto/capture.proto`. Forward-compat via reserved field
  numbers; never re-allocate a number.
- **Idempotency key everywhere:** `(device_id, client_seq)`. PowerSync uses it
  to de-dupe across retries; the spike checks for duplicates after every run.

Phase 2 (legal pack, weeks 16–24) revisits ADR-002 in favor of custom
event-sourced sync on Postgres + outbox. Schema preservation is the migration
contract; the data plane is preserved across the cutover.

## What this session does NOT do

- No Swift, Kotlin, or UI of any kind.
- No iOS scaffolding (session 3).
- No Android scaffolding (session 2).
- No auth UI; spike uses a hardcoded test user.
- No "while I'm here" extras. Every minute of product surface delays the
  gating measurement.

## Reading order for new contributors / future Claude sessions

1. `README.md` — what this repo is and how to reproduce the setup
2. `decisions/ADR-001-cross-platform-stack.md` — split-native decision
3. `decisions/ADR-002-sync-engine.md` — PowerSync for Phase 0/1
4. `spikes/SPIKE-01-capture-latency.md` — the gating measurement
5. `spike/run-scenarios.md` — the field card you tick during the run
6. `TOOLCHAIN.md` — exact Mac-less setup steps with version numbers
