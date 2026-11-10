# fx-bench (batch 2026-11-10-custom-shader-system)

Purpose: the CPU, GPU and RAM bench of the effects system (W11, `documentation/2026-11-10-custom-shader-system/FX_BENCH_PLAN.md`): the plan's scene at full load, one client run per lane configuration, each budget of the plan printed against its measured value.

## Files

- `scene.txt`: the plan's scene in a flat world (ground y −60), snowy plains under it (`fillbiome`, −6 °C, so every plume shows): 10 batch stills (z 0), 50 standing drums D 2 × 6 m (z 10..34, x −27..27, 6 m apart), 10 flare stacks (tip 0.3 m, z 46), 10 vertical fired heaters (4 × 8 m, z 58), 2 induced-draft towers (cell 12 m, z 74) and 2 natural-draft towers (D 40 m, x ±56, z 20); noon, no weather or mobs. The view point (0.5, −40, −30) keeps the stills within 48 blocks, the flares within 88 and the towers and heaters within 120: the client watch ranges (`ProcessStates`).
- `view.txt`: spectator at the view point facing south over the scene. `away.txt`: the same point facing north, every effect behind the camera (still watched). `far.txt`: lane 4, 600 blocks north of the scene's middle, facing it.
- `options.txt`: vsync off, `maxFps:260` (unlimited), render distance 12, as the LOD lane.
- `config/icheme-client-{off,auto,compatible,auto-nosilhouettes}.toml`: the `[effects]` section per run (mode OFF, AUTO, COMPATIBLE; `dh_silhouettes` off in the last); `heat_haze` stays at its default (off). `config/iris.properties`: Iris on with the pack, lane 3.
- `lane.sh <run> <worktree> <log dir>`: one run. It refuses to start while another fx lane (its lock `tmp/fx-bench/lane.lock`), a Gradle test worker, a GameTest server or a client runs, or the machine's CPU is above 25 %; it logs the free RAM. It attaches `../custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java` uncommitted to `src/mcpCompat`, copies the bridge jar, `options.txt`, the run's client config (and for lane 3 the pack into `run/mcp-client/shaderpacks/` and `iris.properties`), writes `run/mcp-client/fps-autopilot/` with `mode=fx`, runs `runMcpClient` at 1920 × 1080 (`-PwithIris` for lane 3, `-PwithDh` for lane 4), keeps `<run>-client.log`, `<run>-summary.txt`, the window shots and the windows' `.jfr` files, then removes the probe, the autopilot folder, the save and the pack and puts the configs back.
- `summarize.mjs <log dir>`: every budget of the plan against the runs' lines, PASS / MISS, then the feed and install checks.

## What the probe does (`mode=fx`)

A fresh flat world; `scene.txt`; 30 s; every gauge mount of each drum gets a gauge and each drum a digital level meter, through the server's own install requests (full wiring; `FPS_AUTOPILOT drum instruments` gives the counts); 10 s; then the synthetic feed from there to the quit: every client tick it stores the plan's full-load states (flares flaring at their rating with soot, stills distilling at full fire, towers' exhaust at 36 °C saturated, heaters at design firing on liquid fuel) for every structure the client's watches hold, through `ProcessStates.accept`, again whenever the server's republication of the real idle states (every 5 s) replaced them; ticks start after the packets are handled, so the effects and sounds see only the synthetic states. Lane 5 also feeds the noise watch: 100 valves at a 1 MPa drop (2 m grid, gas and liquid in turn) and 20 tower fans on a 12 m ring around the listener. `src/main` has no bench hook: the feed reads the stores by reflection.

Each window: its `.txt`, 60 s warm-up, 60 s measured. Lines per window: `FPS_PROBE` (frame-interval percentiles, GPU frame time), `FPS_LOAD`, `FX_PROBE` (FxStats per frame: effects draw, build and wiring ms; the CPU and GPU time of the `AFTER_PARTICLES` and `AFTER_WEATHER` stages (the wiring, and the effects at either stage by `FxBatch.stage()`), each timed apart and summed, from the probe's own first and last handlers of each stage with `GL_TIMESTAMP` counters; quads, emitters, rebuilds; the sound tick's ms, loops, sources, groups), `FX_JFR` (JFR started by the probe through `jdk.jfr`, so `runMcpClient` needs no JVM argument: it has none in `build.gradle`; allocation samples at 1000/s and render-thread execution samples every 10 ms, counted where the stack holds `client.fx` or `InstrumentRenderer`), `FX_FEED` (synthetic states held / watched per kind). After the windows: `FX_SERVER` (the server's `publishTowers`, `publishHeaters`, `publishNoise`, `publishSilhouettes`, 200 calls each by reflection on the server thread at the scene's size; noise and silhouettes also with every island recomputed and every subscriber ranked) and `FX_MEMORY` (FxStats' vertex, wiring and target bytes, the effects' and wiring's textures from GL, level 0; silhouette boxes, store sizes).

## Run

Timing runs alone: no other Gradle run, test suite or dev client on the machine. Git Bash, from the batch worktree (`ONLINE=1` on the first `-PwithIris` or `-PwithDh` run if Gradle's cache lacks those mods):

```bash
for run in 1-off 1-auto 2-compat 5-sound 3-off 3-auto 4-sil 4-nosil; do tools/fx-bench/lane.sh $run . tmp/fx-bench/runs; done
node tools/fx-bench/summarize.mjs tmp/fx-bench/runs
```

| Run | Config | Windows | Proves | About |
|---|---|---|---|---|
| `1-off` | OFF | view, away | the baseline | 8 min |
| `1-auto` | AUTO (SHADER path) | view, away | frame time and GPU over OFF, render-thread CPU, rebuilds, allocation, memory, server | 8 min |
| `2-compat` | COMPATIBLE | view, away | the per-frame vertex path's CPU, GPU, allocation | 8 min |
| `5-sound` | AUTO + noise feed | view | sound tick, loops at most 12 | 5 min |
| `3-off`, `3-auto` | OFF, AUTO, Iris + Complementary Reimagined | view, away | the effects under a pack (COMPATIBLE path) | 9 min each |
| `4-sil`, `4-nosil` | AUTO, DH, silhouettes on / off | far | silhouettes' frame cost, boxes, memory; the silhouette publication | 6 min each |

`JFR=0` records no JFR (a timing pair stays comparable either way: every run records the same). Logs: compress the log folder into the main checkout's `tools/fx-bench/logs/fx-bench-logs-<date>.tar.gz` (git-ignored, never staged); the review is `documentation/2026-11-10-custom-shader-system/FX_BENCH_REVIEW.md`.

## Recording (owner, 2026-11-10: "Record these animations")

`record.sh <worktree> <out dir> [auto|compatible|off]` builds the same scene and feed with the probe's `mode=fx`, attaches `FxRecorder.java` beside the probe, and flies the camera to each shot of `record.txt` (`name x y z yaw pitch seconds fps settle`), saving half-size frames, one full-size frame and `times.txt` per shot. Not a timing run, but it takes the fx lane lock, so no bench lane starts meanwhile. Then `python record_sheets.py <out dir>` (Pillow) writes a GIF and an eight-frame contact sheet per shot. Output goes to `tmp/fx-record/<run>/`. The first recording and its review: `documentation/2026-11-10-custom-shader-system/FX_VISUAL_REVIEW.md`.

## In `src/main`

`client/fx/FxStats` (counters and the F3 line) stays in the product. Nothing measurement-only is added to `src/main`, so there is no `detach.patch`: the probe is attached uncommitted for the runs only.

## History

- 2026-11-10: written for the W11 bench (FX_BENCH_PLAN.md); not run yet.
