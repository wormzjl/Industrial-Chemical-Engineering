# floating-roof-tanks

Purpose: the WP5 solver-impact measurement of batch `documentation/2026-10-21-floating-roof-tanks/` (plan
`FLOATING_ROOF_TANKS_PLAN.md` sections 3.1, 4, 6 WP5; review `OPEN_TANK_QUICK_CHECK_REVIEW.md`), branch
`claude/floating-roof-tanks-81d68b`, commits `cb54c5d3` (WP1 science) and `3178bada` (WP2 runtime) on main `ef64bd06`
(0.11.2). Nothing here is tracked or was removed from a tracked path; the product code was not changed.

## Contents

| Path | What |
|---|---|
| `hold-fuzz-bundled/src/HoldFuzzTest.java` | The bundled hold-fuzz harness with the option `holdFuzz.openTanks=true`: about half the one-block tanks (a SplitMix hash of the seed and the tank's position, no random draw) are registered open (`FluidDeviceSpec` 7-arg, `open=true`, volume 1 m3 and level height as before); the layout labels them `otank`. Option off = the old harness's placements exactly. Compiles on `3178bada` and later only (`FluidDeviceSpec.open`). |
| `patches/hold-fuzz-open-tanks.patch` | The option as a patch against `tools/unified-thermo-port-forms/hold-fuzz-bundled/src/HoldFuzzTest.java` as of 2026-10-21 (md5 `49cabc796581a6fc2226be4323f97999`; the harness is not tracked). |
| `hold-fuzz-bundled/hold-fuzz.init.gradle` | The Gradle init script of that folder, plus `-PholdFuzzOpenTanks=true`, for a Gradle run through `tools/unified-thermo-port-m3/fuzz.sh` (`HARNESS=<this folder>/hold-fuzz-bundled`). The form A switch `-PopenTankPostStepVent` was removed with form A (`796da897`). Since 2026-10-21 a seed that opens a tank feeds its crude generators methane-free (owner: no volatile feeds into open tanks). |
| `quick-lane.sh` | The Gradle-free 200-seed quick check (from `tools/fluid-solver-simplification/quick-lane.sh`), bundled distributions only; `WT` = the lane worktree (classpath `build/moddev`, `git archive`), `ORIG=1` the unpatched harness (for `ef64bd06`), `HARNESS=` another copy, `EXTRA_OPTS` system properties, `THREADS` (4). |
| `probe/HoldFuzzTest.java`, `patches/probe-dead-crude.patch` | Trace probe: `holdFuzz.deadCrude=true` takes the methane out of the crude feed (no draw changes). Used on seeds 4, 12, 26 only. |
| `logs/quick-2026-10-21.tar.gz` | `/tmp/frt-logs` and the run transcripts (below). |

## How to run (Git Bash, from the session worktree root; no dev client running; second lane = 4 threads)

```
M=D:/Minecraft/Modding/1.21/CreateChemE/tools; L=$M/unified-thermo-port-m5/d40/lane.sh; Q=$M/floating-roof-tanks/quick-lane.sh
export WT=$(pwd -W) OUT=/tmp/frt-logs
bash $L export frt-base ef64bd06; bash $L export frt-head 3178bada
ORIG=1 bash $Q frt-base base-off                     # main, old harness
bash $Q frt-head head-off                            # option off: must equal base-off seed by seed
EXTRA_OPTS=-DholdFuzz.openTanks=true bash $Q frt-head head-onB
EXTRA_OPTS="-DholdFuzz.openTanks=true -Dicheme.openTank.postStepVent=true" bash $Q frt-head head-onA
bash $Q frt-head clean-onB 1-3,5-11,13-25,27-156,158-200 bundled-random   # counters on the seeds clean in OFF, B and A
```
Compare reports with `ms=` and `SUMMARY` stripped. Seed 157 (class F, wall deadline) moves the whole-run counters with load;
the gate is read on the other 199 seeds (`*-x157-*`). Single-seed traces: `lane.sh run frt-head
com.wormzjl.icheme.runtime.fluid.HoldFuzzTest` with `-DholdFuzz.seeds=4 -DholdFuzz.threads=1 -DholdFuzz.verbose=true`.

## Logs (`logs/quick-2026-10-21.tar.gz`, extract with `tar --force-local -xzf`)

`frt-logs/<label>-<dist>-report.txt` (+ `.classes.txt`, `-run.txt`, `-compile.txt`): `base-off` (ef64bd06), `head-off`,
`head-onB`, `head-onA`; `base-off-x157`, `head-off-x157` (gate without seed 157); `clean-off`, `clean-onB`, `clean-onA`
(196 seeds clean in all three); `dead-onB`, `dead-onA` (seeds 4, 12, 26, methane-free crude); `trace-4A*`, `trace-12B*`,
`trace-4A-dead*`, `trace-4B-dead*` (verbose single seeds). `frt-base-off.out`, `frt-head.out`: the run transcripts.

## Campaign (2026-10-21, `796da897`, form B; review `OPEN_TANK_CAMPAIGN_REVIEW.md`)

The one 1000-seed campaign of WP5 on the Gradle-free javac lane (exports `/tmp/d40-frtc-*` of the session worktree; the
worktree is only read): cold random (`unified-thermo-port-forms/hold-fuzz-cold`, `holdFuzz.cold=true`), bundled random and
defaults (this folder's harness, `holdFuzz.openTanks=true`), 8 workers, counters on, compared with fl8 (`c7b883cb`,
`tools/new-units/logs/final-lane-c7b883cb-2026-10-21.tar.gz`).

| Path | What |
|---|---|
| `campaign/scripts/campaign.sh` | The campaign on exports `frtc-cold`, `frtc-bund` (`SEEDS`, `THREADS`, `PREFIX`, `DISTS`, `BNAME`, `BEXTRA`); reports in `/tmp/frt-camp/logs`, classified by `phase-ports-probes/hold-fuzz/classify.sh` |
| `campaign/scripts/wait-idle.sh`, `idle.ps1` | Waits for three idle checks a minute apart (no test executor, harness run, dev client, `GradleWrapperMain` or geosim job; load < 30 %); idle Gradle daemons are ignored |
| `campaign/scripts/alone.sh` | The one alone batch: new C/F holds, one worker (cold 118, 183, 833; bundled 6) |
| `campaign/scripts/split.pl` | `split.pl <label> <base report> <new report>`: clean counts and new holds split by open tank (`otank` in the layout); seeds without one compared line by line; `BOTHCLEAN=<prefix>` writes the clean-in-both seed lists |
| `campaign/trace/trace-describe.patch` | Trace-only change to this folder's harness: `describe` of an island merged away prints the current islands instead of throwing (the verbose run of 354/895 threw `Unknown island`) |
| `campaign/trace/M5MinimizerTest.java`, `M5SolveLogTest.java`, `M5TraceWorld.java` | `unified-thermo-port-m5/trace/src` moved to `com.wormzjl.icheme`, with `HoldFuzzTest.openTanks(seed, ...)` applied to the seed's placements; compile with the patched harness on the d40 lane |

Run (Git Bash; the machine idle; exports first, `WT` = the session worktree):
```
L=tools/unified-thermo-port-m5/d40/lane.sh; bash $L export frtc-cold 796da897; bash $L export frtc-bund 796da897
bash $L compile frtc-cold <forms>/hold-fuzz-cold/src/HoldFuzzTest.java; bash $L compile frtc-bund <this>/hold-fuzz-bundled/src/HoldFuzzTest.java
bash campaign/scripts/wait-idle.sh campaign/scripts/campaign.sh      # scripts expect copies in /tmp/frt-camp/scripts
JAVA_OPTS="-DholdFuzz.openTanks=true -Dminimize.seed=895 -Dminimize.threads=4" bash $L run frtc-trace com.wormzjl.icheme.runtime.fluid.M5MinimizerTest
JAVA_OPTS="-DholdFuzz.openTanks=true -Dreplay.seed=895 -Dreplay.keep=<KEEP> -Dreplay.slices=3 -Dreplay.island=12" bash $L run frtc-trace 'com.wormzjl.icheme.runtime.fluid.M5SolveLogTest#replay'
```
Logs `logs/campaign-logs-2026-10-21.tar.gz` (`logs/`): `campaign-*` (the campaign and `campaign-stdout.txt`), `alone-*`
(the alone batch), `cc-{base,head}-{closed,open}-*` (counters on the seeds clean in both, `ef64bd06` with the forms harness
and `796da897`), `bothclean-random-*.txt`, `trace-*`, `trace2-*` (verbose 354, 598, 678, 895), `minimize-598.txt`,
`minimize-895.txt` (KEEP lists), `trace-895-min.txt` (the minimized 895 replayed, island 12's network before each solve).

## Harness change after the campaign (2026-10-21, reverted)

For a few hours open-tank seeds fed C1-C4-free crude capped at 320 K. The owner then put boiling feeds that cool
themselves by venting in design ("yeah", 2026-10-21): the harness again feeds the original live crude and water at their
drawn temperatures, and holds such as 598 and 678 are defects (fixed by the release rates, `9a035cde`, `44f836e0`).

## Trace of seeds 354, 678, 598, 12 and the finite release rate prototype (2026-10-21, review `OPEN_TANK_354_678_TRACE.md`)

Run at `86776654` on the reverted harness (the 2026-10-21 owner widening: open-tank seeds again feed live crude and water at
their drawn temperatures; the "Harness change after the campaign" section above no longer applies). Nothing here is
tracked; the product code was not changed.

| Path | What |
|---|---|
| `trace-354-678/harness-describe.patch` | Trace-only fix of `campaign/trace/trace-describe.patch` for the reverted harness (`patch HoldFuzzTest.java harness-describe.patch`): the island-gone guard at the start of `describe` (the old hunk lands mid-method on this harness) |
| `trace-354-678/release-share-86776654.patch` | Prototype of option A: an open vessel releases the share `min(1, dt/tau)` of its start gas and of its liquid above the shell top over a step (`OpenVessel.RELEASE_TIME`, `-Dicheme.openTank.releaseTime`, default 1 s); the vent's capacity and `drainedSeeds` read the same share |
| `logs/trace-354-678-logs-2026-10-21.tar.gz` | `trace-354-678-logs/logs/`: `repro-*` (verbose 354/598/678/895 at `86776654`), `minimize-598.txt`, `minimize-678.txt` (KEEP lists), `trace-598.txt`, `trace-678.txt` (minimized replays with `trace-895/trace-dump-796da897.patch`), `closed-*-min.txt` (tanks closed), `proto1-*`, `proto5-*` (prototype at tau 1 s and 5 s); `quick/` and `quick-*.out` (200-seed quick checks: `base`, `proto` tau 1 s, `proto5` tau 5 s) |

Run (Git Bash; `L` = `tools/unified-thermo-port-m5/d40/lane.sh`, `WT` = the session worktree; `H` = a copy of
`hold-fuzz-bundled/src/HoldFuzzTest.java` patched with `harness-describe.patch`, beside the `campaign/trace` sources):
```
bash $L export t354d 86776654 trace-895/trace-dump-796da897.patch; bash $L compile t354d $H/HoldFuzzTest.java $H/M5*.java
JAVA_OPTS="-DholdFuzz.openTanks=true -Dminimize.seed=598 -Dminimize.threads=4" bash $L run t354d com.wormzjl.icheme.runtime.fluid.M5MinimizerTest
JAVA_OPTS="-Dt895.dump=true -DholdFuzz.openTanks=true -Dreplay.seed=598 -Dreplay.keep=1@252,5@252,7@252,8@252,10@252,11@252,13@252 -Dreplay.slices=18 -Dreplay.island=8" bash $L run t354d 'com.wormzjl.icheme.runtime.fluid.M5SolveLogTest#replay'
#   678: -Dreplay.keep=0@335,1@335,2@335,3@335,4@335,5@335,8@335,10@335,15@335,16@335,17@335,18@335,19@335,20@553,21@610,22@610 -Dreplay.slices=12 -Dreplay.island=19
bash $L export t354p 86776654 trace-354-678/release-share-86776654.patch
EXTRA_OPTS="-DholdFuzz.openTanks=true -Dicheme.openTank.releaseTime=1" HARNESS=$H/HoldFuzzTest.java THREADS=4 OUT=/tmp/t354/quick bash quick-lane.sh t354p proto
```

### nozzle-room/ (nozzle roots follow the pipe, 2026-10-21)

| File | What |
|---|---|
| `nozzle-room/NozzleRoomProbeTest.java` | Probe: per kind and role, the least room a nozzle's neck has (`NozzleRoom.room`) and how many gated sizes fail `NozzleRoom.clear` at `maxOd`. Drop it into `src/test/java/com/wormzjl/icheme/science/equipment/` beside the tracked `NozzleRoom.java` and run `./gradlew test --tests '*NozzleRoomProbeTest' -i \| grep NOZZLE-ROOM`. Never tracked. |
| `nozzle-room/room-baseline-4b842f30.txt` | The probe at `4b842f30` (before the roots followed the pipe), `SURFACE` 0.1 m: the baseline the gate's `MET_BEFORE` list comes from. |
| `nozzle-room/room-after.txt` | The probe on the working tree after the rooms (no row worse; fixed-bed gasifier STEAM 8 to 0). |
| `nozzle-room/NozzleClashDiagTest.java` | Diagnostic (2026-10-21, phase 2b): for every neck that fails `NozzleRoom.clear` at a pipe's largest, names the part the neck first meets and how deep. Needs the hooks in `debug-hooks-wip.diff`. Never tracked. |
| `nozzle-room/debug-hooks-wip.diff` | The interrupted phase-2b working diff of `ProcessUnit.java` and `ShapeRasterizer.java` at `f658697c` (2026-10-25), with the temporary `DEBUG-NOZZLE-ROOM` hooks (`ProcessUnit.ORIGIN`/`what`, `ShapeRasterizer.what`). Re-attach only the hook hunks: the rest landed in `a590f592`. The hooks keep every solid with a stack-trace string in a static map: run the diagnostic alone, never the suite (the suite ran out of heap with them). |

## Code consolidation (2026-10-25, review `documentation/2026-10-21-floating-roof-tanks/CONSOLIDATION_REVIEW.md`)

Commits `7b3137bf`, `2d3593c8`, `38825518`, `36fb0965`, `65886b15` on `61343f3d`. Nothing was detached from a tracked
path (every removed class was folded into its owner, every removed test class merged into another); the files here
are the measurement only.

| Path | What |
|---|---|
| `consolidation/TmpGeometryDigestTest.java` | The geometry digest probe (never tracked): one hash over every valve, sight-glass, inline-mover, filter and pressure-valve mesh on 864 pipe keys, the tank gauge, the instrument housing, needles, connectors and sockets, the bubbles and the movers' sound gains. This copy compiles on `38825518` and later (`ValveGeometry`, `InstrumentGeometry.sockets`, `SightGlassGeometry.bubbles`, `UnitDesign.soundGain`); the "before" run used the same body with the pre-fold names (`CheckValveGeometry`, `ShutoffValveGeometry`, `PressureValveGeometry`, `InstrumentSockets`, `SightGlassBubbles`, `MoverSoundVolume`) on `2d3593c8`. |
| `consolidation/geometry-digest.txt` | The two digests, before and after: `864 keys digest 4a19ecc3855c29f7` both. |
| `logs/consolidation-logs-2026-10-25.tar.gz` | The baseline `test slowGates` transcript at `61343f3d` and the final one at `65886b15`, with the final JUnit XML counts. |

Run: copy the probe to `src/test/java/com/wormzjl/icheme/science/equipment/`, then (no dev client running)
`./gradlew test --tests com.wormzjl.icheme.science.equipment.TmpGeometryDigestTest`; the digest is appended to
`build/tmp/geometry-digest.txt`. Delete the probe before committing.

## Final lane (2026-10-26, `e8ec6455`, review `documentation/2026-10-21-floating-roof-tanks/FINAL_LANE_E8EC6455_REVIEW.md`)
- `final-lane/`: the m23 lane scripts (`tools/new-units/od4-holds/scripts-2026-10-21/final-lane/`) pointed at worktree `rename-ichem-mod-30e50c`.
  - `lane.sh`: its test step runs `test slowGates`.
  - `gradle-locked.sh`: refuses to start while a dev client runs.
  - `fuzz.sh`: the campaign harness from `tools/unified-thermo-port-forms/hold-fuzz-*`.
  - `printed.sh <results dir> <out>`: every test's printed lines, with the class name in front and the package dropped, so pre- and post-rename runs pair.
  - `pairs.pl A B`: for each masked line key, the first differing pair of lines.
  - The comparisons used `/d/tmp_fl/scripts/cmp-seeds.pl` and `/d/tmp_285/scripts/maxrel.pl` (stored copies under `tools/new-units/od4-holds/scripts-2026-10-21/`).
- Run from the worktree root, alone: `LOGS=build/frt-lane RUNID=frt bash <this folder>/final-lane/lane.sh <sha>`. Edit `WT` in the three scripts for another worktree.
- `logs/final-lane-e8ec6455-2026-10-26.tar.gz`:
  - the lane logs and campaign reports;
  - the GameTest diagnosis runs `gt2`..`gt5` (before and after the generator and chunk-load fixes);
  - the printed lines of fl8 and of this run, and their pairs and `maxrel` output.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: WP5 solver-impact measurement of the open vessel (floating-roof tank): the bundled hold-fuzz harness with `holdFuzz.openTanks=true` (about half the one-block tanks open, by a hash of seed and position, no random draw; option off = old placements), its patch against `unified-thermo-port-forms/hold-fuzz-bundled` (2026-10-21), a Gradle init script forwarding `-PholdFuzzOpenTanks` (open-tank seeds feed methane-free crude), the bundled Gradle-free `quick-lane.sh`, the dead-crude trace probe and `logs/quick-2026-10-21.tar.gz` (bitwise gate `ef64bd06` vs `3178bada`, forms B and A, clean-in-all counters, seeds 4/12/26 traces), `campaign/` (the 1000-seed campaign scripts, the alone batch, the open-tank split comparison and the minimizer/solve-log trace adapted to the open-tank harness), `trace-895/` (seed 895 trace patch, the hold-then-vent prototype patch and its probe test), `trace-354-678/` (describe fix for the reverted harness, the finite release rate prototype patch) `logs/campaign-logs-2026-10-21.tar.gz` and `skirt-render/` (2026-10-25: software renders of the reactor skirts before and after `36e0f9fb`, `UnitRender` ported to the `icheme` package with a DRUM case, the boot-drop table for `bae8620a`) and `ladder-probe/` (2026-10-25: the ladder step-off probe and its trace helpers, the rolling ladder renders before and after `68af29c2`, `logs/ladder-probe-logs-2026-10-25.tar.gz`; gates `ProcessUnitTest.aPlayerClimbsEveryLadderAndGetsOffAtItsLanding` and `FloatingRoofsTest.aPlayerWalksTheRollingLadderBetweenTheRoofAndThePlatform` replace it) and `consolidation/` (2026-10-25: the code consolidation's geometry digest probe, digest `4a19ecc3855c29f7` before and after; `logs/consolidation-logs-2026-10-25.tar.gz`: baseline and final `test slowGates`; nothing detached from a tracked path) ; `final-lane/` scripts and `logs/final-lane-e8ec6455-2026-10-26.tar.gz` (2026-10-26)

Batch: 2026-10-21-floating-roof-tanks

Status: Concluded 2026-10-26 (batch in 0.12.0). Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `quick-lane.sh`, `trace-895/`, `trace-354-678/`, `hold-fuzz-bundled/`, `campaign/scripts/campaign.sh`, `skirt-render/`, `ladder-probe/`, `consolidation/`

### History

- In progress since 2026-10-21 (quick check done 2026-10-21 on `3178bada`: gate green, form B recommended
- review `OPEN_TANK_QUICK_CHECK_REVIEW.md`
- campaign done 2026-10-21 on `796da897` against fl8 `c7b883cb`: no new C/F hold alone, new holds 354, 598, 678, 895 in open-tank seeds, 895 an in-design gas-only open tank hold
- review `OPEN_TANK_CAMPAIGN_REVIEW.md`
- seed 895 traced and the owner's hold-then-vent rule prototyped 2026-10-21 on `4c15e508`: 895 clean, quick check 200/200 both distributions, 354 and 678 another mechanism
- review `OPEN_TANK_895_TRACE.md`, `trace-895/`, `logs/trace-895-logs-2026-10-21.tar.gz`
- seeds 354, 678, 598, 12 traced 2026-10-21 on `86776654` with the reverted harness: one mechanism, the release of all start excess per step (rate excess/dt), finite release rate prototyped, quick check 200/200
- review `OPEN_TANK_354_678_TRACE.md`, `trace-354-678/`, `logs/trace-354-678-logs-2026-10-21.tar.gz`)
- nothing tracked

### Notes

- status corrected 2026-10-26: the batch reached `main`; the folder is kept as its evidence.

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 55 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/floating-roof-tanks/`, at the same relative paths.
