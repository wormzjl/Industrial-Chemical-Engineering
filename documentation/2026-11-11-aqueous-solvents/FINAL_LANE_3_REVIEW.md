# Third final lane of the aqueous-solvents batch

2026-11-12/13, on `1b784f1f2` (`94278e90a` with the merge-prep commit, documents only). One run of
`LANE_OUT=tmp/final-lane-3 bash tools/merge-lane/lane.sh head`, sub-thread (owner: "run changed only then merge").
Outputs: `tmp/final-lane-3/head/`. Baselines: the second lane (`tmp/final-lane-2/head/`,
[FINAL_LANE_2_REVIEW.md](FINAL_LANE_2_REVIEW.md)) and main 0.27.1 (`tmp/final-lane/base/merge-lane/head/`). Counters only.

| Step | Result |
|---|---|
| Tests | selected: test 260 of 400, holdGates 8 of 8, columnGates 63 of 83, equipmentGates 0 of 28; 475 result classes (nested groups apart), 3037 tests, 1 failure (below) |
| chain-100 exact | deviation 0 (all four quantities) |
| Fluid GameTests | 96 of 96 pass |
| Junction lines | 33; counters identical to both baselines, floats identical to lane 2; `MIXED_GAS_COST` 313 Newton solves (all three) |
| Campaign clean (cold, bundled random, defaults) | 991, 994, 1000; refusals 0 |
| Replay alone (AGENTS.md wall-clock rule, parent's word) | `HoldClassWorldsTest.aResidueIsBookedWithoutAClosure`: passes |

## The failure: a wall deadline under load, not counted
- holdGates, `HoldClassWorldsTest.aResidueIsBookedWithoutAClosure`: "a residue booked into an empty tank: holds
  [Held[tick=2892, island=15, committed=2792, status=HELD: wall deadline]]" (`EventPathWorld.assertKeptUp`).
- One replay alone, once: `./gradlew holdGates --tests '...HoldClassWorldsTest.aResidueIsBookedWithoutAClosure' --rerun
  --max-workers=1`: 1 test, 0 failures (`tmp/final-lane-3/replay-hold-class-residue.*`). The hold was the wall deadline
  with 8 test JVMs side by side.
- Load against cost: the world prints counters only when it holds, so no stored counters of it exist to compare.
- Time shares of the 469 classes in both lanes (each class's share of their sum; side-by-side JVMs). Grew most:
  `FlowsheetColumnTest` 17.2 -> 21.3 %, `HoldClassWorldsTest` 5.2 -> 7.4 %, `ColumnSolveTest` 4.4 -> 6.2 %,
  `RegroupedCrudeTest` 2.3 -> 3.1 %, `V3AqueousColumnTest` 1.1 -> 1.6 %. Fell most: `UtilityRegulationTest`
  18.1 -> 8.5 %, `PumpBottomPortDrainTest` 7.9 -> 0.3 %, `ExchangerCouplingTest` 4.3 -> 2.1 %, `InletPhaseTest`
  0.9 -> 0.2 %, `ExtremeTopologyIslandTest` 1.0 -> 0.9 %. The falls are mostly the shortened tests of `e8a25bd2e`, which
  lifts every other share; the growers are column (dense-mixture) tests, but a share is not a cost and lane 3 ran 36
  fewer classes. Script: `tmp/final-lane-3/times.js`.
## Campaign against the second lane
- Cold: fixed 589 B; 629 B -> E5, 743 B -> E5. Newton iterations 1959612 -> 1983908, solves 322871 -> 326898.
- Bundled random: 431 F -> E6. Newton iterations 683188 -> 685500. Defaults: identical counters (65438).
## Campaign against main 0.27.1
- Cold: new 65 E5, 743 E5, 919 E5, 6 F; fixed 589 B, 85 E1, 181 E2, 118 F; 629 B -> E5, 431 B -> F.
  Newton iterations 2002115 -> 1983908.
- Bundled random: fixed 301 B, 896 E5, 47 F, 678 F; 431 F -> E6. Newton iterations 859982 -> 685500.
- Defaults: clean 1000 both; Newton iterations 62242 -> 65438 (as in lane 2).
## Held seeds, budget class
- Cold F: 6, 431 (both F in lane 2: no new budget-class hold, no replay). 6 replayed clean in lane 2.
- Cold 431 after the `PassiveStepSolver` fix (`6084652e4`): F, wall deadline on island 42 at end tick 3085, 64.4 kg
  of crystal in 10 vessels. In lane 2 it ended at tick 2159 (cycle traced at tick 2785, E7); the cycle no longer
  stops it, the budget does. Not replayed (not new to a baseline).
- Bundled F: 118, 579, 941 (lane 2: 118, 431, 579, 941).
## Open
- Cold 431 now holds on the wall budget past its old cycle; whether it is the cost of its crystal load or a new stall
  is not measured (no replay by the rule).
- No stored counter separates load from cost for `aResidueIsBookedWithoutAClosure`.
- Nothing in this lane blocks the merge: the one failure passes alone, no new budget-class hold, chain-100 0, GameTests 96/96.
