// tests/fixtures/_generate_synthetic.mjs
// Deterministic generator for synthetic.jsonl. Re-run with:
//     node tests/fixtures/_generate_synthetic.mjs
// Produces 200 measurement rows across the four SPIKE-01 scenarios and two
// devices, with values chosen so analyze.py --strict exits 0 on the fixture.
//
// Pure Node, no deps. Uses a seeded mulberry32 PRNG for reproducibility.

import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));

function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const rand = mulberry32(20260506);

// Triangular distribution centered at `c` with half-spread `s`. Bounded tails.
function jitter(c, s) {
  const u = rand();
  // Inverse CDF of symmetric triangular on [c-s, c+s]
  const v =
    u < 0.5
      ? c - s + Math.sqrt(u * 2 * s * s)
      : c + s - Math.sqrt((1 - u) * 2 * s * s);
  return Math.max(1, v);
}

const DEVICES = ["ios-iphone13", "android-pixel6a"];
const start = new Date(Date.UTC(2026, 4, 6, 14, 0, 0)); // 2026-05-06 14:00Z

const rows = [];
let seq = 0;
function emit(device, scenario, metric, value_ms) {
  rows.push({
    device_id: device,
    scenario,
    metric,
    value_ms: Math.round(value_ms * 10) / 10,
    client_seq: seq,
    wall_clock: new Date(start.getTime() + seq * 1000).toISOString(),
  });
}

// Row counts per scenario chosen to total exactly 200:
//   A: 10*2*2 (keystroke+local) + 5*2 (e2e) + 5*2 (converge)        = 60
//   B: 10*2*2 (keystroke+local) + 5*2 (e2e)                          = 50
//   C: 10*2 (local) + 10*2 (converge)                                = 40
//   D: 25*2 (converge)                                               = 50
//   ---------------------------------------------------------------------
//   total                                                            = 200

// Scenario A — online, foreground. All four metrics, well under target.
for (const d of DEVICES) {
  for (let i = 0; i < 10; i++) {
    seq++;
    emit(d, "A", "t_keystroke", jitter(180, 80));
    emit(d, "A", "t_local", jitter(60, 30));
  }
  for (let i = 0; i < 5; i++) {
    seq++;
    emit(d, "A", "t_e2e", jitter(900, 400));
  }
  for (let i = 0; i < 5; i++) {
    seq++;
    emit(d, "A", "t_converge", jitter(1100, 500));
  }
}

// Scenario B — online, cold start. Higher t_keystroke + t_local.
for (const d of DEVICES) {
  for (let i = 0; i < 10; i++) {
    seq++;
    emit(d, "B", "t_keystroke", jitter(450, 150));
    emit(d, "B", "t_local", jitter(220, 90));
  }
  for (let i = 0; i < 5; i++) {
    seq++;
    emit(d, "B", "t_e2e", jitter(2100, 600));
  }
}

// Scenario C — offline -> online. Offline t_local, then t_converge.
for (const d of DEVICES) {
  for (let i = 0; i < 10; i++) {
    seq++;
    emit(d, "C", "t_local", jitter(70, 25));
  }
  for (let i = 0; i < 10; i++) {
    seq++;
    emit(d, "C", "t_converge", jitter(1500, 400));
  }
}

// Scenario D — two-device convergence.
for (const d of DEVICES) {
  for (let i = 0; i < 25; i++) {
    seq++;
    emit(d, "D", "t_converge", jitter(1300, 350));
  }
}

const out = join(__dirname, "synthetic.jsonl");
writeFileSync(out, rows.map((r) => JSON.stringify(r)).join("\n") + "\n", "utf8");
console.log(`wrote ${rows.length} rows -> ${out}`);
