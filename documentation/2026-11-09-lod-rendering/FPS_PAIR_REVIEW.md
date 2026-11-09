# FPS pair: baseline against Stage A

- Status: measured 2026-11-09 (plan L4, owner request). Baseline: scratch worktree at `ca6965d2e` (the code of `main` `b794e2d41`). Stage A: `fa214e43b`. Lane: `tools/lod-rendering/` (`fcf8ebc76`, CPU check tightened afterwards).
- Setup: vanilla pipeline, 1920 × 1080 (k = 771 px, the plan's reference), RTX 4070 Ti, vsync off, unlimited frame rate, render distance 12, noon. Fresh flat world per lane; 100 gauged pipes 3–35 m from the camera, 40 drums and 10 columns at 39–75 m. All 100 gauges installed in each lane. 60 s warm-up + 60 s measurement per window.

## Results

| Window | Build | Mean FPS | p50 ms | p95 ms | p99 ms | System CPU |
|---|---|---|---|---|---|---|
| view | baseline | 89.4 | 10.68 | 14.94 | 18.42 | 31 % |
| view | baseline, first lane | 98.5 | 9.89 | 11.41 | 12.51 | 21 % |
| view | Stage A | 574.9 | 1.63 | 2.46 | 2.93 | 23 % |
| away | baseline | 90.3 | 10.65 | 14.55 | 17.77 | 32 % |
| away | Stage A | 1468.1 | 0.64 | 0.92 | 1.53 | 30 % |

- The first baseline lane's away window is not counted: another session's job held the machine at 76 % CPU during it. The lane script now refuses to start above 25 % CPU. Its view window is shown to give the run-to-run spread (about 10 %).
- `view`: facing the gauges and equipment. `away`: same point facing empty ground, every gauge behind the camera.

## Reading

1. Expected: 100 gauges cost the CPU only where they are seen, and little past the distance where their detail falls under a pixel. The plan estimated 10–20 ms a frame for 100 dials in the baseline.
2. Measured: the baseline costs the same facing the gauges or facing away (10.68 against 10.65 ms). The renderer drew every gauge in 120 m every frame. Stage A facing away: 0.64 ms, so the baseline spent about 10.0 ms a frame on 100 gauges, 0.1 ms each, at the low end of the estimate. Facing the scene, Stage A takes 1.63 ms (6.5× faster): about 1 ms for the 100 tiered gauges, the 50 vessels and the terrain together.
3. The shots match (main checkout `research/2026-11-09-lod-rendering/shots/`), with one difference: the dial at the near right (5.45 m away, 46° off the view axis) loses its scale text in Stage A while it covers about 75 px. The tier uses straight-line distance. Off the axis, an object is nearer the camera's plane than its distance, and the screen draws it larger: 3.6 m deep against 5.45 m, 1.5× here, up to about 1.8× in the corners at FOV 70°. Its scale labels are about 5 px high there.

## Proposals for the owner

- L2, body route: **B3 (no body LOD) for now.** With the gauges tiered, the whole view with 10 columns and 40 drums takes 1.63 ms, and the GPU frame is 1.45 ms at p50. On this GPU the chunk-baked bodies are not a cost worth a second mesh path. Revisit B1 only if a weaker GPU shows vertex-bound frames.
- Distance against view depth, for the tiers (a choice the physics does not force):
  - A. Keep distance (now). Tiers do not change when the player turns. Objects at the screen's edge lose detail up to 1.8× earlier than their size on screen asks.
  - B. Use view depth, the distance along the view axis. Exact on-screen size, but tiers change as the player turns (the 15 % hysteresis damps it). Costs one dot product per object.
  - C. Keep distance and lower the thresholds by 1.5× (FULL from 32 px). Edge objects keep their detail; centred objects keep theirs 1.5× longer than needed. Gives back part of Stage A's gain.

Logs: main checkout `tools/lod-rendering/logs/lod-fps-pair-logs-2026-11-09.tar.gz`. Code guide: no change (measurement only).
