"""Drift report of a column soak (tools/column-soak): reads soak-islands.csv and soak-devices.csv from <dir>.

Usage: python -I tools/column-soak/drift.py <dir> [window_fraction] [pos_substring]

pos_substring keeps only the devices whose position contains it (the review world keeps earlier runs' plants standing).

Per device and quantity, a least-squares line over the last part of the soak (default the second half), its slope per
minute of game time against the bands below, and the scatter about the line (cycling). Per island: statuses seen, held
samples, the commit lag (online - committed ticks) at the start and end of the window, rejected substeps.
"""
import csv
import math
import sys
from collections import defaultdict, Counter

TICKS_PER_MIN = 1200
# quantity: (absolute slope band per minute, relative slope band per minute of the mean |value|, floor of |mean| to judge relative)
BANDS = {
    "t_k": (0.5, None, None),
    "p_pa": (1000.0, 0.01, 1e3),
    "mass_kg": (None, 0.01, 1.0),
    "flow_kg_s": (None, 0.01, 0.01),
    "level_m": (0.01, None, None),
    "heat_out_k": (0.5, None, None),
    "heat_w": (None, 0.01, 1e4),
}
CYCLE = 0.05  # scatter about the line over the mean |value|, for the relative quantities; absolute ones use 2 x the band


def fit(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return 0.0, my, 0.0
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    a = my - b * mx
    res = math.sqrt(sum((y - (a + b * x)) ** 2 for x, y in zip(xs, ys)) / n)
    return b, my, res


def main():
    d = sys.argv[1]
    frac = float(sys.argv[2]) if len(sys.argv) > 2 else 0.5
    only = sys.argv[3] if len(sys.argv) > 3 else ""
    islands = list(csv.DictReader(open(d + "/soak-islands.csv", newline="")))
    devices = list(csv.DictReader(open(d + "/soak-devices.csv", newline="")))
    end = max(int(r["tick"]) for r in islands)
    lo = end * (1 - frac)
    print(f"samples to tick {end} ({end / TICKS_PER_MIN:.1f} min); window from tick {lo:.0f}")

    print("\n== islands")
    by = defaultdict(list)
    for r in islands:
        by[r["island"]].append(r)
    for isl, rows in sorted(by.items(), key=lambda kv: int(kv[0])):
        win = [r for r in rows if int(r["tick"]) >= lo]
        statuses = Counter(r["status"].split(":")[0] for r in rows)
        held = sum(1 for r in rows if r["status"].startswith("HELD"))
        lag0 = int(win[0]["online"]) - int(win[0]["committed"]) if win else 0
        lag1 = int(win[-1]["online"]) - int(win[-1]["committed"]) if win else 0
        rej = sum(max(0, int(r["rejected"])) for r in win)
        flag = " <<" if held or lag1 > max(400, 2 * lag0 + 200) else ""
        last = rows[-1]["status"][:90]
        print(f"island {isl}: statuses {dict(statuses)} held_samples={held} lag {lag0}->{lag1} ticks rejected={rej} last=\"{last}\"{flag}")

    print("\n== devices beyond the bands (slope per min over the window; scatter = rms about the line)")
    series = defaultdict(lambda: defaultdict(list))
    meta = {}
    for r in devices:
        t = int(r["tick"])
        if t < lo or only not in r["pos"]:
            continue
        key = r["device"]
        meta[key] = (r["kind"], r["pos"], r["status"][:70])
        for q in BANDS:
            try:
                v = float(r[q])
            except ValueError:
                continue
            if math.isfinite(v):
                series[key][q].append((t / TICKS_PER_MIN, v))
    flagged = []
    for key, qs in series.items():
        for q, pts in qs.items():
            if len(pts) < 6:
                continue
            xs, ys = zip(*pts)
            b, mean, res = fit(xs, ys)
            ab, rel, floor = BANDS[q]
            why = []
            if ab is not None and abs(b) > ab:
                why.append(f"slope {b:+.4g}/min > {ab}")
            if rel is not None and abs(mean) >= floor and abs(b) > rel * abs(mean):
                why.append(f"slope {100 * b / abs(mean):+.2f} %/min")
            if rel is not None and abs(mean) >= floor and res > CYCLE * abs(mean):
                why.append(f"scatter {100 * res / abs(mean):.1f} %")
            if ab is not None and rel is None and res > 2 * ab:
                why.append(f"scatter {res:.3g}")
            if why:
                flagged.append((key, q, mean, b, res, "; ".join(why)))
    flagged.sort(key=lambda f: (meta[f[0]][0], f[0], f[1]))
    for key, q, mean, b, res, why in flagged:
        kind, pos, status = meta[key]
        print(f"{kind:12s} {key:>8s} {pos:28s} {q:10s} mean={mean:.5g} {why}  [{status}]")
    print(f"\n{len(flagged)} quantity series beyond the bands, of {sum(len(q) for q in series.values())}; {len(series)} devices")


if __name__ == "__main__":
    main()
