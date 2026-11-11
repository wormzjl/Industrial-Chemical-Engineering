# minecraft-time-delta

Purpose: the measurement behind `documentation/2026-11-11-minecraft-time-delta/TIME_SCALE_REVIEW.md`: the hold-fuzz
quick check with simulated time running 72 times faster than online time (one Minecraft day = 24 h of process time).

Batch: `2026-11-11-minecraft-time-delta`. Status: Concluded 2026-11-11 (evaluation only; nothing in `src/main` changed).

## Contents

| Path | What |
|---|---|
| `patches/probe-time-scale.patch` | The probe on `ff6f6892f`: `IslandClock.seconds(ticks)` = ticks x `icheme.probe.timeScale` / 20 (default 1, so the default is bit-identical), used by `IslandClock.Slice.seconds` and the three conversions of `IslandCertificate`; `PassiveIntervalSolver.Settings.defaults()` reads `icheme.probe.initialStep` (1 s) and `icheme.probe.maximumStep` (20 s). It does not scale the column cycle, the control cadence, the heat coupling, the electric stores or wear: the fuzz worlds hold none of them. |
| `probe.init.gradle` | Forwards `-PprobeTimeScale`, `-PprobeInitialStep`, `-PprobeMaximumStep` to the test JVMs. |
| `run.sh` | `bash tools/minecraft-time-delta/run.sh <label> <seeds> "<dists>" [-P...]`: one Gradle run per distribution (`cold`, `bundled`, `defaults`) with the harnesses of `tools/unified-thermo-port-forms/`, reports in `tmp/time-scale/`. `THREADS=1` replays seeds alone. |
| `results/` | The `SUMMARY`, `COUNTERS` and `CLASS` lines and the held seeds of every run. |

## How to run

```bash
git apply tools/minecraft-time-delta/patches/probe-time-scale.patch
bash tools/minecraft-time-delta/run.sh b1  1-200 "bundled cold defaults"
bash tools/minecraft-time-delta/run.sh a72 1-200 "bundled cold defaults" -PprobeTimeScale=72
bash tools/minecraft-time-delta/run.sh b72 1-200 "bundled cold defaults" -PprobeTimeScale=72 -PprobeMaximumStep=1440
bash tools/minecraft-time-delta/run.sh c72 1-200 "bundled cold defaults" -PprobeTimeScale=72 -PprobeInitialStep=72 -PprobeMaximumStep=1440
git apply -R tools/minecraft-time-delta/patches/probe-time-scale.patch
```

Run alone on the machine (the wall-deadline holds are what the run measures), with no dev client.
