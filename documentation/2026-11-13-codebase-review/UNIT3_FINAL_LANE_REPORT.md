# Final lane of unit 3 of the review branch: report

`LANE_OUT=tmp/merge-lane-unit3 bash tools/merge-lane/lane.sh head`, run once by the main thread on 2026-11-13 at the code of `36b5805d3` (later commits are documents), tree clean, no other Gradle run and no subagent writing. Baseline: unit 2's lane (`tmp/merge-lane-unit2/head`, the code of `088e9887a`; [UNIT2_FINAL_LANE_REPORT.md](UNIT2_FINAL_LANE_REPORT.md)), which stood at the 0.30.0 lane. Output: `tmp/merge-lane-unit3/head/`, comparison `tmp/merge-lane-unit3/compare.txt`, console `tmp/review-verify/lane-console-unit3.txt`. Counters only; no times.

**Result: green.** No new hold, no replay needed.

## What unit 3 holds (after the marker `claude/codebase-review-unit2`)

Sixteen packages, 233 source files. Fourteen are structure only: C4c (`PortAccounts`), C4b (the still's column solver in `science/column/still`), D-E0 (the engine digest gate, S03), D-S1, D-S2, D-S3 (the step solver's `PortStreams`, `CarriedPoint`, `SolveConstants`, `PassStart`, three laws stated once), D-C1 to D-C4 (the column calculator in ten files; the calculator 3,652 to 626 lines), D-E1 and D-E3 (the engine's dry core on an explicit feed; four of its stages in files; 6,215 to 4,691 lines), D-Q1 and D-Q2 (the drawing kit without forwarders, ratings and kind drawings; 4,717 to 3,098 lines), D-R1 and D-R2 (the scheduler's queue, the device builder, the island coordinator in four files). Three change behaviour: R-ST1 and R-ST2 (the batch still reads across islands as of the fence; its column jobs are asked by ticks) and D-E2 (the engine judges only a solve's own split).

## Steps

- **test.** Selection: 368/457 `test`, 10/10 `holdGates`, 79/87 `columnGates`, 25/29 `equipmentGates`, `fluidSolverRegression` 1/1. 3,642 tests, 0 skipped, 0 errors, 0 failures.
- **regression (chain-100).** Exact: max deviation 0 in moles, temperature, phase fraction and flow.
- **gametest.** 97 of 97.
- **campaign** at factor 4, `compare.mjs` against unit 2's lane:

| run | clean unit 2 lane → unit 3 | refused | newtonSolves | newtonIterations | implicitSolves | holds |
|---|---|---|---|---|---|---|
| cold random | 991 → 992 | 0 / 0 | 327015 → 328617 | 1984579 → 1997985 | 196831 → 197570 | new: none; 6 no longer holds; 431 F → E9 |
| bundled random | 992 → 995 | 0 / 0 | 131190 → 133560 | 635031 → 662291 | 92909 → 94123 | new: none; 47, 59, 118 no longer hold |
| bundled defaults | 1000 → 1000 | 0 / 0 | 26997 → 26997 | 65447 → 65447 | 22539 → 22539 | none |

- **Junction lines:** 33/33 identical with unit 2's lane, floats included; 33/33 counters identical. `MIXED_GAS_COST` newtonSolves 313 → 313, allocated 324.1 → 324.3 MB.

## The campaign's counters

The totals moved because seeds that stopped at a wall-deadline hold in unit 2's lane (cold 6; bundled 47, 59, 118) ran to their end in this one: a seed stops counting at its first hold. Checked seed by seed: of the 3,000 outcome lines (end tick, holds, classes, every island's status, crystal mass), all are equal to unit 2's lane except those four and cold 431 (the known wall-deadline seed, which this lane reaches as E9, as unit 1's lane did). The defaults campaign's counters are identical. So no solve of the campaign moved.

Seeds 6, 47 and 59 are the three that unit 2's lane replayed alone and found clean. Seed 118 (bundled) held on the wall deadline in the 0.30.0 baseline and in unit 2's lane, both times at tick 7507, and is clean here (end 14380): the bundled random campaign stands at 995 where the baseline stood at 994. A hold at the same tick in two lanes is a slice whose cost sits at the budget; whether the quiet machine or a cheaper step cleared it was not measured (no timed run in this lane), so it is not claimed as a gain.

## Printed lines that moved against unit 2's lane

Read with times, hashes and byte counts stripped.

- **The still (R-ST1, R-ST2), as stated:** `StillRuntimeTest` wash pot: the water side's heat booked after the run 854,227 → 592,689 J (the water takes the duty a fence later: the heat in transit holds the difference); head ethanol share 0.7710 → 0.7709. Light oil: head 314.56 → 314.30 K, distillate 0.0122 → 0.0121 kg/s, the vented and distilled moles within 1.5 % of before (the column answers every 200 ticks and the outlets read the answer in force a fence earlier). New lines: the two determinism gates (the solid-fired one: 24 jobs; the liquid-fired one: 30 jobs, 0.1155 kg of fuel).
- **Allocation only:** `PassiveStepSolverTest` bytes a step 486,931 → 487,155 and 392,337 → 392,561 (the per-solve constants are one object); `MixedGasJunctionTransientTest`'s 24 lines differ in `bytes` (0.05 to 0.6 %) and nothing else; `CrudeUnitSampleTest` packed body 22,963 → 22,964 bytes.
- **New lines:** `EngineDigestTest` (429 requests), `AqueousEquilibriumTest` (40 pairs equal bit for bit).

## What stands

Every gate above stands for the code of `36b5805d3`. The two campaign harnesses (`tools/unified-thermo-port-forms/hold-fuzz-{cold,bundled}`) compiled and ran with the device builder and the moved worker records. Not covered by any gate: a batch still in the game after R-ST1 and R-ST2 (the lags, the liquid still's lighting with no alarm, a controller on a still at its new cadence); the items listed for units 1 and 2.
