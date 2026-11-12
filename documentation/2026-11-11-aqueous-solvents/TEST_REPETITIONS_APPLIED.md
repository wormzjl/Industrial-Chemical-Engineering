# Test repetitions: the six cheaper forms applied

2026-11-12, owner choice "All six" on `TEST_DUPLICATES_REVIEW.md` (on `claude/ethanol-compound-matrix-4b318d`). Every
assertion is kept (criteria C1). Branch `claude/test-repetitions-40d405`, `src/test` and `build.gradle` only.

| # | Change | Tests before → after | Result |
|---|---|---|---|
| 1 | `PumpBottomPortDrainTest`: the fed line stops 5 slices after its junctions' summed vapour is 0 (it is exactly 0 from slice 2 in the second final lane), and asserts the trace ends within 400 slices; per-slice hold check and `FED_PUMP` lines kept | 3 → 3 | pass; 7 slices run, not 400 |
| 2 | `CrudeUnitSampleTest`: the five sample classes as `@Nested` groups of their old names, over one cold run (counting `COLD`), one `FlowThermo`, one sizing pass and one Size all (also `aNewDiameter…`'s `plain`). `slowGateClasses` names the class and `CrudeUnitSampleTest$*` | 40 → 39 (item 3) | pass (5 groups, 39 tests, all under `holdGates`) |
| 3 | `theDigitsAreTheSameBesideAnotherRunAndProgressRunsInOrderAndTheLogFollowsTheLoop`: the `Ear` runner beside a plain one, both `same(ONE)`, then all of the progress test's checks | 2 → 1 | in 2 |
| 4 | `solveAloneHoldsAColumnOnTheStreamItHas`: `runner.restore(new Finished(SAMPLE, ONE, 1))`; `alone` reads its streams from `last()`, and the editor's sheet is `SAMPLE` itself (asserted) | 1 → 1 | in 2 |
| 5 | `UtilityRegulationTest`: each variant runs from the sample's steady run (`FlowsheetRuns.run(…, last)`); the base run stays cold | 12 → 12 | pass, every variant warm; the 1 W and 1e-9 checks hold |
| 6 | `FlowsheetColumnTest`: `theSampleClosesInTwoPasses` and `aFurnaceOnAFixedDutyCloses` share one lazily held run; `aFurnaceOnADutyClosesColdAsFromAFirstEstimate` unchanged | 10 → 10 | pass |

Printed lines keep their text; `printed.sh` now prefixes them `CrudeUnitSampleTest$<old name>`. No lane reader is keyed by
the old class names. Open for merge-prep: the code guide (`process-simulator.md`, `verification.md`) still names the five
old classes.

Run once (commit c2a3d74d9): `test --tests PumpBottomPortDrainTest holdGates --tests CrudeUnitSampleTest* --tests
UtilityRegulationTest --tests FlowsheetColumnTest`, `--max-workers=4`: 64 tests, 0 failed. Not run: `test` as a whole (the
exclusion of `CrudeUnitSampleTest$*` from it is by the same pattern list).
