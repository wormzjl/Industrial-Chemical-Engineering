# Final lane of the tutorial branch

2026-11-13, on `df15654c0` (the translation, changelog, code guide and tooling cleanup included). One run of
`LANE_OUT=tmp/merge-lane-tutorial LANE_RUN=lane-tutorial-1113a bash tools/merge-lane/lane.sh head` (no `--all`).
Outputs: `tmp/merge-lane-tutorial/head/`. Baseline: main 0.29.0's third lane (aqueous solvents,
[FINAL_LANE_3_REVIEW.md](../2026-11-11-aqueous-solvents/FINAL_LANE_3_REVIEW.md), `tmp/final-lane-3/head/` of its
worktree). Counters only. No dev client, no other Gradle run.

| Step | Result |
|---|---|
| Selection | test 367 of 438 (filters too long: the task ran whole), holdGates 10 of 10, columnGates 73 of 83, equipmentGates 26 of 28; 40 classes had no load set |
| Tests | 551 result classes, 3505 tests, 1 failure (below) |
| chain-100 exact | ran (`PengRobinsonKernel` loads a changed constant of `ShapeRasterizer`); deviation 0 in all four quantities |
| Fluid GameTests | ran (`TutorialUnitGameTests.java` changed); 96 of 97, `TutorialUnitGameTests` failed (below) |
| Campaign | ran: `IslandCoordinator`, `Stills`, `TutorialTriggers`, `BatchStill`, `StillColumn`, `StillHead` differ from main |

## Failures, both test-side, fixed
1. `DeterministicMathSourceTest`: `films/SmallTower.java` called `Math.acos` and `Math.hypot` (P4b film, never in a lane
   before). Now `DeterministicMath.acos` and `Math.sqrt` (d1e35e4d3). DeterministicMathSource 2, TowerFilms 2,
   GuideFile 10, GuideCoverage 12 pass, once each with `--tests`.
2. `TutorialUnitGameTests.theGuidesSmallCrudeTowerRunsAtItsDesignFeed`: "build: Unknown column preset: small_crude".
   The test asked the material catalog's presets for the guide's tower, which is a client asset template
   (`columnpreset/` and `mixturepreset/small_crude.json`); the test never ran before. It now builds the input from those
   files as the column tests' `V3TestInputs.crude` does (df5b43c26). The gametest step alone (`lane.sh head gametest`,
   fresh run id `lane-tutorial-1113b`, `tmp/merge-lane-tutorial/fix/`): 97 of 97 pass; the tower reaches RUNNING at
   39.96 kmol/h with Check parts COMPLETE and the guide's go, limit and products conditions.
   No timing assumption on the still's fire was found: no GameTest runs a batch still.

## References against the baseline
- Junction lines: 33 of 33 identical, floats included. `MIXED_GAS_COST`: 313 Newton solves, as the baseline.
- Printed lines, 167 classes in both: 149 identical; 12 differ only in times, allocated bytes or object hashes. Moved
  by the still batch (its README): `BatchStillUnitTest` wet naphtha (first cut 0.7637 -> 0.1536, vent 0.8464),
  `BatchStillTest` (new CONDENSER, CONTINUITY and crude-run lines, crude vented 0.0638; wash energy residual
  -5.44e-16 -> -2.72e-16), `StillRuntimeTest` (new and changed still runs). `DrawCallTest`: the P6 lines.
  `CrudeUnitSampleTest$SimulatorRunTest`: packed sample body 22698 -> 22699 bytes (not traced). `HoldClassWorldsTest`:
  the baseline's wall-deadline hold print is gone (it passed here).
- Campaign at factor 4 (`compare.mjs`): cold clean 991 -> 992 (fixed 6 F; no new hold), Newton iterations
  1983908 -> 1988631; bundled random 994 -> 994, 431 E6 -> F, Newton iterations 685500 -> 685337; defaults 1000, counters
  identical (65438).
- Bundled 431 F: same end tick 1784 and eight holds; its hold at tick 1377 (island 32) read "wall deadline" in place of
  "Newton iteration limit". Replayed alone, once (`tmp/merge-lane-tutorial/replay-bundled-431/`): E6, the baseline's
  eight holds at the same ticks. A wall deadline with 8 threads, not a new hold.

## Not covered
- Nothing was checked in the game; the small unit's survival supplies together (converters, crude burners, driven pumps,
  FE fans, H-102) are proven by no test.
- The 71 test classes, 10 column and 2 equipment gate classes the selection left out stand from their recorded passes.

## Next baseline
This lane (`tools/merge-lane/logs/tutorial-system-final-lane-logs-2026-11-13.tar.gz`, main checkout), with the two fixes above, is the next baseline once the branch merges.
