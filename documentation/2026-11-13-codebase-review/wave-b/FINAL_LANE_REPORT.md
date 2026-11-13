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
