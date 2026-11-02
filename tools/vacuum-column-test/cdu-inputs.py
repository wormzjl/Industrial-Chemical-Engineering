"""Freeze positive-reflux full-CDU cases around the shipped Tia Juana reference."""
import argparse
import copy
import json
import math
import random
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--count", type=int, default=1000)
parser.add_argument("--seed", type=int, default=20261029)
args = parser.parse_args()
assert 4 <= args.count <= 10000, "Use 4 to 10000 cases"
source = json.loads(args.source.read_text(encoding="utf-8-sig"))
assert source["success"]
base = source["input"]
assert base["packageId"] == "icheme:tjl20_methane" and base["stageCount"] == 40
assert len(base["sideDraws"]) == len(base["pumparounds"]) == 3
out = args.output.resolve()
repo = Path(__file__).resolve().parents[2]
assert out.parent == repo / "tmp", "Output must be a named folder in this worktree's tmp/"
out.mkdir(parents=True, exist_ok=True)
index = repo / "tmp" / "INDEX.md"
if not index.exists():
    index.write_text("# Temporary outputs\n\n| Folder | Purpose | Date | Task |\n|---|---|---|---|\n", encoding="utf-8")
if f"| {out.name}/" not in index.read_text(encoding="utf-8-sig"):
    with index.open("a", encoding="utf-8") as stream:
        stream.write(f"| {out.name}/ | Frozen full-CDU comparison inputs | 2026-10-29 | documentation/2026-10-28-vacuum-column/ |\n")
rows = []
for i in range(args.count):
    r = random.Random(args.seed + i)
    p = copy.deepcopy(base)
    group = "control" if i < 4 else "near" if i % 5 < 2 else "broad" if i % 5 < 4 else "boundary"
    near = group in {"near", "control"}
    n = base["stageCount"] if near else r.choice([20, 30, 40, 50, 60])
    scale = r.uniform(.8, 1.2) if near else r.uniform(.5, 1.5)
    tint = base["feedTemperatureKelvin"] + r.uniform(-10, 10) if near else r.uniform(603.15, 673.15)
    pressure = base["topPressurePascal"] * r.uniform(.9, 1.1) if near else r.uniform(150000, 350000)
    reflux = 4.17 * r.uniform(.95, 1.05) if near else r.uniform(2, 6)
    condenser = base["specifications"][0]["kelvin"] + r.uniform(-3, 3) if near else r.uniform(313.15, 353.15)
    diameter = base["columnDiameterMetres"] * math.sqrt(scale) * (r.uniform(.95, 1.05) if near else r.uniform(.85, 1.15))
    steam_factor = r.uniform(.9, 1.1) if near else r.uniform(.5, 1.5)
    draw_factor = r.uniform(.9, 1.1) if near else r.uniform(.7, 1.3)
    heat_factor = r.uniform(.9, 1.1) if near else r.uniform(.7, 1.3)
    tilt = 0 if near else r.uniform(-.15, .15)
    if group == "control":
        n, scale, tint, pressure, reflux, condenser, diameter = 40, 1, 638.15, 250000, 4.17, 332.15, 8
        steam_factor = draw_factor = heat_factor = 1
        if i == 1:
            tint += 1
        elif i == 2:
            tint -= 1
        elif i == 3:
            scale, diameter = 1.05, 8 * math.sqrt(1.05)
    elif group == "boundary":
        edge = (i // 5) % 10
        if edge == 0:
            n = 64
        elif edge == 1:
            reflux = .5
        elif edge == 2:
            reflux = 8
        elif edge == 3:
            steam_factor = 0
        elif edge == 4:
            pressure = 100000
        elif edge == 5:
            pressure = 500000
        elif edge == 6:
            heat_factor = 1.8
        elif edge == 7:
            draw_factor = 1.7
        elif edge == 8:
            tint = 578.15
        else:
            tint = 698.15
    feed = [f * math.exp(tilt * (2 * c / (len(base["feedComponentMolarFlowsMolPerSecond"]) - 1) - 1))
            for c, f in enumerate(base["feedComponentMolarFlowsMolPerSecond"])]
    factor = scale * sum(base["feedComponentMolarFlowsMolPerSecond"]) / sum(feed)
    p["feedComponentMolarFlowsMolPerSecond"] = [f * factor for f in feed]
    p.update(stageCount=n, feedStageNumber=max(1, min(n + 1, round(n * base["feedStageNumber"] / 40))),
             feedTemperatureKelvin=tint, topPressurePascal=pressure, columnDiameterMetres=min(15, diameter))
    p["specifications"] = [{"kelvin": condenser}, {"ratio": reflux}, {"watts": 0}]
    for draw, original in zip(p["sideDraws"], base["sideDraws"]):
        draw.update(trayNumber=max(1, round(n * original["trayNumber"] / 40)),
                    molarFlowMolPerSecond=original["molarFlowMolPerSecond"] * scale * draw_factor)
    for steam, original in zip(p["steamFeeds"], base["steamFeeds"]):
        steam.update(stageNumber=n + 1, molarFlowMolPerSecond=original["molarFlowMolPerSecond"] * scale * steam_factor)
    if steam_factor == 0:
        p["steamFeeds"] = []
    for pa, original in zip(p["pumparounds"], base["pumparounds"]):
        pa.update(returnTray=max(1, round(n * original["returnTray"] / 40)),
                  drawTray=max(1, round(n * original["drawTray"] / 40)), dutyWatts=original["dutyWatts"] * scale * heat_factor)
    assert reflux > 0 and len(p["sideDraws"]) == len(p["pumparounds"]) == 3
    rows.append({"index": i, "distribution": group, "input": p})
with (out / "inputs.jsonl").open("x", encoding="utf-8") as stream:
    for row in rows:
        stream.write(json.dumps(row, separators=(",", ":")) + "\n")
(out / "generation.json").write_text(json.dumps({"seed": args.seed, "count": args.count,
    "sourceInputDigest": source["inputDigest"], "description": "Full positive-reflux CDU; three side cuts and three pumparounds; cold cases"}, indent=2), encoding="utf-8")
print(f"Frozen {len(rows)} full-CDU inputs in {out}")
