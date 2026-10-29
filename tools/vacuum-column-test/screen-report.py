"""Validate/flatten an offline column screen and optionally plot its observed outcomes."""
from __future__ import annotations

import argparse
import collections
import csv
import json
from pathlib import Path


def category(status: str) -> str:
    if status == "ACCEPTED":
        return "Accepted + postchecks"
    if status == "TIME_BUDGET":
        return "Time budget"
    if status in {"INVALID_INPUT", "INFEASIBLE_SPECIFICATION"}:
        return "Input/spec rejection"
    if status == "POSTCHECK":
        return "Postcheck failure"
    if status in {"INTERNAL_ERROR", "HARNESS_EXCEPTION"}:
        return "Internal/harness error"
    return "Numerical/phase failure"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--plot", action="store_true")
    parser.add_argument("--partial", action="store_true")
    args = parser.parse_args()
    out = args.output.resolve()
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8-sig"))
    expected = [json.loads(line) for line in (out / "inputs.jsonl").read_text(encoding="utf-8-sig").splitlines()]
    rows = [json.loads(line) for line in (out / "cases.jsonl").read_text(encoding="utf-8-sig").splitlines()]
    assert rows, "No completed cases yet"
    assert len(expected) == manifest["count"]
    by_id = {row["index"]: row for row in rows}
    assert len(rows) == len(by_id), "Duplicate completed case"
    if not args.partial:
        assert len(rows) == manifest["count"], "Campaign is incomplete"
        assert set(by_id) == set(range(manifest["count"]))
    for row in rows:
        source = expected[row["index"]]
        assert source["input"] == row["input"], "Completed input differs from its frozen manifest"
        assert source["distribution"] == row["distribution"]
        reflux = [s["ratio"] for s in row["input"]["specifications"] if "ratio" in s]
        assert reflux == [0.0], "Population contains nonzero external reflux"
    rows.sort(key=lambda row: row["index"])

    flat = []
    counts = collections.Counter()
    groups = collections.defaultdict(collections.Counter)
    stages = collections.defaultdict(collections.Counter)
    failed_families = collections.Counter()
    for row in rows:
        p = row["input"]
        status = row["classification"]
        counts[status] += 1
        groups[row["distribution"]][status] += 1
        stages[p["stageCount"]][status] += 1
        checks = row.get("checks", {})
        audit = (row.get("diagnostics") or {}).get("acceptanceAudit") or {}
        families = [c["family"] for c in audit.get("checks", []) if not c["passed"]]
        failed_families.update(families)
        total = sum(p["feedComponentMolarFlowsMolPerSecond"])
        expected_dry_prefix = min([p["feedStageNumber"]] + [pa["returnTray"] for pa in p["pumparounds"] if pa["dutyWatts"] < 0]) - 1
        if checks:
            assert checks["dryPrefixCount"] == expected_dry_prefix
        flat.append({
            "index": row["index"], "distribution": row["distribution"], "classification": status,
            "category": category(status), "interior_stages": p["stageCount"], "feed_stage": p["feedStageNumber"],
            "feed_kmol_h": total * 3.6, "feed_C": p["feedTemperatureKelvin"] - 273.15,
            "top_kPa_abs": p["topPressurePascal"] / 1000, "diameter_m": p["columnDiameterMetres"],
            "steam_kmol_h": sum(s["molarFlowMolPerSecond"] for s in p["steamFeeds"]) * 3.6,
            "side_draw_fraction_of_feed": sum(s["molarFlowMolPerSecond"] for s in p["sideDraws"]) / total,
            "cooling_MW": -sum(s["dutyWatts"] for s in p["pumparounds"]) / 1e6,
            "elapsed_ms_under_eight_workers": row["elapsedMillis"],
            "max_scaled_residual": row.get("diagnostics", {}).get("maximumScaledResidual"),
            "component_error_mol_s": checks.get("maxComponentErrorMolPerSecond"),
            "water_error_mol_s": checks.get("waterErrorMolPerSecond"),
            "energy_error_W": checks.get("energyErrorWatts"), "dry_prefix_count": checks.get("dryPrefixCount"),
            "expected_dry_prefix_count": expected_dry_prefix,
            "minimum_dry_HC_beta": checks.get("minimumDryGasBeta"),
            "flood_fraction": (row.get("hydraulics") or {}).get("maximumFloodFraction"),
            "failed_audit_families": ";".join(families), "summary": row.get("summary", ""),
        })
    with (out / "cases.csv").open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(flat[0]))
        writer.writeheader()
        writer.writerows(flat)

    successes = [row for row in rows if row["classification"] == "ACCEPTED"]
    postchecks = [row for row in rows if row["classification"] == "POSTCHECK"]
    smallest = sorted((row for row in rows if row["classification"] not in {"ACCEPTED", "TIME_BUDGET", "INVALID_INPUT", "INFEASIBLE_SPECIFICATION"}),
                      key=lambda row: (row["input"]["stageCount"], row["index"]))[:2]
    summary = {
        "complete": len(rows) == manifest["count"], "completed": len(rows), "expected": manifest["count"],
        "counts": dict(counts), "by_distribution": dict(groups), "by_stages": dict(sorted(stages.items())),
        "failed_audit_families": dict(failed_families),
        "accepted_with_postchecks_fraction": len(successes) / len(rows),
        "production_accepted_fraction": (len(successes) + len(postchecks)) / len(rows),
        "accepted_over_flood": sum((row.get("hydraulics") or {}).get("maximumFloodFraction", 0) > 1 for row in successes),
        "cases_with_structurally_dry_top": sum(r["expected_dry_prefix_count"] > 0 for r in flat),
        "accepted_with_structurally_dry_top": sum(r["checks"]["dryPrefixCount"] > 0 for r in successes),
        "largest_accepted_component_error_mol_s": max((r["checks"]["maxComponentErrorMolPerSecond"] for r in successes), default=None),
        "largest_accepted_energy_error_W": max((abs(r["checks"]["energyErrorWatts"]) for r in successes), default=None),
        "smallest_observed_failures": [{"index": r["index"], "stages": r["input"]["stageCount"],
                                          "classification": r["classification"], "summary": r.get("summary", "")} for r in smallest],
        "postcheck_failures": [{"index": r["index"], "checks": r["checks"]} for r in postchecks],
        "limits": {"workers": manifest["workers"], "seconds_per_case": manifest["secondsPerCase"],
                   "note": "Timeouts are censored. The parameter ranges and postcheck tolerances are screening choices, not refinery standards."},
    }
    (out / "analysis.json").write_text(json.dumps(summary, indent=2, allow_nan=False), encoding="utf-8")
    lines = ["# Vacuum-column convergence population", "", f"Completed {len(rows)}/{manifest['count']}; {manifest['workers']} workers; {manifest['secondsPerCase']} s per case; every input has zero reflux.", "",
             "| Distribution | Cases | Accepted + postchecks | Numerical/phase | Time budget | Input/spec rejection | Postcheck | Internal/harness |",
             "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for name in ["control", "near", "broad", "boundary"]:
        group = groups[name]
        cat = collections.Counter()
        for status, number in group.items():
            cat[category(status)] += number
        lines.append(f"| {name} | {sum(group.values())} | {cat['Accepted + postchecks']} | {cat['Numerical/phase failure']} | {cat['Time budget']} | {cat['Input/spec rejection']} | {cat['Postcheck failure']} | {cat['Internal/harness error']} |")
    lines += ["", f"Verified acceptance rate within the screening budget: {len(successes)}/{len(rows)} ({len(successes)/len(rows):.1%}).",
              "", "The failure families identify terminal evidence, not necessarily root cause. Broad and boundary samples include unsupported or infeasible specifications. Time budgets do not prove that a case cannot converge.",
              "", "The fresh-CDU/heater coupling outcome is stored separately in heater-conditioning.json. The population uses the original fixed-temperature design as its center, with independent cold solves and synthetic composition tilts that retain every exact-zero source component."]
    (out / "SCREEN_RESULTS.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if args.plot:
        plot(out, groups, stages, manifest)
    print(json.dumps({k: summary[k] for k in ["complete", "completed", "counts", "accepted_with_postchecks_fraction", "accepted_over_flood"]}))


def plot(out, groups, stages, manifest):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np

    colors = {"Accepted + postchecks": "#227d73", "Numerical/phase failure": "#b54849", "Time budget": "#dfa64a",
              "Input/spec rejection": "#9aa6af", "Postcheck failure": "#795ba9", "Internal/harness error": "#303740"}
    fig, axes = plt.subplots(2, 1, figsize=(10.5, 8.2), gridspec_kw={"height_ratios": [1, 1.2]})
    names = ["control", "near", "broad", "boundary"]
    left = np.zeros(len(names))
    for label, color in colors.items():
        values = []
        for name in names:
            group = groups[name]
            values.append(100 * sum(v for s, v in group.items() if category(s) == label) / max(1, sum(group.values())))
        bars = axes[0].barh(names, values, left=left, color=color, label=label, height=.65)
        for bar, value in zip(bars, values):
            if value >= 9:
                axes[0].text(bar.get_x() + value / 2, bar.get_y() + bar.get_height() / 2, f"{value:.0f}%", ha="center", va="center", color="white", fontsize=9)
        left += values
    axes[0].set_yticks(range(len(names)), [f"{name} (n={sum(groups[name].values())})" for name in names])
    axes[0].invert_yaxis()
    axes[0].set_xlim(0, 100)
    axes[0].set_xlabel("Observed outcome within each distribution (%)")
    axes[0].set_title("Cold-start outcomes", loc="left", fontsize=13, weight="bold")
    sizes = sorted(stages)
    passed = [100 * stages[n]["ACCEPTED"] / sum(stages[n].values()) for n in sizes]
    bars = axes[1].bar([str(n) for n in sizes], passed, color=colors["Accepted + postchecks"], width=.65)
    for bar, n in zip(bars, sizes):
        axes[1].text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 1.5, f"n={sum(stages[n].values())}", ha="center", fontsize=8)
    axes[1].set_ylim(0, 110)
    axes[1].set_xlabel("Interior stages (condenser and sump excluded)")
    axes[1].set_ylabel("Accepted + postchecks (%)")
    axes[1].set_title("Acceptance by geometry; distributions pooled", loc="left", fontsize=13, weight="bold")
    for ax in axes:
        ax.spines[["top", "right"]].set_visible(False)
        ax.set_axisbelow(True)
        ax.grid(axis="x" if ax is axes[0] else "y", alpha=.18)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=3, frameon=False, fontsize=9, bbox_to_anchor=(.5, .035))
    fig.suptitle(f"Vacuum-column convergence screen · {manifest['count']:,} zero-reflux cases", fontsize=17, weight="bold", x=.08, ha="left")
    fig.text(.08, .015, f"Eight workers · {manifest['secondsPerCase']} s per case · timeouts censored · authored screening bounds, not design standards", fontsize=9, color="#53616b")
    fig.subplots_adjust(top=.89, bottom=.18, hspace=.48, left=.20, right=.96)
    fig.savefig(out / "convergence.png", dpi=180)
    fig.savefig(out / "convergence.svg")
    plt.close(fig)


if __name__ == "__main__":
    main()
