# lod-rendering (batch 2026-11-09-lod-rendering)

Purpose: the frame-time pair of the LOD batch: the same scene on two builds, the baseline and Stage A, each measured looking at the scene and looking away from it.

- `scene.txt`: 100 lone pipes 3 m apart (x 0..27, z 0..27, y −58), 40 standing drums (D 2 × 6 m) and 10 columns (D 3 m, 20 trays) behind them, noon, no weather or mobs.
- `view.txt`: spectator at (13.5, −57, −3) facing south, pitch 15°: gauges at 3–35 m, drums and columns at 39–75 m. `away.txt`: the same point facing north, every gauge behind the camera.
- `options.txt`: the simulator lane's (vsync off, `maxFps:260` unlimited, render distance 12).
- `scene-heavy.txt`, `view-heavy.txt`, `away-heavy.txt` (owner: "bench harder"): the same 100 gauges, 50 columns (D 4 m, 30 trays) and 200 drums (D 2.5 × 8 m), seen from 15 m up behind the grid. `lane.sh <label> <worktree> <logs> heavy [normal|low]`; `low` sets `low_polygon_mode` for that lane and resets it after.
- `lane.sh <label> <worktree> <log dir>`: attaches `../custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java` (uncommitted), writes `run/mcp-client/fps-autopilot/` with `mode=lod`, runs `runMcpClient` at 1920 × 1080 (k = 771 px, the plan's reference), keeps `<label>-client.log`, `<label>-summary.txt` (the `FPS_PROBE`, `FPS_LOAD`, `FPS_AUTOPILOT` lines) and the view shot, then removes the probe, the autopilot folder and the save. It refuses to start while another test worker, GameTest server or client runs: a timing run runs alone.
- The probe's `mode=lod` creates the flat world, runs the scene, waits 30 s, puts a pressure gauge on each pipe through the server's own install request (the player 1.8 m from the pipe on each side in turn), waits 10 s, then measures `view` and `away` (60 s warm-up + 60 s each). The `FPS_AUTOPILOT gauges installed` line gives the count.

Run (Git Bash, from the batch worktree; the baseline is a scratch worktree at the plan-only commit `ca6965d2e`, the code of `main` `b794e2d41`):

```bash
tools/lod-rendering/lane.sh base ../lod-baseline-scratch tmp/lod-rendering
tools/lod-rendering/lane.sh stage-a . tmp/lod-rendering
```

History:
- 2026-11-09: written for the Stage A pair (plan L4). Results in `documentation/2026-11-09-lod-rendering/FPS_PAIR_REVIEW.md`.
- 2026-11-09: heavy scene and `[normal|low]` detail switch for the heavy bench (`HEAVY_BENCH_REVIEW.md`).
- 2026-11-09: merged with the batch as 0.23.2; logs in the main checkout's `tools/lod-rendering/logs/`.
