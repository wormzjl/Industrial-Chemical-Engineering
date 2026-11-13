# Repetitions in the test suite: a review

2026-11-12, owner request after the second final lane ("remove all the repetitions in the test", scope chosen: duplicate
tests suite-wide). Read-only: no test changed, nothing run. Bar: `documentation/2026-11-02-test-suite-optimization/CRITERIA.md`
(A2 duplicate, S1 subsumed, S2 instance of a sweep, C1 cheaper form; never weaken an assertion). Times are the second
final lane's (`tmp/final-lane-2/head/results/`, 8 JVMs side by side), so they are shares, not measurements. Tool:
`tmp/test-duplicates/extract.mjs` (3454 test methods of `src/test` and `src/fluidGameTest`, bodies compared after
normalising, 5-token shingles).

## Duplicate tests: none to remove
- No two test bodies are the same after normalising. 97 pairs share 35 % or more of their text. Every one is a second case
  of one rule on another input (a liquid-only and a vapour-only condenser, a filter and a valve line, one door per screen).
  Each is a different production path or a different branch, so none is A2 or S1. The two earlier passes (2026-10-21,
  2026-11-02) removed the true duplicates.
- The "fits the door at every supported width" tests (`ConverterDoorTest`, `DeviceColumnTest`, `MachineColumnTest`,
  `StillDoorTest`, `HeatControlColumnTest`, `DialogColumnsTest`, `ConstructorColumnTest`) look alike but each checks its own
  screen. A shared helper would save lines but no time (all under 1.1 s).

## Repeated work inside tests (C1): the real repetitions
| # | Test | What repeats | Cheaper form | Est. saving |
|---|---|---|---|---|
| 1 | `PumpBottomPortDrainTest.aPumpedWaterLineRunsThroughTheWholeLifetimeOfItsJunctionsGasTrace` | 400 fixed slices; the junction's gas trace is gone at slice 2, and slices 3 to 399 repeat a steady fill (3 accepted steps, same modes, each) | stop 5 slices after the junction's vapour is 0; keep the no-hold assertion on every slice run | ~145 of 153 s |
| 2 | `ColumnSizingSampleTest`, `SizeAllSampleTest`, `UnitSizingSampleTest`, `LineSizingSampleTest`, `SimulatorRunTest` | each solves the same `crude_unit` sample cold (in `@BeforeAll` or a static), one JVM per class: 5 identical solves | one class with `@Nested` groups over one shared run and one shared `sizeAll` (also serves `ColumnSizingSampleTest`'s `plain`, a sixth solve) | ~4 to 5 solves, ~140 s |
| 3 | `SimulatorRunTest.theDigitsAreTheSame...` and `progressRunsInOrder...` | 3 cold runs on the runner thread; each is compared to `ONE` | run the progress test's runner beside a second runner; both `same(ONE)`; the digits test goes | ~35 s |
| 4 | `SimulatorRunTest.solveAloneHoldsAColumnOnTheStreamItHas` | a cold run only to have a steady sheet to edit | `runner.restore(new SimulatorHost.Finished(SAMPLE, ONE, 1))` | ~35 s |
| 5 | `UtilityRegulationTest` (11 variants) | each variant solves the Tia Juana sample cold (~30 s each); the base run is already steady | start each variant from the base run (`FlowsheetRuns.run(sheet, catalog, cp, run)`), as the game does after an edit; the warm solve takes under half a cold one's checkpoints (`SimulatorRunTest.aRunFromASteadyOne...`) | ~150 to 200 s |
| 6 | `FlowsheetColumnTest.theSampleClosesInTwoPasses`, `aFurnaceOnAFixedDutyCloses` | the same `sample(HOLDS)` sheet solved twice | one lazily held run | ~7 s |

Together about 510 to 560 s of the lane's 3043 s of test-class time (about 18 %). Item 2 changes the lane's selection:
the merged class runs whenever any of the five would have.

## Owner choices
- Item 5 tests the warm path where it now tests the cold one. The cold start of the same sample stays guarded by the
  base run and by `SimulatorRunTest`. A variant whose assertion sits at the loop's tolerance could move by up to that
  tolerance. The tightest assertions are values a door sets exactly: the kettle's 5 MW within 1 W, and the typed flow
  within 1e-9. Check those two first.
- Kept on purpose: `FlowsheetColumnTest.aFurnaceOnADutyClosesColdAsFromAFirstEstimate` (two solves, 140 s). It is the
  only guard of the tear estimate (`Sheet.estimate`).
- Kept: `ProcessUnitTest`'s six sweeps over every unit (20 to 30 s each). Each checks its own rule, and the geometry is
  already cached (`ShapeRasterizer.GEOMETRIES`).

## Not repetition: the batch's kernel cost
`PumpBottomPortDrainTest` (2.2 s on main), `ExchangerCouplingTest` (10 to 97 s) and `HoldClassWorldsTest` (74 to 118 s)
take the same steps as on main at a higher cost per state. 84 % of the pump test's samples are in the Huron-Vidal terms
of `PengRobinsonKernel`, which loop over every compound with a water partner (`tmp/pump-profile/NOTES.md`). That is a
performance task, not a test change.

## Not reviewed in depth
The fluid GameTests (one world per behaviour, 94 tests). The column and equipment gates were read by the scan only.
