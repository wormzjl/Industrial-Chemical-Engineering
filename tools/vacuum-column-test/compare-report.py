"""Report matched raw production acceptance; never relabel timeouts as infeasibility."""
import argparse
import collections
import csv
import json
from pathlib import Path


def read(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines()]


def counts(rows):
    c = collections.Counter(r["classification"] for r in rows)
    return {"cases": len(rows), "accepted": c["ACCEPTED"], "numerical": sum(v for k, v in c.items()
            if k not in {"ACCEPTED", "TIME_BUDGET", "INVALID_INPUT", "INFEASIBLE_SPECIFICATION", "INTERNAL_ERROR", "HARNESS_EXCEPTION"}),
            "budget": c["TIME_BUDGET"], "rejected": c["INVALID_INPUT"] + c["INFEASIBLE_SPECIFICATION"],
            "errors": c["INTERNAL_ERROR"] + c["HARNESS_EXCEPTION"], "raw": dict(c)}


def boundary_checks(rows):
    metrics = []
    for r in rows:
        if r["classification"] != "ACCEPTED":
            continue
        p = r["input"]
        ids = p["componentBasis"]["componentIds"]
        product = collections.defaultdict(float)
        for stream in r["streams"]:
            for f in stream["moleFractions"]:
                product[f["componentId"]] += stream["molarFlowMolPerSecond"] * f["moleFraction"]
        total = sum(p["feedComponentMolarFlowsMolPerSecond"])
        error = max(abs(product[c] - f) for c, f in zip(ids, p["feedComponentMolarFlowsMolPerSecond"]))
        water = abs(product["Water"] - sum(s["molarFlowMolPerSecond"] for s in p["steamFeeds"]))
        assert all(c["passed"] for c in r["inspection"]["audit"]["checks"])
        metrics.append({"index": r["index"], "component_error": error, "water_error": water,
                        "component_screen_pass": max(error, water) <= 1e-6 * max(1, total),
                        "flood": (r.get("hydraulics") or {}).get("maximumFloodFraction", 0)})
    return {"accepted": len(metrics), "component_screen_passed": sum(m["component_screen_pass"] for m in metrics),
            "largest_component_error_mol_s": max((m["component_error"] for m in metrics), default=None),
            "largest_water_error_mol_s": max((m["water_error"] for m in metrics), default=None),
            "over_flood": sum(m["flood"] > 1 for m in metrics)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--plot", action="store_true")
    parser.add_argument("--replay", type=Path)
    args = parser.parse_args()
    before_all, after_all = read(args.before / "cases.jsonl"), read(args.after / "cases.jsonl")
    bm = json.loads((args.before / "manifest.json").read_text())
    am = json.loads((args.after / "manifest.json").read_text())
    assert len(before_all) == bm["count"] and len(after_all) == am["count"], "Run incomplete"
    b = {r["index"]: r for r in before_all}
    a = {r["index"]: r for r in after_all}
    assert len(b) == len(before_all) and len(a) == len(after_all)
    for folder, actual in [(args.before, b), (args.after, a)]:
        frozen = read(folder / "inputs.jsonl")
        assert len(frozen) == len(actual) and len({r["index"] for r in frozen}) == len(frozen)
        assert {r["index"] for r in frozen} == set(actual)
        for row in frozen:
            assert row["input"] == actual[row["index"]]["input"]
            assert row["distribution"] == actual[row["index"]]["distribution"]
    assert bm["workers"] == am["workers"] and bm["secondsPerCase"] == am["secondsPerCase"]
    paired = sorted(b)
    assert set(paired) <= set(a)
    for i in paired:
        assert b[i]["input"] == a[i]["input"] and b[i]["distribution"] == a[i]["distribution"]
    assert all(next(s["ratio"] for s in r["input"]["specifications"] if "ratio" in s) > 0 for r in after_all)
    before, after = [b[i] for i in paired], [a[i] for i in paired]
    gains = [i for i in paired if b[i]["classification"] != "ACCEPTED" and a[i]["classification"] == "ACCEPTED"]
    losses = [i for i in paired if b[i]["classification"] == "ACCEPTED" and a[i]["classification"] != "ACCEPTED"]
    groups = {}
    for name in ["control", "near", "broad", "boundary"]:
        groups[name] = {"before": counts([r for r in before if r["distribution"] == name]),
                        "after": counts([r for r in after if r["distribution"] == name])}
    summary = {"paired_cases": len(paired), "before": counts(before), "after": counts(after),
               "after_full_population": counts(after_all), "gains": gains, "losses": losses,
               "after_full_groups": {name: counts([r for r in after_all if r["distribution"] == name]) for name in groups},
               "preserved_acceptances": sum(b[i]["classification"] == a[i]["classification"] == "ACCEPTED" for i in paired),
               "losses_by_status": dict(collections.Counter(a[i]["classification"] for i in losses)),
               "groups": groups, "before_boundary_checks": boundary_checks(before),
               "after_boundary_checks": boundary_checks(after), "full_after_boundary_checks": boundary_checks(after_all),
               "controls": [{"index": i, "before": b[i]["classification"], "after": a[i]["classification"]} for i in range(4)],
               "smallest_lost_acceptances": sorted(losses, key=lambda i: (b[i]["input"]["stageCount"], i))[:2],
               "note": "Raw acceptance under each revision's own production audit; timeouts censored; current audit is stricter. Not a speed benchmark."}
    replay_rows = read(args.replay / "cases.jsonl") if args.replay else []
    replay_manifest = json.loads((args.replay / "manifest.json").read_text()) if args.replay else {}
    if args.replay:
        assert len(replay_rows) == replay_manifest["count"]
        assert len({r["index"] for r in replay_rows}) == len(replay_rows)
    for row in replay_rows:
        assert row["index"] in losses and row["input"] == b[row["index"]]["input"]
    summary["budget_replay"] = [{"index": r["index"], "classification": r["classification"], "elapsed_ms": r["elapsedMillis"],
                                 "workers": replay_manifest["workers"], "seconds": replay_manifest["secondsPerCase"]} for r in replay_rows]
    load_note = args.after / "RUN_LOAD_NOTE.md"
    summary["load_note"] = load_note.read_text(encoding="utf-8-sig").strip() if load_note.exists() else ""
    out = args.after
    (out / "comparison.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    replay_by_id = {r["index"]: r for r in replay_rows}
    flat = [{"index": i, "distribution": b[i]["distribution"], "stages": b[i]["input"]["stageCount"],
             "before": b[i]["classification"], "after": a[i]["classification"],
             "isolated_budget_replay": replay_by_id.get(i, {}).get("classification", ""),
             "before_summary": b[i].get("summary", ""), "after_summary": a[i].get("summary", "")} for i in paired]
    with (out / "paired.csv").open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(flat[0])); writer.writeheader(); writer.writerows(flat)
    lines = ["# CDU revision comparison", "", f"{len(paired)} identical full-CDU inputs; {bm['workers']} workers; {bm['secondsPerCase']} s cooperative budget.", "",
             "| Group | Cases | Before accepted | After accepted | Before timeouts | After timeouts |", "|---|---:|---:|---:|---:|---:|"]
    for name, group in groups.items():
        x, y = group["before"], group["after"]
        lines.append(f"| {name} | {x['cases']} | {x['accepted']} | {y['accepted']} | {x['budget']} | {y['budget']} |")
    lines += ["", f"Newly accepted: {len(gains)}; lost raw acceptances: {len(losses)}; preserved: {summary['preserved_acceptances']}.",
              "", "Raw acceptance is under each version's own audit. Rejected, numerically failed and time-censored cases remain distinct. Extra component checks and hydraulic flooding counts are in comparison.json. Current full-population counts are reported separately from the matched subset."]
    lines += ["", "| Current full population | Cases | Accepted | Numerical/phase | Time budget |", "|---|---:|---:|---:|---:|"]
    for name, group in summary["after_full_groups"].items():
        lines.append(f"| {name} | {group['cases']} | {group['accepted']} | {group['numerical']} | {group['budget']} |")
    for r in summary["budget_replay"]:
        lines += ["", f"Budget replay (not blended into the paired counts): case {r['index']} = {r['classification']}, {r['elapsed_ms']/1000:.3f} s, {r['workers']} worker, {r['seconds']} s allowance."]
    if summary["load_note"]:
        lines += ["", summary["load_note"]]
    (out / "CDU_COMPARISON.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if args.plot:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(figsize=(9, 4.8))
        left = [0, 0]
        for key, label, color in [("accepted", "Production accepted", "#227d73"), ("numerical", "Numerical/phase failure", "#b54849"),
                                   ("budget", "Time budget", "#dfa64a"), ("rejected", "Input/spec rejection", "#9aa6af"), ("errors", "Internal/harness", "#303740")]:
            values = [100 * summary[v][key] / len(paired) for v in ["before", "after"]]
            bars = ax.barh(["Before", "After"], values, left=left, color=color, label=label, height=.6)
            for bar, value in zip(bars, values):
                if value >= 7:
                    ax.text(bar.get_x() + value / 2, bar.get_y() + bar.get_height() / 2, f"{value:.1f}%", ha="center", va="center", color="white")
            left = [x + y for x, y in zip(left, values)]
        ax.invert_yaxis(); ax.set_xlim(0, 100); ax.set_xlabel("Observed outcome (%)")
        ax.spines[["top", "right"]].set_visible(False)
        ax.set_title(f"CDU · {len(paired)} matched positive-reflux cases", loc="left", fontsize=16, weight="bold")
        ax.legend(loc="upper center", bbox_to_anchor=(.5, -.19), ncol=3, frameon=False)
        fig.text(.10, .02, "Same frozen inputs and budgets; version-specific audits; timeouts are censored, not infeasibility.", fontsize=9, color="#53616b")
        fig.subplots_adjust(bottom=.29, left=.12, right=.96, top=.86)
        fig.savefig(out / "comparison.png", dpi=180); fig.savefig(out / "comparison.svg"); plt.close(fig)
    print(json.dumps({k: summary[k] for k in ["paired_cases", "before", "after", "after_full_population", "gains", "losses"]}))


if __name__ == "__main__":
    main()
