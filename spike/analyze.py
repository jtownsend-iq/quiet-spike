#!/usr/bin/env python3
"""
spike/analyze.py — collapse SPIKE-01 latency JSONL into a markdown table.

Input: one or more JSONL files. Each line is a single measurement:
    {"device_id": "...", "scenario": "A|B|C|D",
     "metric":   "t_keystroke|t_local|t_e2e|t_converge",
     "value_ms": <number>, "client_seq": <int>, "wall_clock": <iso8601>}

Output (stdout): a markdown table with P50 / P95 per (metric, scenario, device)
plus a summary row per (metric, scenario) across devices, and a final
PASS/FAIL line evaluated against the SPIKE-01 acceptance targets.

Targets (from SPIKE-01):
    t_keystroke   P50 <= 800   ms
    t_local       P50 <= 500   ms
    t_e2e         P50 <= 3000  ms,  P95 <= 6000 ms
    t_converge    P50 <= 2000  ms

Pure stdlib. Works on Python 3.9+.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Iterable


SCENARIOS = ("A", "B", "C", "D")
METRICS = ("t_keystroke", "t_local", "t_e2e", "t_converge")

# (metric, percentile) -> ms ceiling. None => no target at that percentile.
TARGETS_MS = {
    ("t_keystroke", "p50"): 800,
    ("t_local",     "p50"): 500,
    ("t_e2e",       "p50"): 3000,
    ("t_e2e",       "p95"): 6000,
    ("t_converge",  "p50"): 2000,
}


def percentile(values: list[float], p: float) -> float:
    """Linear-interpolated percentile. p in [0, 100]. Empty -> NaN."""
    if not values:
        return float("nan")
    s = sorted(values)
    if len(s) == 1:
        return s[0]
    k = (len(s) - 1) * (p / 100.0)
    lo = math.floor(k)
    hi = math.ceil(k)
    if lo == hi:
        return s[int(k)]
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def load(paths: Iterable[Path]) -> list[dict]:
    rows: list[dict] = []
    for p in paths:
        with p.open("r", encoding="utf-8") as f:
            for ln, raw in enumerate(f, 1):
                line = raw.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError as e:
                    print(f"WARN {p}:{ln}: bad JSON ({e})", file=sys.stderr)
                    continue
                rows.append(obj)
    return rows


def fmt_ms(v: float) -> str:
    if math.isnan(v):
        return "—"
    return f"{v:,.0f}"


def render(rows: list[dict]) -> tuple[str, bool]:
    """Return (markdown, all_targets_met)."""
    # buckets[(metric, scenario, device_id)] -> [value_ms, ...]
    buckets: dict[tuple[str, str, str], list[float]] = defaultdict(list)
    # combined[(metric, scenario)] -> [value_ms, ...]   (across devices)
    combined: dict[tuple[str, str], list[float]] = defaultdict(list)

    for r in rows:
        try:
            metric = r["metric"]
            scenario = r["scenario"]
            device = r["device_id"]
            v = float(r["value_ms"])
        except (KeyError, TypeError, ValueError):
            continue
        if metric not in METRICS or scenario not in SCENARIOS:
            continue
        buckets[(metric, scenario, device)].append(v)
        combined[(metric, scenario)].append(v)

    devices = sorted({d for (_, _, d) in buckets.keys()})

    lines: list[str] = []
    lines.append("# SPIKE-01 latency report")
    lines.append("")
    lines.append(
        f"Devices: {', '.join(devices) if devices else '(none)'}  ·  "
        f"Total measurements: {len(rows)}"
    )
    lines.append("")

    # Per-metric tables
    all_pass = True
    for metric in METRICS:
        lines.append(f"## {metric}")
        lines.append("")
        header = "| scenario | device | n | P50 (ms) | P95 (ms) | target |"
        sep    = "|---|---|---:|---:|---:|---|"
        lines.append(header)
        lines.append(sep)
        for scenario in SCENARIOS:
            for device in devices:
                vs = buckets.get((metric, scenario, device), [])
                p50 = percentile(vs, 50)
                p95 = percentile(vs, 95)
                tgt_p50 = TARGETS_MS.get((metric, "p50"))
                tgt_p95 = TARGETS_MS.get((metric, "p95"))
                tgt_parts = []
                if tgt_p50 is not None:
                    tgt_parts.append(f"P50 ≤ {tgt_p50}")
                if tgt_p95 is not None:
                    tgt_parts.append(f"P95 ≤ {tgt_p95}")
                tgt_str = "; ".join(tgt_parts) if tgt_parts else "—"
                lines.append(
                    f"| {scenario} | {device} | {len(vs)} | "
                    f"{fmt_ms(p50)} | {fmt_ms(p95)} | {tgt_str} |"
                )
            # combined row across devices
            cs = combined.get((metric, scenario), [])
            p50 = percentile(cs, 50)
            p95 = percentile(cs, 95)
            tgt_p50 = TARGETS_MS.get((metric, "p50"))
            tgt_p95 = TARGETS_MS.get((metric, "p95"))
            ok = True
            if tgt_p50 is not None and not math.isnan(p50) and p50 > tgt_p50:
                ok = False
            if tgt_p95 is not None and not math.isnan(p95) and p95 > tgt_p95:
                ok = False
            if cs and not ok:
                all_pass = False
            badge = "✅" if ok else "❌"
            lines.append(
                f"| **{scenario}** | **all** | **{len(cs)}** | "
                f"**{fmt_ms(p50)}** | **{fmt_ms(p95)}** | **{badge}** |"
            )
        lines.append("")

    verdict = "PASS — all latency targets met across all scenarios." if all_pass \
              else "FAIL — at least one scenario missed its target. See ❌ rows."
    if not rows:
        verdict = "NO DATA — supply a JSONL log to evaluate."
        all_pass = False
    lines.append(f"**Verdict:** {verdict}")
    lines.append("")
    return "\n".join(lines), all_pass


def main(argv: list[str]) -> int:
    # Windows consoles default to cp1252 and will UnicodeEncodeError on the
    # ≤ / ✅ / ❌ glyphs in the report. Reconfigure to UTF-8 unconditionally;
    # POSIX is already UTF-8 so this is a no-op there.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("paths", nargs="+", type=Path, help="one or more JSONL log files")
    ap.add_argument(
        "--strict", action="store_true",
        help="exit non-zero if any target is missed (use this in CI)",
    )
    args = ap.parse_args(argv)

    for p in args.paths:
        if not p.exists():
            print(f"ERROR: {p} not found", file=sys.stderr)
            return 2

    rows = load(args.paths)
    md, all_pass = render(rows)
    print(md)
    if args.strict and not all_pass:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
