# Effects bench (W11): results, draft

2026-11-10, sub-thread, Opus medium. Plan: `FX_BENCH_PLAN.md`. Tool: `tools/fx-bench/` (probe `mode=fx`), logs in the main checkout's `tools/fx-bench/logs/fx-bench-logs-2026-11-10.tar.gz`.

**Setup.** RTX 4070 Ti, 1920 × 1080, render distance 12, vsync off. Effects code c8d5c4d09 (flame rework, 110 dB full gain, valve kinds by cavitation). Baseline 1-off on 0f7603b0b (effects OFF; the wiring is the same code). 60 s warm-up + 60 s measured per window, JFR on in every run. Owner's choice: run beside a PlateTectonics campaign (3 `geosim.exe`, one core each), so the lane's CPU check was skipped (`CPU_OK=1`). Window load (`FPS_LOAD` systemCpu): 1-off 31/35 %, 1-auto 51/31 %, 2-compat 25/34 %, 5-sound 30 %, 3-off 40/61 %, 3-auto 65/60 %, 4-sil 86 %, 4-nosil 81 %. Iris 1.8.12 + Sodium and DH 3.3.3 resolved online (`ONLINE=1`). 3-auto's away window is **overlapped**: another session's Gradle test JVM (worktree `distillation-failure-mode-control-db2da5`) started at 13:29:39, during that window. It is kept as a record, not rerun (owner: retest only the changed parts). Game music off from 5-sound on.

| Budget | Lane | Measured | Limit | Verdict |
|---|---|---|---|---|
| Frame time p50, AUTO over OFF | 1-auto view / away | +0.36 / −0.04 ms | ≤ 0.3 ms | MISS (view, at a higher load) / PASS |
| GPU of our draws, AUTO over OFF | 1-auto view: `AFTER_PARTICLES` stage; whole frame | +0.27 ms; +0.48 ms | ≤ 0.5 ms | PASS (see note 2) |
| Render-thread CPU p50 (effects + wiring) | 1-auto view | 0.93 ms (effects draw 0.13, wiring 0.79) | ≤ 0.3 ms | MISS |
| | 5-sound view | 0.85 ms (draw 0.12, wiring 0.73) | ≤ 0.3 ms | MISS |
| | 2-compat view (COMPATIBLE) | 3.82 ms (build 2.78, wiring 0.74) | ≤ 0.3 ms | MISS |
| | 3-auto view (Iris: COMPATIBLE) | 4.07 ms (build 2.90, wiring 1.08) | ≤ 0.3 ms | MISS |
| Rebuilds only on a snapshot | 1-auto view | 528 in 60 s, 94 emitters | ≤ 1,222 | PASS |
| Sound tick mean | 5-sound (100 valves in 4 kinds, 20 fans) | 0.035 ms (p95 0.19) | ≤ 0.05 ms | PASS |
| Loops playing, most | 5-sound | 12 (160 sources, 80 groups) | ≤ 12 | PASS |
| Allocation per frame (JFR estimate) | 1-auto view / away | 10.2 / 5.0 MB | < 1 KB | MISS |
| Resident memory | largest (5-sound) | 0.66 MB | ≤ 32 MB | PASS |
| Server, per publication per island | towers, heaters, noise, noise recomputed, silhouettes | ≤ 0.009 ms each (noise 0.30 ms for 71 islands) | ≤ 0.1 ms | PASS |
| Server silhouettes, every subscriber ranked | 4-sil (1 subscriber) | 0.52 ms (plain reply 0.006 ms) | ≤ 0.1 ms | MISS |
| Silhouettes' frame cost (DH, far window 600 blocks) | 4-sil − 4-nosil | +0.41 ms p50 frame, GPU frame 0.51 / 0.50 ms; not a valid measure (note 4) | record | — |

For the record: COMPATIBLE costs +3.23 ms p50 over OFF (2-compat view). Under Iris with Complementary Reimagined, AUTO costs +4.91 / +1.57 ms over OFF (view / away, the away number overlapped), because AUTO takes the COMPATIBLE path under a pack.

**Hot spots (JFR, render thread: execution samples, then allocation weight).**
- 1-auto view: CPU `InstrumentRenderer.quad` 1,181, `.render` 314, `.drawWiring` 120, `.draw` 30, `.light` 26 (`FxBatch.draw` 12). Allocation `InstrumentRenderer.quad` 287 GB (`double[]`), `.drawWiring` 11.7 GB, `.render` 9.7 GB, `.draw` 0.8 GB, `FxBatch.drawShader` 0.71 GB (23 KB a frame).
- 1-auto away: CPU `InstrumentRenderer.render` 515, `.drawWiring` 342, `.visible` 21, `.hud` 6, `$Seen.hashCode` 2. Allocation `.drawWiring` 409 GB (`ArrayList`), `.render` 30 GB, `.hud` 0.8 GB, `FxBatch.draw` 0.33 GB, `FxBatch.drawShader` 0.10 GB.
- 2-compat view: CPU `PlumeModel.centreLine` 1,040, `InstrumentRenderer.quad` 461, `.render` 97, `.drawWiring` 66, `FxImmediateSink.square` 27. Allocation `InstrumentRenderer.quad` 40 GB, `.drawWiring` 4.8 GB, `.render` 4.3 GB, `PlumeModel.centreLine` 1.9 GB, `FxBatch.drawCompatible` 1.1 GB.
- 3-auto view: CPU `PlumeModel.centreLine` 639, `InstrumentRenderer.quad` 483, `.render` 135, `.drawWiring` 60, `FxImmediateSink.vertex` 31.
- 5-sound view: same as 1-auto view (`InstrumentRenderer.quad` 1,362); the sound code is not in the top frames.

**Reading.**
1. The CPU and allocation misses come from the instrument wiring (`InstrumentRenderer`), not the effects. Its cost is the same with effects OFF (1-off view: wiring 0.73 ms, 10 MB a frame), so it is per-frame quad building with `double[]` and stream temporaries. The effects' own SHADER draw is 0.13 ms (within 0.3), but `FxBatch.drawShader` still allocates about 23 KB a frame (limit 1 KB). The COMPATIBLE cost is `PlumeModel.centreLine`, called per frame for every plume.
2. Since 0d562f7b0 the effects draw at `AFTER_WEATHER`, but the probe brackets `AFTER_PARTICLES` only. The stage numbers no longer contain every effect draw: 2-compat's stage (0.08 ms) is below OFF's (0.74 ms). The whole-frame GPU delta, +0.48 ms, is the bound to use.
3. The 1-auto view frame-time miss (+0.36 ms) was measured at 51 % machine load against the baseline's 31 %. Replay it alone once the machine is quiet.
4. In 4-sil, Distant Horizons 3.3.3 loaded and the client store held 84 silhouettes, but `FarSilhouettes` built no boxes (`silhouetteBoxes=0`). The far shots of 4-sil and 4-nosil are identical: DH's own LODs draw the scene, and no silhouette appears. So the frame delta (+0.41 ms, at 86 / 81 % load) is not silhouette drawing, and silhouette memory and frame cost are unmeasured. This is a defect to trace in `FarSilhouettes.plan` (no built entry, or no `FarRenderer`), not a bench fault. The silhouette ranking (`publishSilhouettes` with every subscriber ranked) costs 0.52 ms for one subscriber, its only server miss.

**Validity.** Every window fed 10/10 flares, 10/10 stills, 4/4 towers and 10/10 heaters, plus 50 drums with 100 gauges and 50 sensors. The summarizer's `SHORT: stills` on the before line is 7/7: only 7 stills are in range before the camera moves to the view point. `summarize.mjs` read the window's start line instead of its result: fixed (uncommitted).
