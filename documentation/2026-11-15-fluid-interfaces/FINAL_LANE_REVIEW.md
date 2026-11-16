# Fluid interfaces and equipment visual design: final lane review

## Summary

The final lane ran once on `claude/fluid-interfaces-lane`, 2026-11-16. That branch is cut from
`claude/fluid-interfaces-batch` at `b6e61f7ad`: the two batches joined, with `main` at 0.31.1 merged in.

Code it ran on:
- The test step ran at `3e9a8484f`.
- The steps after it ran at `5e306724b`.
- Between the two, one test-only commit and one lang-text commit, both below.

Commits on the lane branch:
- `369728ef7`: the translation commit.
- `3e9a8484f`: the alarm beacon's model rework, which the owner asked for before the merge.
- `ecc4d49e9`: the PreparedRefillTest pin fix.
- `5e306724b`: the three tutorial lines.
- The level loop's pacing (owner: "A: pace the test"), with this review's update: the commit that holds this line.

Output is in the worktree's `tmp/merge-lane/head/`. The baseline is the 0.31.1 lane (`tmp/merge-lane/head` of the
worktree `batch-still-models-b3de99`; its review is `documentation/2026-11-15-fluid-step-cost/MERGE_REVIEW.md`).
Counters only, no times.

**The lane's shell died.** `lane.sh head` died with exit 255 during the test step, before printing the step's results.
Its Gradle run (pid 30780) went on to the end. The step's tail was then run by hand with the lane's own commands
(`tmp/fluid-interfaces-lane/after-test.sh`):
- copy the results;
- the counts;
- `printed.sh`;
- the 33 junction lines;
- `affected.mjs save`.

The remaining steps ran as `lane.sh head regression gametest campaign`. That completes this one lane; it is not a
second lane.

### Results

- **test:** 3,649 tests, 5 failures, 0 errors.
  - The selection took 382 of 476 `test` classes, all 10 `holdGates`, 63 of 87 `columnGates` and 28 of 31
    `equipmentGates` classes.
  - The `test` filters passed 8,000 characters, so `test` ran whole (the lane's rule).
  - `columnGates` ran 62 classes (379 tests): the selected `NativeColumnSolverTest` is excluded from that task (0.31.1
    finding 1). The task did not fail this time, because other classes ran with it.
  - `equipmentGates`: 28 classes, 258 tests.
  - The 5 failures are below. Both causes are fixed, and the failing classes ran again alone.
- **regression:** chain-100 exact, every deviation 0. No re-record.
- **gametest:** 101 of 102. One failure, `LevelLoopGameTests.aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence`: the
  test did not pace the unthrottled server (section below). The four new GameTest batches (`alarm-beacon`, `crude-oil`,
  `gas-intake`, `liquid-hopper`) pass. After the owner's choice, the test paces the server; the three level loop tests
  then ran once and all pass. The other 99 GameTests were not run again: their pass in the lane stands.
- **Junction lines:** 33 of 33 identical, floats included.
- **`MIXED_GAS_COST`:** 313 Newton solves before and after. Allocated memory 125.1 to 137.1 MB: the mole arrays carry
  52 entries instead of 47.
- **Campaign** (factor 4, 1000 seeds, 8 threads):

| run | clean, base → head (lane) | after replays | new holds | fixed holds | class moved |
|---|---|---|---|---|---|
| cold random | 991 → 992 | 992 | none | 826 F | 0 |
| bundled random | 993 → 991 | 993 | 12 F, 59 F | none | 0 |
| bundled defaults | 1000 → 1000 | 1000 | none | none | 0 |

- **Campaign replays:**
  - The two new F holds (wall deadline) were replayed once, together, on one thread, with no other JVM running
    (`replay-bundled-12-59-report.txt`). Both are clean.
  - These are the two seeds the 0.31.1 lane listed as fixed. They move between lanes with the machine's load: this
    lane's campaign shared the machine with another batch's test JVMs (the lane printed `WARNING: 1` and `2 test or
    client JVMs running`).
  - Refusals 0 on every run.
- **Campaign Newton counters** (the held and fixed seeds change how far their runs get, so the sums are not like for
  like):
  - cold: solves 326,233 to 326,093, iterations 2,022,572 to 2,022,447;
  - bundled: solves 131,194 to 131,818, iterations 637,072 to 644,714;
  - defaults: solves 27,002 and iterations 65,433, identical.
- **References:**
  - No asserted reference moved besides the stale pins below.
  - Conservation is exact: every ledger and conservation assertion passed. The failed GameTest's residuals are
    4.9e-10 mol of 1.59e7 mol.
- **Printed lines:**
  - With times, timestamps and worktree paths left out (`tmp/fluid-interfaces-lane/printed-diff.js`), 8,729 lines are
    identical and 149 differ. 21 classes print only in the head lane: new or newly selected tests.
  - Every differing line follows from a change named below.
- **Translations:** `369728ef7` and `5e306724b`.
  - `check.js`: OK, 4,604 keys in each file.
  - `status-words.js`: reports only lines that stood before.
  - LanguageFilesTest 2/2, UnitTextTest 1/1, TutorialTextTest 6/6.
- **Logs:** `tools/merge-lane/logs/fluid-interfaces-final-lane-logs-2026-11-16.tar.gz` in the main checkout
  (git-ignored).
- **Status:** clean. The level loop failure is answered by the owner's choice A (the test paces the server); the
  three level loop tests pass on their one run.

## The five test failures, and their fixes

| Class | Failure | Cause | Fix |
|---|---|---|---|
| `PreparedRefillTest` (3 tests) | `RootAbsent`: "a liquid of a node with a vapour has only a vapour-like root" at 330 and 345 K, 2 bar | A positional pin of the world list. The test's liquid took the compounds at `n-2-c`, the last ones before water. W1 appended the five rare gases there, so its "liquid" became xenon, krypton and helium, which are supercritical. | `ecc4d49e9`, test only. The liquid skips the five rare gases (`RARE_GASES = 5`) and runs the same compounds as before: ethanol, carbon dioxide, ethyl mercaptan. Alone: 3/3. Accepted by the orchestrator. |
| `TutorialTextTest` (2 tests) | Item lines over 14 words or not one sentence: `alarm_beacon.serves`, `crude_oil_bucket.serves`. A tour stop of two sentences: `tour.alarm_beacon.settings.7`. | English lines written in W3 and W9 that break the test's text rules. W9 did not run this class (the builder's miss). | `5e306724b`, lang text only, with the orchestrator's OK. The beacon's wording is the orchestrator's. Four translations follow. Alone: TutorialTextTest 6/6, LanguageFilesTest 2/2, UnitTextTest 1/1 (the load map names UnitTextTest for the lang files). |

## The GameTest failure, and the owner's choice

`LevelLoopGameTests.aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence` failed in the lane: "The drum emptied or
overflowed (1 % to 99 %): 23.35 % to 100.0 %". The mechanism below is the orchestrator's, read from the lane log and the
code. It corrects this review's first reading, which counted two held cycles and blamed the start phase alone.

**Expected behaviour.** The drum holds two control cycles of its feed. After the +50 % feed step, the level loop
decides on a fresh reading at each cadence (100 online ticks) and opens its valve within about a cadence. On a 20 TPS
server a slice's solve takes well under one tick, so every decision reads a level at most a slice old.

**What happened.**
- `Sensor.read` gives a reading the tick of the view's `committedTick`. `ControlLaw.level` holds its output when the
  reading's tick equals the last sample's tick.
- The GameTest server ran about 3,000 ticks a second: cycles 2 to 30 share one log second. A solve of about 15 ms then
  spans about 45 online ticks (lag 45 at cycle 17, 72 at cycle 19).
- After the step, no slice committed between the control ticks at online 30500 and 30600. The new state came about 2
  ticks after 30600. That one decision held: cycles 17 and 18 print the same decision. The fresh level waited 98 ticks.
- The next output acted about 2.6 cadences after the surplus began, but the drum has room for 2.0. The drum filled
  (3.85 bar, venting); the loop then recovered to 50.00 %.
- `pace()` throttled only above `CADENCE + LAG` = 110 ticks behind, so lags of 45 to 84 passed unthrottled. The 0.31.1
  run had the same lags and passed by its phase.

**The fix.** The owner chose "A: pace the test": a test-only change, with no control-law change.
- `LevelLoopGameTests.pace` now waits while the drum island's slice has been in flight more than `LAG` = 10 online ticks,
  as a 20 TPS server with a quick worker would.
- In flight means the drum view's status is `SOLVING`. `IslandCoordinator` sets it at admission and replaces it only
  when the server drains the completion (`closeRound`), so the view shows it for the whole flight.
- A new slice (another committed tick) starts its own count, so a backlog drains one slice per `LAG` ticks at most.
- The result line counts the decisions that held (`heldDecisions`), so each run proves from its own record whether one
  held.
- Nothing in `src/main` changed. `tools/minecraft-time-delta/level-loop/` calls the test's own `run` and `pace`, so it
  needed no change.

**The bound.** Once a solve is at most W ticks, the surplus runs at most S + W + 99 ticks before the first correction
(S the slice):
- S = 43 and W = 12 give 154 ticks, about 83 % level;
- S = 70 gives 181 ticks, about 90 %.

**The run.** One focused run of the three level loop tests (`runFluidGameTestServer` with
`tools/simulator-sizing/focused-gametests.init.gradle`, run id `level-loop-paced-1`): all 3 pass.

| Test | Lane (old pacing) | Paced |
|---|---|---|
| vented drum (`aDrumOfTwoCycles…`) | low 24.50 %, high 89.25 %, at step 50.00 %, at end 50.00 %, deadMax 72, largest lag after the step 16 | low 25.09 %, high 61.08 %, at step 50.00 %, at end 50.00 %, deadMax 45, heldDecisions 0, largest lag after the step 12 |
| blanketed drum (`aBlanketedDrumOfTwoCycles…`) | low 23.35 %, **high 100.0 %**, at step 50.00 %, at end 50.00 %, deadMax 64, largest lag after the step 72; failed | low 25.00 %, high 65.68 %, at step 50.00 %, at end 50.00 %, deadMax 57, heldDecisions 0, largest lag after the step 13 |
| blanketed drum from empty (`aBlanketedDrumStartedEmpty…`) | low 45.02 %, high 64.08 %, at step 50.00 %, at end 50.00 %, deadMax 90, largest lag after the step 43 | low 46.10 %, high 79.78 %, at step 50.00 %, at end 50.00 %, deadMax 90, heldDecisions 0, largest lag after the step 12 |

- Every largest lag after the step is within S + `LAG`.
- Every high is within the bound.
- Conservation is exact in all three: moles residual at most 5.8e-10 mol of 1.6e7, energy at most 2.2e-5 J of 7.0e11.
- The lane runs printed no `heldDecisions`: the field is new.

**Not taken.** Option B was offered and not taken: a coupled level loop that decides on each published state (worst
peak about 61 to 66 %).

## Printed lines that differ, and why

| Lines | Old (0.31.1) | New | Reason |
|---|---|---|---|
| Component counts, kernel output counts, checkpoint and store sizes, step bytes (`BatchStillTest`, `PengRobinsonKernelHuronVidalTest`, `FluidCheckpointFormatTest`, `FluidCheckpointStoreTest`, `PassiveStepSolverTest`, `UpLookingLuParityTest`, `ColumnWaterTest`'s port arrays) | 47 components; for example 1,107,000 unit bytes, 98,880 outputs | 52 components; 1,158,000 unit bytes, 120,300 outputs | W1's five rare gases: every composition array is five entries longer. Values printed beside the counts are identical (for example the still's trays [-15.44 16.86]). |
| `VirtualJunctionHoldupTest` `VIRTUAL_HOLDUP_DIGEST`, all six | for example mixed-gas 0.1 s `373fc192…` | `6882c2e4…` | Each digest hashes every node's mole array, which now has 52 entries. The junction lines that print the same transients' counters and floats are identical, 33 of 33. Printed only. |
| `NetworkPhaseEngineTest` WP7c, the "20-component mixture" wet at 750 K, 1 MPa | pc 929,588.516 Pa, p_w 70,411.484 Pa | pc 936,413.674 Pa, p_w 63,586.326 Pa | The test builds that mixture over every non-water compound (`i % 5 + 1` hundredths each), so the rare gases joined it: another mixture, not another model. The asserted ratio p_w/(y_w P) stays 1.000000. Printed only. |
| `CrudeUnitSampleTest` (`ColumnSizingSampleTest`, `SizeAllSampleTest`) and `ColumnSolveTest` U2 air coolers | E-102: 6 × 3 bays of 2.5 × 6 m, drop 0.043 bar; U2: 2 banks of 5 × 4 bays of 2.5 × 6 m | E-102: 1 × 3 bays of 6 × 12 m, drop 0.218 bar; U2: 1 bank of 5 × 3 bays of 3 × 11 m | The equipment visual design batch's larger air cooler bay: automatic sizing now picks larger bays. Every limit holds (E-102's drop is under its 0.7 bar limit, surface over its need). The size-all list reorders with it. Printed only. |
| `MachineHealthTest` N4 | "exhaust moisture … 0.0620" | "… 0.0620 at 10000 Pa, onset 0.0560" | The visual design batch's LP class adds the onset to the printed line; the value is unchanged. |
| `FluidFactsCompatTest` | the schema-26 image's lines | the schema-27 image's lines | The renumbering removed the schema-26 image and wrote `compat/fluid/schema-27/` again (named in the brief). |
| `ConvertersTest`, `DerivedPairsTest` | — | added or reordered lines | New hopper lines (W4). Pair lines print in another order with identical values. |

## Findings

| # | Finding | Proposal |
|---|---|---|
| 1 | `lane.sh`'s shell died with exit 255 during a long Gradle step; Gradle went on, the step's tail did not run. The cause is not known from the lane's output. | Run the tail by hand, as here. A tools change could make the tail a script of its own that a lane can resume. |
| 2 | The level loop GameTests did not pace the unthrottled server: one decision could fall while a slice was in flight and hold the output (section above). | Done: the owner chose "A: pace the test"; the three level loop tests pass. |
| 3 | The campaign's wall-deadline seeds (bundled 12 and 59) move between lanes with the machine's load. Both lanes shared the machine with another Gradle run. | None: the replay rule handles them. |
| 4 | `tools/heat-gui-renders/GuiPrototypes.java` does not compile on this branch (an old import of `science.column.operation`; ALARM_BEACON_BUILD.md). | A tools fix after the merge. |

## Second run, on the joined tree (main at 0.32.0)

**Why it ran.** `main` moved to 0.32.0 (`f305ac6b0`, the pollution system batch) while the first lane ran. The
orchestrator merged it into `claude/fluid-interfaces-batch` as `953635400`, on top of the pacing commit `ad12a263c`.
The code changed after the first lane, so the owner said "Run the lane". `lane.sh head` ran once, every step it
selects, on `953635400` (a fast-forward of `claude/fluid-interfaces-lane`). The shell did not die this time.

**Baseline.** The pollution batch's lane at 0.32.0, run on `a2ea53029`: `main`'s newest lane with every step. A copy is
in `tmp/merge-lane/base-pollution-0.32.0/`. The first lane's output was moved aside to `tmp/merge-lane/head-first/`
first. Counters only, no times.

### Results

- **test:** 3,777 tests, 0 failures, 0 errors. The selection took 389 of 494 `test` classes, all 10 `holdGates`, 63
  of 87 `columnGates` and 28 of 31 `equipmentGates`. The `test` filters passed 8,000 characters, so `test` ran whole.
  594 load sets saved.
- **regression:** chain-100 exact, every deviation 0.
- **gametest:** "All 110 required tests passed" (the 0.32.0 lane: 106). The level loop result lines:

| test (blanket, direct) | low | high | heldDecisions |
|---|---|---|---|
| vented (false, false) | 24.50 % | 62.47 % | 0 |
| blanketed, two cycles (true, false) | 25.20 % | 72.85 % | 1 |
| blanketed from empty (true, true) | 46.61 % | 62.46 % | 0 |

  The blanketed two-cycle drum had one held decision this run, and the paced server kept its peak at 72.85 % (bound
  1 % to 99 %, band 50 ± 10 % on each half: passed). The first lane's unpaced overflow does not recur.
- **Junction lines:** 33 of 33 identical, floats included; counters identical on 33 of 33.
- **`MIXED_GAS_COST`:** 313 Newton solves before and after. Allocated 125.0 to 137.1 MB (52 mole entries, as in the
  first run).
- **Campaign** (factor 4, 1000 seeds, 8 threads):

| run | clean, base → head | new holds | fixed holds | class moved | refused |
|---|---|---|---|---|---|
| cold random | 991 → 992 | none | 118 F | 0 | 0 |
| bundled random | 992 → 995 | none | 12 F, 59 F, 707 F | 0 | 0 |
| bundled defaults | 1000 → 1000 | none | none | 0 | 0 |

  No new hold, so no replay. Head holds: cold E5 ×6 (65, 101, 629, 752, 803, 919), F 6, 431; bundled E6 241, E9 145,
  431, F 579, 941.
- **Campaign Newton counters** (fixed seeds run further, so the sums are not like for like): cold solves 319,886 to
  326,331, iterations 1,962,414 to 2,023,231; bundled solves 133,055 to 134,419, iterations 663,586 to 673,097;
  defaults 27,002 and 65,433, identical.
- **References:** no asserted reference moved. Every ledger and conservation assertion passed.
- **The two batches together:** `EquipmentInfoCoverageTest` 6/6, `TutorialTextTest` 6/6, `LanguageFilesTest` 2/2,
  `UnitTextTest` 1/1 on the joined lang files (4,657 keys each); `FlowsheetPaletteCoverageTest` 5/5, `HelpCoverageTest`,
  `GuideCoverageTest`, `PhysicalRegistryTest`, `FxSoundsTest`, `SensorReadingTest`, `ControlSystemTest` 23/23,
  `MonitorSignalsTest` 4/4, `AlarmLawTest`, `AlarmSettingsTest`, `VesselBreachTest` and every pollution test passed.
  `FluidFactsCompatTest` reads the joined schema-27 image (a breached vessel and the ambient generator spec) and all
  older images. No test puts a pollution monitor's ANALYSER inputs and an alarm beacon on one data bus (finding 5).
- **Machine:** the lane printed `WARNING: 1 test or client JVMs running` five times (test, gametest and campaign
  steps). No other Gradle run was started from either session; the cause is not known.
- **Logs:** `tools/merge-lane/logs/fluid-interfaces-final-lane-2-logs-2026-11-16.tar.gz` in the main checkout
  (git-ignored).
- **Status:** clean. Nothing failed; nothing was changed or re-recorded.

### Printed lines that differ, and why

With times, timestamps, seconds, object hash codes and worktree paths left out (`printed-diff.js`), 8,858 lines are
identical and 167 differ. Four classes print only in the head lane (`GasIntakeTest`, `SteamTurbineSectionsTest`,
`SteamTurbineTest`, `RareGasPropertiesTest`). Against the first lane, only `FluidFactsCompatTest`, the log noise
below, reorderings and one allocation counter differ.

| Lines | Reason |
|---|---|
| Component counts, kernel outputs, checkpoint sizes, step bytes, WP7c, `VirtualJunctionHoldupTest` digests, the air coolers of `CrudeUnitSampleTest` and `ColumnSolveTest`, `MachineHealthTest` N4, `ConvertersTest` hopper lines | The first run's families (table above): 52 compounds, the larger air cooler bay, the LP onset, new lines. Same values as the first lane. |
| `CrudeUnitSampleTest$SizeAllSampleTest` order, `DerivedPairsTest` order | Lines print in another order; the sorted sets equal the first lane's. |
| `FluidFactsCompatTest`: 264 to 285 lines | The round-trip world holds more islands and vessels (16 to 19, 13 to 15); the energy deltas booked are identical (−2,554,382.311 J with 1.5 mol retired; −2,897,239.454 J). The joined schema-27 image prints its vessel lines. |
| `AccessoryStoreCompatTest`: 5 to 461 lines | log4j "Error writing to RandomAccessFile logs/debug.log" stack traces: parallel forks share the log file. Every base line is in the head lane. |
| `UpLookingLuParityTest` 582 to 579 bytes; `PassiveStepSolverTest` two tanks 135,724 to 146,894 bytes a step | Allocation counters (52 compounds); the factorization counts are identical. Against the first lane, `PassiveStepSolverTest` two tanks moved 147,011 to 146,894 bytes, the one value that moved with the pollution merge, a counter only. |
| `ColumnWaterTest` W5 | The port arrays have 52 entries instead of 47; the printed values are identical. |

### Finding

| # | Finding | Proposal |
|---|---|---|
| 5 | No test or GameTest puts a pollution monitor's ANALYSER inputs and an alarm beacon on one data bus; each is tested alone. | A GameTest after the merge: a monitor's reading over its limit raises a beacon on the same bus. |
