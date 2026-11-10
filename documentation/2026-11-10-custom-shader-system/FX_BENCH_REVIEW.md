# Effects bench (W11): results, draft

2026-11-10, sub-thread, Opus medium. Plan: `FX_BENCH_PLAN.md`. Tool: `tools/fx-bench/` (probe `mode=fx`); logs in the main checkout's `tools/fx-bench/logs/fx-bench-logs-2026-11-10.tar.gz` (before) and `fx-bench-logs-2026-11-10-rerun.tar.gz` (after).

**Setup.** RTX 4070 Ti, 1920 × 1080, render distance 12, vsync off, 60 s warm-up + 60 s measured per window, JFR in every run, game music off. Owner's choice: beside a PlateTectonics campaign (`CPU_OK=1`, the load logged per window as `FPS_LOAD` systemCpu). Iris 1.8.12 + Sodium, DH 3.3.3.
- **Before**: effects c8d5c4d09, baseline 1-off on 0f7603b0b. Load 1-off 31/35 %, 1-auto 51/31 %, 2-compat 25/34 %, 3-off 40/61 %, 3-auto 65/60 % (away overlapped by a test JVM), 4-sil 86 %, 4-nosil 81 %, 5-sound 30 %.
- **After** (rerun after the optimization package, 50e00a0e2: wiring plans cached per host, allocation-free effects frame, plume centre lines kept per aim, ranking on kept boxes, the DH client-level fix; the probe's GPU stage = `AFTER_PARTICLES` + `AFTER_WEATHER`). Every lane but 5-sound (unchanged code; its rows stand from before). Load 1-off 39/56 %, 1-auto 35/44 %, 2-compat 31/43 %, 3-off 40/40 %, 3-auto 24/18 %, 4-sil 75 %, 4-nosil 88 %. 1-auto's load is within 15 points of its baseline in both windows, so the frame-time verdict is not load-bound. 3-off's away window ran beside another JVM (2.6 cores), so the pack's away delta is a record only.

## Rerun after the optimization: before and after per budget

| Budget | Lane | Before | After | Limit | Verdict after |
|---|---|---|---|---|---|
| Frame time p50, AUTO over OFF | 1-auto view / away | +0.36 / −0.04 ms | +0.04 / −0.11 ms | ≤ 0.3 ms | PASS |
| GPU of our draws, AUTO over OFF | 1-auto view: stage; whole frame | +0.27 (`AFTER_PARTICLES` only); +0.48 ms | +0.32 (both stages); +0.31 ms | ≤ 0.5 ms | PASS |
| Render-thread CPU p50 (effects + wiring) | 1-auto view | 0.93 (draw 0.13, wiring 0.79) | 0.48 (draw 0.10, wiring 0.37) | ≤ 0.3 ms | MISS (wiring) |
| | 2-compat view | 3.82 (build 2.78, wiring 0.74) | 0.75 (draw 0.36 incl. build 0.12, wiring 0.38) | ≤ 0.3 ms | MISS |
| | 3-auto view (Iris: COMPATIBLE) | 4.07 (build 2.90, wiring 1.08) | 0.60 (draw 0.10, build 0.09, wiring 0.50) | ≤ 0.3 ms | MISS |
| | 5-sound view | 0.85 | not rerun | ≤ 0.3 ms | MISS (before) |
| Rebuilds only on a snapshot | 1-auto view | 528 | 528 | ≤ 1,222 | PASS |
| Sound tick mean; loops | 5-sound | 0.035 ms; 12 | not rerun | ≤ 0.05 ms; ≤ 12 | PASS (before) |
| Allocation per frame (JFR estimate) | 1-auto view / away | 10.2 / 5.0 MB | 1.41 / 1.56 MB | < 1 KB | MISS |
| Resident memory | largest run | 0.66 MB (5-sound) | 0.82 MB (4-sil, 4,366 boxes) | ≤ 32 MB | PASS |
| Server, per publication per island | towers, heaters, noise, recomputed, silhouettes | ≤ 0.009 ms | ≤ 0.013 ms | ≤ 0.1 ms | PASS |
| Server silhouettes, every subscriber ranked | 4-sil, 1 subscriber | 0.52 ms | **0.073 ms** (p95 0.17) | ≤ 0.1 ms | PASS |
| Silhouettes drawn | 4-sil | 84 silhouettes, 0 boxes; far shots identical (62 px differ) | 84 silhouettes, **4,366 boxes**; far shots differ (1,787 of 38,400 px in the scene box) | boxes > 0 | PASS |
| Silhouettes' frame cost (record) | 4-sil − 4-nosil, far | not valid (no boxes) | −0.21 ms p50 frame, GPU frame 0.51 / 0.56 ms (load 75 / 88 %: no measurable cost) | record | — |
| COMPATIBLE over OFF (record) | 2-compat view, frame p50 / GPU frame | +3.23 / +2.43 ms | +0.35 / −0.09 ms | record | — |
| Pack: AUTO over OFF (record) | 3-auto − 3-off, view / away | +4.91 / +1.57 ms | +0.12 / 0.00 ms | record | — |

## Hot spots after (JFR, render thread)
- 1-auto view: CPU `InstrumentRenderer.submit` 1,205, `.render` 263, `$Plan.holds` 40. Allocation `InstrumentRenderer.submit` 57.7 GB (`org.joml.Vector3f`, 1.1 MB a frame), `.render` 13.6 GB (stream temporaries, `Object[]`), `FxBatch.drawShader` 1.16 GB (**22.6 KB a frame**, unchanged from before).
- 1-auto away and 1-off away: allocation `InstrumentRenderer.render` 137 / 121 GB (1.6 MB a frame: `Object[]` and stream pipelines, the same with effects OFF). `FxBatch.drawShader` 0.7 KB a frame.
- 2-compat view: CPU `InstrumentRenderer.submit` 942, `.render` 189, `FxBatch.drawCompatible` 30. Allocation `InstrumentRenderer.render` 10.9 GB, `FxBatch.drawCompatible` 3.3 GB, `FxImmediateSink.ribbon` 2.7 GB, `FlameModel.ribbon` 0.9 GB (`double[]`, `FxSink$Point`). `PlumeModel.centreLine` is gone from the top frames.

## Reading
1. Fixed by the package: the frame-time miss (+0.36 → +0.04 ms, at a comparable load), the COMPATIBLE cost (build 2.78 → 0.12 ms, frame +3.23 → +0.35 ms), the pack cost (+4.91 → +0.12 ms), the silhouette ranking (0.52 → 0.073 ms) and the silhouette drawing (0 → 4,366 boxes).
2. Still a MISS: render-thread CPU, 0.48 ms in 1-auto view. The wiring alone costs 0.37 ms (0.38 ms with effects OFF), above the 0.3 ms budget by itself; the effects' own draw is 0.10 ms. Its CPU is `InstrumentRenderer.submit`.
3. Still a MISS: allocation, 1.4–1.6 MB a frame, nearly all of it the wiring: `InstrumentRenderer.render`'s stream temporaries (in every window, OFF included) and `submit`'s `Vector3f` in the view window. The "allocation-free effects frame" is not reached on the SHADER path in view: `FxBatch.drawShader` still allocates 22.6 KB a frame (0.7 KB away). The COMPATIBLE path allocates in `FxImmediateSink.ribbon` and `FlameModel.ribbon`.
4. The probe's new stage sum puts the wiring's GPU time in OFF's stage (0.39 ms), so the stage delta and the whole-frame delta now agree (+0.32 / +0.31 ms).

**Validity.** Every window fed 10/10 flares, 10/10 stills, 4/4 towers, 10/10 heaters, plus 50 drums with 100 gauges and 50 sensors (the `before` line's 7/7 stills is the camera's start point). The rerun needed one probe fix: the ejector merge added `Noise.Kind.EJECTOR`, which the probe's solo switch lacked (uncommitted, `tools/`).
