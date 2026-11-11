# minecraft-time-delta

Purpose: the measurements behind `documentation/2026-11-11-minecraft-time-delta/` (`TIME_SCALE_REVIEW.md`, `HOLD_STUDY_REVIEW.md`, `OPTION_A_REVIEW.md`, `TIME_SCALE_BUILD_REVIEW.md`):
the hold-fuzz quick check with simulated time running 72 times faster than online time (one Minecraft day = 24 h of
process time), and the study of every seed it held.

Batch: `2026-11-11-minecraft-time-delta`. Status: In progress since 2026-11-11. The probe patch and `run.sh` measured the evaluation on `ff6f6892f`; `factor.sh` and `worlds/` measure the built time factor (W1-W6) and need no patch.

## Contents

| Path | What |
|---|---|
| `patches/probe-feed-step.patch` | Row and failure probe for the level loop's feed-step hold (FEED_STEP_HOLD_REVIEW.md), on `ca4de4521`. Off unless `-Dicheme.probe.failures=true`. Each failed pass Newton (step, pass, phases, its five worst rows, the vessels, the pipes) and each accepted or rejected step goes to a per-thread ring of 80. An interval that ends at the rejection limit prints the ring as `PROBE_DUMP`. Run: `git apply` it, then `LEVEL_LOOP_OPTS=-Dicheme.probe.failures=true bash tools/minecraft-time-delta/level-loop/run.sh 4`, then `git apply -R` it. `run.sh` passes `LEVEL_LOOP_OPTS` to the server JVM. |
| `patches/probe-time-scale.patch` | The probe on `ff6f6892f`, every switch off by default (the default is bit-identical). Time: `IslandClock.seconds(ticks)` = ticks x `icheme.probe.timeScale` / 20, used by `IslandClock.Slice.seconds` and the three conversions of `IslandCertificate`; `PassiveIntervalSolver.Settings.defaults()` reads `icheme.probe.initialStep` (1 s) and `icheme.probe.maximumStep` (20 s). Study: `icheme.probe.failures` prints each failed pass Newton with its four largest rows (`PROBE_FAILURE`, the first ten then every `icheme.probe.failures.every`) and a non-finite Jacobian entry's row and column (`PROBE_NONFINITE`). Hypothesis switches: `icheme.probe.newtonTolerance` (1e-9), `icheme.probe.gate` (1e-8), `icheme.probe.failureCeiling` (accepted steps without growth after a Newton failure, 0), `icheme.probe.junctionRelative` (a junction's mixing rows over the larger of its holdup and the step's inflow), `icheme.probe.gateDecides` with `icheme.probe.nearGate` (option A: a failed pass or polish Newton whose last iterate is within 1e-8 hands it on; `icheme.probe.countsOut` writes the failures and the near misses accepted), `icheme.probe.holdupTau` (`HOLDUP_TAU`, 0.05 s). The failure probe names a junction's row (mixing component, continuity, enthalpy, equilibrium) and the row the equation gate refuses (`PROBE_GATE`). It does not scale the column cycle, the control cadence, the heat coupling, the electric stores or wear: the fuzz worlds hold none of them. |
| `probe.init.gradle` | Forwards the `-Pprobe...` and `-Pstudy...` switches to the test JVMs and attaches `probe/src`. |
| `run.sh` | `bash tools/minecraft-time-delta/run.sh <label> <seeds> "<dists>" [-P...]`: one Gradle run per distribution (`cold`, `bundled`, `defaults`) with the harnesses of `tools/unified-thermo-port-forms/`, reports in `tmp/time-scale/`. `THREADS=1` replays seeds alone. |
| `probe/src/TimeScaleStudyTest.java`, `study.sh` | `bash tools/minecraft-time-delta/study.sh <label> <bundled|cold> <seeds> [-P...]`: each seed alone after one warm-up seed, with the step-attempt trace, the stalls and a 50 ms stack sampler (leaf and inclusive frames); output `tmp/time-scale/study-<label>.txt`, probe lines in its `.gradle.txt`. |
| `factor.sh` | `bash tools/minecraft-time-delta/factor.sh <label> <seeds> "<dists>" <factor> [-P...]`: the hold-fuzz run on the built factor (`-PholdFuzzTimeScale`), classified; reports `tmp/time-scale/<label>-<dist>-report.txt`; a label starting `campaign` also copies them under the merge lane's names to `tmp/time-scale/<label>/` for `tools/merge-lane/compare.mjs`. `THREADS=1` replays seeds alone. |
| `worlds/src/.../DrumDrainTest.java` | Check 2 of FEED_STEP_HOLD_REVIEW.md, at factor 1 with no server and no control. It builds the level loop's drum, feed and outlet line over `EventPathWorld`, with three cases: an open outlet from empty, an open outlet from 1 %, and a regulated outlet (592.8 kg/s). It prints `DRUM_DRAIN_RESULT` (held or clean) and asserts nothing. Run: `./gradlew --offline --no-configuration-cache --init-script tools/minecraft-time-delta/worlds/worlds.init.gradle test --tests '*DrumDrainTest'`. |
| `worlds/worlds.init.gradle`, `worlds/src/.../TimeScaleWorldsTest.java` | W6's headless worlds: `ColumnRunTest`'s running column and `ExchangerCouplingTest`'s water pair for 1 h of process time at 4 and 72 (the pair also at 1), through `EventPathWorld.time`; prints `COLUMN`, `HEAT` and the `TRACE` of the unequal-cadence pair at 72. |
| `level-loop/run.sh`, `level-loop/level-loop.init.gradle`, `level-loop/src/.../LevelLoopFactorRun.java`, `level-loop/src/.../LevelLoopStepProbe.java` | `bash tools/minecraft-time-delta/level-loop/run.sh <factor> [cycles]`: the GameTest `LevelLoopGameTests` (drum level loop) at another factor, alone on the review server (`-PreviewBatch=level-loop`): sets `fluid.timeScale` in `run/review-world/config/icheme-common.toml` for the run and restores the file; `cycles` goes in as `-Dicheme.levelLoop.cycles`. Log `tmp/time-scale/level-loop-<factor>x<cycles>.txt`. The same run also runs `LevelLoopStepProbe`: the plant without its ramp (an empty drum on the full feed, and one feed step at 25 %), at the rule feed and at the factor-4 feed; it logs `level_loop probe result` (held or clean, the setup's unbooked moles) and never fails. Switches: `-Dicheme.levelLoop.generatorVent=true` (the vent a 1 atm nitrogen generator), `-Dicheme.levelLoop.gasOutlet=true` (the outlet void charged as 1 atm nitrogen); it logs `level_loop probe nodes` (each node's pressure in the drum's first 12 accepted slices). |
| `results/summary.txt` | The `SUMMARY`, `COUNTERS` and `CLASS` lines and the held seeds of every run, then the study runs. |
| `results/feed-step-hold.txt` | The probed level-loop run at 4 behind FEED_STEP_HOLD_REVIEW.md: the `level_loop probe` result lines and the `PROBE_DUMP` rings. |
| `results/worlds.txt` | The `COLUMN`, `HEAT` and `TRACE` lines of the W6 headless worlds (TIME_SCALE_BUILD_REVIEW.md section 4). |
| `results/study/` | Study outputs of the 5 s budget run, seed 47 and seed 164 (attempt lists cut to 60 lines), and the `PROBE_FAILURE` / `PROBE_NONFINITE` lines of the probe runs. |

## How to run

```bash
git apply tools/minecraft-time-delta/patches/probe-time-scale.patch
C="-PprobeTimeScale=72 -PprobeInitialStep=72 -PprobeMaximumStep=1440"
bash tools/minecraft-time-delta/run.sh b1  1-200 "bundled cold defaults"
bash tools/minecraft-time-delta/run.sh c72 1-200 "bundled cold defaults" $C
bash tools/minecraft-time-delta/run.sh t72 1-200 "bundled cold" $C -PprobeNewtonTolerance=1e-8 -PprobeGate=1e-7
bash tools/minecraft-time-delta/run.sh a1x  1-200 "bundled cold defaults" -PprobeGateDecides=true
bash tools/minecraft-time-delta/run.sh a72x 1-200 "bundled cold defaults" $C -PprobeGateDecides=true
bash tools/minecraft-time-delta/run.sh h72  1-200 "bundled cold defaults" $C -PprobeHoldupTau=3.6
bash tools/minecraft-time-delta/study.sh c72-5s bundled 15,47,77,123 $C -PprobeWallNanos=5000000000 -PprobeSoftNanos=3750000000
bash tools/minecraft-time-delta/study.sh f-bundled bundled 15,164,123 $C -PprobeFailures=true
git apply -R tools/minecraft-time-delta/patches/probe-time-scale.patch
```

On the built factor (no patch):

```bash
bash tools/minecraft-time-delta/factor.sh q4 1-200 "bundled cold defaults" 4
bash tools/minecraft-time-delta/factor.sh campaign4 1-1000 "cold bundled defaults" 4
node tools/merge-lane/compare.mjs tmp/merge-lane/head tmp/time-scale/campaign4
THREADS=1 bash tools/minecraft-time-delta/factor.sh replay4 47,118,678 bundled 4
./gradlew --offline --no-configuration-cache --init-script tools/minecraft-time-delta/worlds/worlds.init.gradle test --tests '*TimeScaleWorldsTest'
```

Forms A and B of the review are `-PprobeTimeScale=72` alone and with `-PprobeMaximumStep=1440`. Run alone on the machine
(the wall-deadline holds are what the run measures), with no dev client.

## Entry points

- `run.sh` and `study.sh` (with `patches/probe-time-scale.patch` applied), `factor.sh`, `level-loop/run.sh`; the JUnit rigs under `worlds/` through `worlds/worlds.init.gradle`.
- In `src/`: the gates these runs reuse are `LevelLoopGameTests` (fluid GameTests; the gates call `run(helper, cycles, blanket, direct)` with fixed arguments, and `level-loop/src/.../LevelLoopFactorRun.java` reads the switches `icheme.levelLoop.cycles`, `.blanket` and `.direct` that `level-loop/run.sh` sets) and the hold-fuzz harnesses of `tools/unified-thermo-port-forms/` (`-PholdFuzzTimeScale`). Nothing of this folder is in a gate.

## History

- 2026-11-11: evaluation on `ff6f6892f` with `patches/probe-time-scale.patch` (applied and reverted), `run.sh`, `study.sh`; TIME_SCALE_REVIEW.md, HOLD_STUDY_REVIEW.md, OPTION_A_REVIEW.md.
- 2026-11-11, W6: `factor.sh` on the built factor, the campaign at 4, `worlds/` (`TimeScaleWorldsTest`, then `DrumDrainTest`), `level-loop/` (`LevelLoopFactorRun` at 72, `LevelLoopStepProbe` at 1 and 4); TIME_SCALE_BUILD_REVIEW.md.
- 2026-11-11: `patches/probe-feed-step.patch` for FEED_STEP_HOLD_REVIEW.md; `results/feed-step-hold.txt`.
- 2026-11-11 (batch `2026-11-11-drum-operation-holds`): `LevelLoopStepProbe` switches `generatorVent` and `gasOutlet` for the gas-vent hold (GAS_VENT_HOLD_REVIEW.md, GAS_VENT_REVIEW.md).
- 2026-11-11 (batch `2026-11-11-gas-blanketing`): `level-loop/run.sh` takes `LEVEL_LOOP_TAG` and the rig's switches `-Dicheme.levelLoop.blanket=true` and `-Dicheme.levelLoop.direct=true` (GAS_BLANKET_REVIEW.md).
- 2026-11-11, merge preparation: checked; no code left `src/`. Run reports stay in the worktree's git-ignored `tmp/time-scale/`; their summary lines are in `results/`.
- 2026-11-11, merge preparation: the level loop's switches leave `src/`. `LevelLoopGameTests.run` takes the cycle count as an argument (the gates: 2, plain and blanketed); `LevelLoopFactorRun` reads `icheme.levelLoop.cycles`, `.blanket` and `.direct`, and `LevelLoopStepProbe` reads `icheme.levelLoop.cycles` for its rule feed. `run.sh` and the init script are unchanged.
- 2026-11-11 (batch `2026-11-11-valve-followups`, item 5): the hold-fuzz harnesses default to factor 4 (`SimulationTime.DEFAULT_FACTOR`); `factor.sh` still passes its factor. The W6 campaign at 4 (`tmp/time-scale/campaign4/`, clean 991 / 990 / 1000, TIME_SCALE_BUILD_REVIEW.md section 3) is archived in the main checkout as `tools/minecraft-time-delta/logs/time-scale-campaign4-logs-2026-11-11.tar.gz` (local, never staged): the campaign baseline of the next final lane. It predates the `factor=` field, so `compare.mjs` reads its factor as not recorded.
