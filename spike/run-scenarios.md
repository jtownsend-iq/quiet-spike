# SPIKE-01 — Run Scenarios Checklist

Tick each box during the measurement run. The full protocol lives in
[`../spikes/SPIKE-01-capture-latency.md`](../spikes/SPIKE-01-capture-latency.md);
this file is the field card.

> **Pass bar:** all four targets met across all four scenarios at the stated
> percentiles. Anything else means a soft- or hard-fail per SPIKE-01 § Acceptance.

## Targets (recap)

| Metric        | P50      | P95      |
|---            |---       |---       |
| `t_keystroke` | ≤ 800 ms | —        |
| `t_local`     | ≤ 500 ms | —        |
| `t_e2e`       | ≤ 3000 ms| ≤ 6000 ms|
| `t_converge`  | ≤ 2000 ms| —        |

## Setup (do once, before scenario A)

- [ ] iPhone 13 sideloaded with QuietSpike build, charge ≥ 80%
- [ ] Pixel 6a sideloaded with quiet-spike build, charge ≥ 80%
- [ ] Both devices on real LTE (Wi-Fi disabled)
- [ ] Music app playing in background; mail client open in background
- [ ] Both devices logged in as the same hardcoded test user
- [ ] PowerSync Cloud sandbox reachable from both devices (open the app once
      and confirm the offline indicator clears)
- [ ] `idevicesyslog` running against iPhone, piping to `ios.log`
- [ ] `adb logcat -s QuietSpike` running against Pixel, piping to `android.log`
- [ ] Test phrase loaded in clipboard for reference only:
      `the Patel matter — read the deposition before Thursday`
      (each capture is **typed**, not pasted — keeps capture cost separated
      from typing speed by holding it constant)

## Scenario A — Online, foreground (baseline)

- [ ] iPhone: 200 captures, app foregrounded the whole time
- [ ] Pixel: 200 captures, app foregrounded the whole time
- [ ] Pull both logs: `tests/run/ios-A.jsonl`, `tests/run/android-A.jsonl`

## Scenario B — Online, cold start

- [ ] iPhone: 200 captures, **force-quit between each** (swipe up + flick away)
- [ ] Pixel: 200 captures, **force-quit between each** (recents → swipe away)
- [ ] Pull logs: `tests/run/ios-B.jsonl`, `tests/run/android-B.jsonl`

## Scenario C — Offline → online

- [ ] Airplane mode ON, both devices
- [ ] iPhone: 50 offline captures (verify hairline indicator shows)
- [ ] Pixel: 50 offline captures (verify hairline indicator shows)
- [ ] Airplane mode OFF, both devices
- [ ] Wait for outbox to drain on each device (open app, watch indicator clear)
- [ ] Pull logs: `tests/run/ios-C.jsonl`, `tests/run/android-C.jsonl`
- [ ] Confirm zero capture loss: row counts in Supabase match local counts

## Scenario D — Two-device convergence

- [ ] Both devices online and foregrounded side-by-side
- [ ] Device A captures; record wall-clock; Device B logs `t_converge` on
      first byte rendered. Repeat ×100 with A=iPhone, then ×100 with A=Pixel.
- [ ] Pull logs: `tests/run/converge.jsonl`

## Durability cross-check (one-shot, scenario B follow-up)

- [ ] Capture 50 items rapidly on iPhone; force-quit mid-burst
      (`launchctl kickstart -k system/...` not needed — the OS-level swipe is
      the user-visible truth). Confirm all 50 land in Supabase after restart.
- [ ] Same on Pixel (Recents → swipe away during burst).
- [ ] Note the smallest `(captured_at - server_received_at)` gap; flag any
      `> 10 s` for investigation in day 7.

## Analysis

- [ ] Merge: `cat tests/run/*.jsonl > tests/run/all.jsonl`
- [ ] Run: `python spike/analyze.py --strict tests/run/all.jsonl`
- [ ] Paste the markdown table into the spike findings doc
- [ ] If green: write the "Phase 0 unblocked" entry, kick off iOS scaffold
- [ ] If soft fail: name the bottleneck, scope the fix, schedule rerun ≤ 3 days
- [ ] If hard fail: reopen ADR-001 or ADR-002 *before* any further client work

## Observability sanity (do at the end, not during)

- [ ] Outbox depth on both devices = 0 after each scenario
- [ ] No capture rows in SQLite that lack a Supabase counterpart
      (`select count(*) from capture_items` on the device vs. the server,
      filtered by `device_id`)
- [ ] No duplicate `(device_id, client_seq)` pairs in Supabase
