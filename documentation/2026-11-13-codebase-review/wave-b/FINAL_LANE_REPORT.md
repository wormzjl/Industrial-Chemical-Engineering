# Final lane of the review branch (wave A fixes, wave B, C1a, G0, G1 part 1, the Rust crates): report

`bash tools/merge-lane/lane.sh head`, run once by the main thread at `a0b157325`, tree clean, the machine alone (no other Gradle run, no cargo). Baseline: the 0.30.0 final lane (`tutorial-system-final-lane-logs-2026-11-13.tar.gz`, `head/`). Output: `tmp/merge-lane/head/` of the review worktree; console `tmp/review-verify/lane-console.txt`. Counters only; no times.

**Result: one GameTest failure; everything else green.**

## Steps that ran
- **test.** Selection: 361/448 `test`, 10/10 `holdGates`, 82/86 `columnGates`, 26/29 `equipmentGates`, `fluidSolverRegression` 1/1. 3,621 tests, 0 skipped, 0 errors, 0 failures.
- **regression (chain-100).** Max deviation 0 in moles, temperature, phase fraction and flow: exact.
- **gametest.** 97 ran: 96 passed, 1 failed (below). The five failures of the wave A lane pass.
- **campaign** at factor 4, `compare.mjs` against the baseline:

| run | clean base → head | refused | newtonSolves | newtonIterations | implicitSolves | holds |
|---|---|---|---|---|---|---|
| cold random | 992 → 992 | 0 / 0 | 327588 → 327675 | 1988631 → 1989397 | 197157 → 197210 | none new, none fixed |
| bundled random | 994 → 995 | 0 / 0 | 134674 → 133496 | 685337 → 661909 | 94735 → 94089 | fixed 118 F; 431 moved F → E9 |
| bundled defaults | 1000 → 1000 | 0 / 0 | 26997 → 26997 | 65438 → 65447 | 22539 → 22539 | none |

- **Junction lines:** 33/33 identical with the baseline, floats included; 33/33 counters identical. `MIXED_GAS_COST`: newtonSolves 313 → 313, allocated 885.1 → 323.9 MB.

## The failure
`LevelLoopGameTests.aBlanketedDrumStartedEmptyOnTheDesignFeedHoldsItsLevel`: "The level was outside its band at the step or the end: 50.02 %, 71.20 %". The loop was still swinging when the test ended (levels 35, 49, 63, 64, 41, 39, 59, 71 % over the last eight cycles, lags 12 to 54 ticks). It passed in the baseline and in the wave A lane. Another test of the same class failed in the wave A rerun (`aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence`, 99.71 %) and passes here.

The family's trajectories depend on the engine's lag already at the baseline (test 1: 48.6 to 64.1 % in one baseline run, 30.2 to 76.0 % in the other). Five runs are tabulated in [../wave-a/LEVEL_LOOP_REVIEW.md](../wave-a/LEVEL_LOOP_REVIEW.md), where the diagnosis is written (a subagent's, from the logs and the code, with no rerun). Until it is settled this lane is not green.

## Printed lines that moved against the baseline
- Not in the wave A lane, so from wave B, C1a or G: `BlanketDrainWorldTest` (27 lines: share 0.017760751539983455 → 0.017760751535367162, P 92178.155437177 → 92178.15544453658 Pa; 2.6e-10 and 8e-11 relative), `PumpBottomPortDrainTest` F1 slices 41 and 42 (flow and condensed amount in the last one to four digits), one line each of `SteamTurbineConnectionTest` (wet run) and `RigidVesselTest` (barometric). All four hold water at or below its saturation pressure. Attributed to C1a's stated shift (the one Wagner-Pruss association moves the share's saturation line by about 1e-15; a converged wet state then moves inside the solver's tolerance). No gate reference moved: every class passed, chain-100 is exact and the junction lines are identical.
- From wave A (as its lane reported): the column identity strings, `V3StageContinuationTest`'s failed-check lists, the hydraulic poll counts, the sample body size.
- New lines: the counters of B-C1 (band factorizations), B-S1, B-S2 (`UpLookingLu`), P21, `FrameBatchDrawsTest`, `ShaftPowerBookingTest`, `WaterModuleTest`.
- Bytes only: `MixedGasJunctionTransientTest` (212 MB against 525 MB on its first case).

## What stands
- The unit and gate suites, chain-100, the junction lines and the campaigns stand for the code at `a0b157325`.
- The GameTest step stands for 96 of 97; the level-loop test needs its diagnosis and one run after the fix.
- Not covered by any gate: the five screens' frame batch in the game (B-K1), the Rust library on the Java side (part 2b is not merged).

## Second run, at the unit's final code (`4563176e1`)

After the first run the Rust library's Java side (part 2b: the C++ lane removed, the band solve behind a ceiling that is off by default) and the level law's fix were merged, so the lane ran once more, alone, at `4563176e1`. **Green.** Output: `tmp/merge-lane/head/` (the first run's is kept in `tmp/review-verify/lane-a0b157325-head/`); console `tmp/review-verify/lane-console-2.txt`.

- **test.** Selection: 287/450 `test`, 10/10 `holdGates`, 76/86 `columnGates`, 6/29 `equipmentGates`, `fluidSolverRegression` 1/1. 3,454 tests, 0 skipped, 0 errors, 0 failures. 541 load sets saved, none failing.
- **regression (chain-100).** Exact.
- **gametest.** 97 of 97. The three level loops end at 50.00 %; post-step peaks 62.49 %, 87.06 % and 66.71 %; the largest dead time of a decision 80 to 81 ticks, so the run was inside the condition that failed before.
- **campaign** against the baseline: cold random 992 → 992 (newtonSolves 327588 → 327675), bundled random 994 → 994 (134674 → 133291; seed 431 moved F → E9; no new hold), bundled defaults 1000 → 1000. No new budget-class hold.
- **Junction lines:** 33/33 identical with the baseline, floats included; counters identical; `MIXED_GAS_COST` newtonSolves 313 → 313.
- Run beside the lane by the main thread on the same code: `nativeSmoke` 4 of 4 with the built Rust library, and ten column classes with `-PnativeColumn=band` (no digest moved).

What stands for the unit: every gate above at `4563176e1`. Not covered by any gate: the five screens' frame batch in the game (B-K1). Known residual: a level loop's first correction after a large feed step can come two cadences late on a loaded machine; the two-cycle drum of `LevelLoopGameTests` has two cadences of headroom at +50 % (peak 87 % in this run).
