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
- **gametest:** 101 of 102. One failure, `LevelLoopGameTests.aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence`:
  timing-sensitive, read below. The four new GameTest batches (`alarm-beacon`, `crude-oil`, `gas-intake`, `liquid-hopper`)
  pass.
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
- **Status:** one GameTest failure stands, read as timing-sensitive. It waits for the decision below. Everything else
  is clean.

## The five test failures, and their fixes

| Class | Failure | Cause | Fix |
|---|---|---|---|
| `PreparedRefillTest` (3 tests) | `RootAbsent`: "a liquid of a node with a vapour has only a vapour-like root" at 330 and 345 K, 2 bar | A positional pin of the world list. The test's liquid took the compounds at `n-2-c`, the last ones before water. W1 appended the five rare gases there, so its "liquid" became xenon, krypton and helium, which are supercritical. | `ecc4d49e9`, test only. The liquid skips the five rare gases (`RARE_GASES = 5`) and runs the same compounds as before: ethanol, carbon dioxide, ethyl mercaptan. Alone: 3/3. Accepted by the orchestrator. |
| `TutorialTextTest` (2 tests) | Item lines over 14 words or not one sentence: `alarm_beacon.serves`, `crude_oil_bucket.serves`. A tour stop of two sentences: `tour.alarm_beacon.settings.7`. | English lines written in W3 and W9 that break the test's text rules. W9 did not run this class (the builder's miss). | `5e306724b`, lang text only, with the orchestrator's OK. The beacon's wording is the orchestrator's. Four translations follow. Alone: TutorialTextTest 6/6, LanguageFilesTest 2/2, UnitTextTest 1/1 (the load map names UnitTextTest for the lang files). |

## The GameTest failure

`LevelLoopGameTests.aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence`: "The drum emptied or overflowed (1 % to
99 %): 23.35 % to 100.0 %".

**Expected behaviour.** The blanketed drum holds two control cycles of its feed. After the +50 % feed step at cycle 15,
the level loop opens its valve within a cycle, and the level peaks well below the 99 % limit. The 0.31.1 lane's run
peaked at 81.79 %.

**What the run did.**
- Fill phase: the run is identical to the 0.31.1 run through the first five samples, 10.91 to 30.25 %.
- First split: the two runs part at the controller's first action. Here the output is 0.4538 at a level of 33.08 %;
  in 0.31.1 it was 0.5100 at 37.63 %. The action falls at another point of the 100-tick control cadence.
- Why the timing moved: this lane runs 102 GameTests against 98, so the test starts at another online tick. Its ramp
  committed at tick 27203 against 27397.
- After the step: the feed edit landed one cycle later than in 0.31.1. Then, for two cycles, the level reading was
  flagged `held=true`: the island was SOLVING, so the reading had not advanced. The output stayed at 0.75.
- The arithmetic: a 50 % step held for two cycles adds one cycle of feed, which is half of a two-cycle drum, so 50 %
  becomes 100 %. The drum went liquid-full (3.85 bar, venting) and the loop recovered to 50.00 % by cycle 26.
- The two other level-loop tests (vented, and blanketed from empty on the design feed) pass.

**What the batch changed near it.**
- `LevelLoopGameTests` and the gas blanketing valve are unchanged since `main`.
- `ControlLaw` gains W7's on/off branch, taken only by a valve whose `onOffValve()` is true (an automated shutoff
  valve). The test's level valve is an ordinary control valve.
- `ControlService` gains W9's beacons. Its early return also requires `alarms.idle()`, which holds when no beacon
  exists; the test places none.
- This reading comes from the lane's log, the code and the 0.31.1 log. By the replay rule, the test was not run again.

**Proposal (decision pending):**
- (A) Accept it as a timing-sensitive test and merge. *Recommended.*
- (B) A test-only follow-up: start the run at a fixed phase of the control cadence, so its result does not depend on
  how many GameTests ran before it.
- (C) A control-law question for the owner: the stale-reading hold (`4563176e1`) can freeze the output for two cycles
  after a feed step, which is exactly enough to fill this drum.

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
| 2 | The blanketed level-loop GameTest depends on its start phase in the control cadence (section above). | Owner or orchestrator choice: A, B or C. |
| 3 | The campaign's wall-deadline seeds (bundled 12 and 59) move between lanes with the machine's load. Both lanes shared the machine with another Gradle run. | None: the replay rule handles them. |
| 4 | `tools/heat-gui-renders/GuiPrototypes.java` does not compile on this branch (an old import of `science.column.operation`; ALARM_BEACON_BUILD.md). | A tools fix after the merge. |
