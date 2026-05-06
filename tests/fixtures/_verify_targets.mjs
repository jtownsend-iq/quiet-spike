// tests/fixtures/_verify_targets.mjs
// Sanity check that synthetic.jsonl meets every SPIKE-01 target.
// Mirrors the percentile + target rules in spike/analyze.py so the local
// Node check and the CI Python check stay in lock-step.
//
// Usage:  node tests/fixtures/_verify_targets.mjs
// Exits 0 on PASS, 1 on FAIL.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const fx = join(__dirname, "synthetic.jsonl");

const TARGETS = [
  { metric: "t_keystroke", p: 50, ms: 800 },
  { metric: "t_local",     p: 50, ms: 500 },
  { metric: "t_e2e",       p: 50, ms: 3000 },
  { metric: "t_e2e",       p: 95, ms: 6000 },
  { metric: "t_converge",  p: 50, ms: 2000 },
];
const SCENARIOS = ["A", "B", "C", "D"];

function pct(values, p) {
  if (!values.length) return NaN;
  const s = [...values].sort((a, b) => a - b);
  if (s.length === 1) return s[0];
  const k = (s.length - 1) * (p / 100);
  const lo = Math.floor(k);
  const hi = Math.ceil(k);
  if (lo === hi) return s[k];
  return s[lo] + (s[hi] - s[lo]) * (k - lo);
}

const rows = readFileSync(fx, "utf8")
  .split("\n")
  .filter(Boolean)
  .map((l) => JSON.parse(l));

const buckets = new Map(); // key: metric|scenario -> [values]
for (const r of rows) {
  const k = `${r.metric}|${r.scenario}`;
  if (!buckets.has(k)) buckets.set(k, []);
  buckets.get(k).push(r.value_ms);
}

let ok = true;
const lines = [];
for (const t of TARGETS) {
  for (const sc of SCENARIOS) {
    const vs = buckets.get(`${t.metric}|${sc}`);
    if (!vs || !vs.length) continue;
    const v = pct(vs, t.p);
    const pass = v <= t.ms;
    if (!pass) ok = false;
    lines.push(
      `${pass ? "PASS" : "FAIL"}  ${t.metric.padEnd(12)} sc=${sc}  P${t.p}=${v.toFixed(1).padStart(7)}ms  target ≤${t.ms}ms  (n=${vs.length})`
    );
  }
}

console.log(`fixture: ${rows.length} rows`);
console.log(lines.join("\n"));
console.log(ok ? "\nVERDICT: PASS" : "\nVERDICT: FAIL");
process.exit(ok ? 0 : 1);
