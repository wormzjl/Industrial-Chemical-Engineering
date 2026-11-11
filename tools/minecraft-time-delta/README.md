# minecraft-time-delta

Purpose: the measurements behind `documentation/2026-11-11-minecraft-time-delta/` (`TIME_SCALE_REVIEW.md`, `HOLD_STUDY_REVIEW.md`):
the hold-fuzz quick check with simulated time running 72 times faster than online time (one Minecraft day = 24 h of
process time), and the study of every seed it held.

Batch: `2026-11-11-minecraft-time-delta`. Status: Concluded 2026-11-11 (evaluation only; nothing in `src/main` changed).

## Contents

| Path | What |
|---|---|
| `patches/probe-time-scale.patch` | The probe on `ff6f6892f`, every switch off by default (the default is bit-identical). Time: `IslandClock.seconds(ticks)` = ticks x `icheme.probe.timeScale` / 20, used by `IslandClock.Slice.seconds` and the three conversions of `IslandCertificate`; `PassiveIntervalSolver.Settings.defaults()` reads `icheme.probe.initialStep` (1 s) and `icheme.probe.maximumStep` (20 s). Study: `icheme.probe.failures` prints each failed pass Newton with its four largest rows (`PROBE_FAILURE`, the first ten then every `icheme.probe.failures.every`) and a non-finite Jacobian entry's row and column (`PROBE_NONFINITE`). Hypothesis switches: `icheme.probe.newtonTolerance` (1e-9), `icheme.probe.gate` (1e-8), `icheme.probe.failureCeiling` (accepted steps without growth after a Newton failure, 0), `icheme.probe.junctionRelative` (a junction's mixing rows over the larger of its holdup and the step's inflow). It does not scale the column cycle, the control cadence, the heat coupling, the electric stores or wear: the fuzz worlds hold none of them. |
| `probe.init.gradle` | Forwards the `-Pprobe...` and `-Pstudy...` switches to the test JVMs and attaches `probe/src`. |
| `run.sh` | `bash tools/minecraft-time-delta/run.sh <label> <seeds> "<dists>" [-P...]`: one Gradle run per distribution (`cold`, `bundled`, `defaults`) with the harnesses of `tools/unified-thermo-port-forms/`, reports in `tmp/time-scale/`. `THREADS=1` replays seeds alone. |
| `probe/src/TimeScaleStudyTest.java`, `study.sh` | `bash tools/minecraft-time-delta/study.sh <label> <bundled|cold> <seeds> [-P...]`: each seed alone after one warm-up seed, with the step-attempt trace, the stalls and a 50 ms stack sampler (leaf and inclusive frames); output `tmp/time-scale/study-<label>.txt`, probe lines in its `.gradle.txt`. |
| `results/summary.txt` | The `SUMMARY`, `COUNTERS` and `CLASS` lines and the held seeds of every run, then the study runs. |
| `results/study/` | Study outputs of the 5 s budget run, seed 47 and seed 164 (attempt lists cut to 60 lines), and the `PROBE_FAILURE` / `PROBE_NONFINITE` lines of the probe runs. |

## How to run

```bash
git apply tools/minecraft-time-delta/patches/probe-time-scale.patch
C="-PprobeTimeScale=72 -PprobeInitialStep=72 -PprobeMaximumStep=1440"
bash tools/minecraft-time-delta/run.sh b1  1-200 "bundled cold defaults"
bash tools/minecraft-time-delta/run.sh c72 1-200 "bundled cold defaults" $C
bash tools/minecraft-time-delta/run.sh t72 1-200 "bundled cold" $C -PprobeNewtonTolerance=1e-8 -PprobeGate=1e-7
bash tools/minecraft-time-delta/study.sh c72-5s bundled 15,47,77,123 $C -PprobeWallNanos=5000000000 -PprobeSoftNanos=3750000000
bash tools/minecraft-time-delta/study.sh f-bundled bundled 15,164,123 $C -PprobeFailures=true
git apply -R tools/minecraft-time-delta/patches/probe-time-scale.patch
```

Forms A and B of the review are `-PprobeTimeScale=72` alone and with `-PprobeMaximumStep=1440`. Run alone on the machine
(the wall-deadline holds are what the run measures), with no dev client.
