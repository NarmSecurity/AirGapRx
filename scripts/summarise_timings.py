#!/usr/bin/env python3
"""
Summarise the app's criterion-8 log (files/eval/timings.csv pulled from the phone).

    adb pull /sdcard/Android/data/com.airgaprx.app/files/eval/timings.csv
    python scripts/summarise_timings.py timings.csv

Reports, per device:
  - recognition_ms (shutter -> Review list), camera rows only, cold (run_index 1) and warm separately;
  - check_ms (evaluate + rank);
  - review_ms (user time, reported on its own, never added to system latency);
  - combined processing time: each check is paired with the camera recognition that immediately
    precedes it in the same app run (same `session`); each pair is summed and the median is taken
    over those sums. Medians of the separate intervals are never added together.
  - peak memory, stating whether values are per operation or since process start.
"""
import csv
import statistics
import sys
from collections import defaultdict


def stats(values):
    if not values:
        return "n=0"
    v = sorted(values)
    return f"n={len(v)}  median={statistics.median(v):.0f} ms  max={v[-1]:.0f} ms  min={v[0]:.0f} ms"


def num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def main(path):
    rows = list(csv.DictReader(open(path, newline="", encoding="utf-8")))
    if not rows:
        sys.exit("no rows in " + path)
    by_device = defaultdict(list)
    for r in rows:
        by_device[f"{r['device']} (Android {r['android']}, {r['total_ram_mb']} MB RAM)"].append(r)

    for device, rs in by_device.items():
        print(f"\n== {device}")
        cam = [r for r in rs if r["source"] == "camera" and num(r["recognition_ms"]) is not None]
        print("recognition, cold :", stats([num(r["recognition_ms"]) for r in cam if r["run_index"] == "1"]))
        print("recognition, warm :", stats([num(r["recognition_ms"]) for r in cam if r["run_index"] != "1"]))
        print("recognition, all  :", stats([num(r["recognition_ms"]) for r in cam]))
        print("check             :", stats([num(r["check_ms"]) for r in rs if r["source"] == "check" and num(r["check_ms"]) is not None]))
        print("review (user time):", stats([num(r["review_ms"]) for r in rs if r["source"] == "review" and num(r["review_ms"]) is not None]))

        sessions = defaultdict(list)
        for r in rs:
            sessions[r["session"]].append(r)
        sums = []
        for srs in sessions.values():
            last_recognition = None
            for r in sorted(srs, key=lambda r: int(r["timestamp"])):
                if r["source"] == "camera" and num(r["recognition_ms"]) is not None:
                    last_recognition = num(r["recognition_ms"])
                elif r["source"] == "check" and num(r["check_ms"]) is not None and last_recognition is not None:
                    sums.append(last_recognition + num(r["check_ms"]))
                    last_recognition = None
        print("processing (paired recognition + check, per session):", stats(sums))
        print("  M1 target: median <= 5000 ms. This excludes user review time by design; state that in the report.")

        for scope in ("per_operation", "since_process_start"):
            peaks = [num(r["peak_rss_kb"]) for r in rs if r["peak_scope"] == scope and num(r["peak_rss_kb"]) not in (None, -1)]
            if peaks:
                print(f"peak RSS ({scope}): max={max(peaks) / 1024:.0f} MB  median={statistics.median(peaks) / 1024:.0f} MB  n={len(peaks)}")
        if any(r["peak_scope"] == "since_process_start" for r in rs):
            print("  note: some peaks are since process start (counter reset not permitted); only cold rows are per-operation.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
