# CPU, GPU and RAM bench of the effects system: plan (W11)

2026-11-10, owner: "Bench the CPU/GPU/RAM [overhead] and optimize as part of the work". Runs after phase 2, before the batch docs.

## What is measured, and the budgets

| Quantity | How | Budget (1920 × 1080, RTX 4070 Ti, render distance 12) |
|---|---|---|
| Frame time p50 / p95 / p99 | `FrameTimeProbe` (`tools/custom-equipment-rendering-poc/frame-time-probe/`), as the LOD batch | effects AUTO at most 0.3 ms p50 over effects OFF, same scene |
| GPU time of our draws | the probe's GPU timestamps bracketing `AFTER_PARTICLES`, read on a lane with only our drawers active there | at most 0.5 ms p50 |
| Render-thread CPU of our code | FxBatch counters: ns per frame for build and draw, quads, cached-buffer rebuilds | at most 0.3 ms p50; rebuilds only when a snapshot arrives |
| Sound tick | `FxSounds` counters: ns per client tick, loops playing, sources merged | at most 0.05 ms per tick |
| Allocation | JFR allocation samples filtered to `com.wormzjl.icheme.client.fx` during the measurement window | SHADER path at steady state: under 1 KB per frame |
| Resident memory | counters: vertex-buffer bytes, effect-texture bytes, silhouette boxes, state stores | at most 32 MB in total |
| Server | ns per presentation publication for the new states, noise levels and silhouette replies, from counters in a unit-level micro-run | at most 0.1 ms per publication per island |

A small, always-on `FxStats` (counter increments only) feeds one F3 line: path, quads, emitters, loops. It stays in the product. Timer queries, JFR filters and the probe mode are measurement-only and leave `src/main` before the merge (`merge-prep`, tooling cleanup).

## Scene and lanes

- Synthetic states, so the bench measures drawing rather than process start-up: a bench-only client command feeds the client state stores (flare, still, tower, heater, noise) at full load. Scene: 10 flares at full flame, 2 natural and 2 induced towers, 10 fired heaters, 10 fired stills, 50 drums with full instrument wiring, 100 flowing valves at 1 MPa drop, in a snowy-plains flat world so every plume shows (the worst case).
- Lanes, each 60 s warm-up and 60 s measurement, timing runs alone with no other Gradle and no dev client running, free RAM checked first:
  1. effects OFF (baseline) and AUTO (SHADER path): view and away windows;
  2. COMPATIBLE forced: the per-frame vertex path;
  3. AUTO with Iris and a shader pack (`-PwithIris`). The pack, Complementary Reimagined from Modrinth, needs the owner's yes to download;
  4. Distant Horizons with silhouettes on and off (`-PwithDh`), looking at the scene from 600 blocks;
  5. sounds: 100 valves and 20 fans in range, budget 12 loops.
- Tool folder `tools/fx-bench/`: scene files, `lane.sh` adapted from `tools/lod-rendering/lane.sh` (it refuses to run beside another timing run), the probe's `mode=fx`, the bench command. Logs go compressed to the main checkout's `tools/fx-bench/logs/`.

## Optimize

1. One full set of lanes on the phase-2 code.
2. Each budget missed: a JFR CPU and allocation profile of that lane, the hot spots fixed in the stage they belong to, then that one lane replayed once (one pair per claim).
3. Results and decisions in `FX_BENCH_REVIEW.md`, at most 50 lines.

## Agent

`fx-bench` (Opus, medium): writes the probe mode, the bench command, the scene, `lane.sh`, `FxStats` and the F3 line, with a detach patch per the tooling rules. It runs nothing. The main thread runs every lane. Needs the owner's yes, as every agent does.
