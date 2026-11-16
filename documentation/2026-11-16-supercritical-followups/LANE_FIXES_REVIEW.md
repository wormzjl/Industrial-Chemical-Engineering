# The fixes of the final lane's two failures

Batch `2026-11-16-supercritical-followups`, after the final lane at `9b1dd2bd6` ([FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md)).
Fix thread on branch `claude/confident-elion-faef91`, base `cb9970087`. Rule B is the main thread's decision on this thread's
stop (option B of three, measured first).

## Summary

1. **Expected.** The column is a vapour-liquid stage model. A feed unstable only towards a second liquid is carried as one
   liquid, and the trays' notes report a liquid that would settle (owner rule). Only an instability towards a vapour needs a
   split. A plunger pump's law gives the same bits on every JVM.
2. **What the code did.**
   - `PlungerPump` called `Math.log1p` and `Math.expm1`, which `DeterministicMathSourceTest` forbids.
   - P3's flash refused the W5 ethanol, water and hexane feed (40/50/10 at 345 K, 1 atm). The feed is at the Wilson
     endpoint, not off it: the stability test finds it unstable, the restart and the engine (no feed with water) give no
     split, and form D refused it. The frozen and 0.34.0 flashes answered every endpoint feed with the endpoint's phase.
   - Where it did split, P3 took a split reached from a liquid trial as vapour-liquid. The same column's distillate at
     320 K and 1 atm (hexane 0.604, ethanol 0.300, water 0.095) is unstable towards a hexane-rich liquid (trial reduced
     density 3.18, feed 3.17). From that trial, successive substitution converged to 10.0 % "vapour" with 39 % water
     (H +3.41 kJ/mol). At 47 °C no vapour forms (water's vapour pressure 10.6 kPa; the heteroazeotrope boils near 329 K):
     a stationary point of the one-liquid model, two liquids taken as vapour-liquid. It dropped the tray-8 note.
3. **What was done.**
   - Fix 1: `DeterministicMath.log1p`, `.expm1`. No plunger value can move: the JDK's `Math.log1p`/`expm1` return
     `StrictMath`'s (no intrinsic), as `DeterministicMath` does. No other code of the batch calls a forbidden function.
   - Fix 2 (`V3FeedFlash`), rule B: after an UNSTABLE verdict the flash first reads the kind of instability from the test's
     own result (`towardsVapour`): the stationary phase on its root and the feed on its root, each by rule D (reduced
     density below 1, or on a supercritical isotherm of its own composition). Neither a vapour: one liquid (the endpoint's
     answer at the Wilson endpoint, else `onePhase` with the detail ending `SECOND_LIQUID`), no restart, no engine call.
     A split into two liquids after all is one liquid too (the `TWO_LIQUIDS` refusal is gone). At the Wilson endpoint no
     path refuses: with no vapour-liquid split the endpoint's answer stands, the workspace restored. Only a feed unstable
     towards a vapour that neither the flash nor the engine splits, off the endpoint, stays a typed refusal.
4. **Measured** (`tools/supercritical-probes/p3`, `run.sh dense|coarse|wet`, each once on the final code):
   - dense grid: 10,602 answered, 0 refused, 0 wrong one-phase against the engine's VAPOR_LIQUID, moves only the 181; all
     of the 181 and of the 721 still split; no engine split answered as one phase; F3 equals production to the bit;
   - coarse grid: 280, 0, 0, moves only the 5 of the 181; F3 equals production to the bit;
   - dry two-liquid scan (207 states of CO2/pc03 and methane/pc03): every answer equal to the lane's flash;
   - wet grid (570 states): answered by the frozen flash and refused now 0; split by the frozen flash and one phase now 0;
     refused by the lane and answered now 31 (the frozen endpoint answer, to the bit); one state refused by every flash.
5. **The guarantee.** No feed the frozen flash answers is refused: off the endpoint the frozen flash answers only where the
   iteration from Wilson's K converges, which runs first and unchanged; at the endpoint no path refuses. Both grids show 0.
6. **The three moves** (lane table 4, rows 2 to 4): the W5 wash feed, ethanol 10 and water 90 at 359.84 K, 101,325 Pa.
   Frozen and 0.34.0: all liquid at the Wilson endpoint, H −39,074.828 J/mol. Now: the column's own split from a vapour
   trial (y ethanol 0.432, measured near 0.44): β 7.6675e-5, H −39,071.723 (+3.105 J/mol); the model's bubble point is
   about 359.8 K. A changed answer of the kind P3 announced for the 181. Row 5: section 3.
7. **The hexane column back to 0.34.0.** Every one of its 7 feed-flash calls answers as the 0.34.0 flash; the note is
   "two liquids on trays 8; the distillate would settle into two liquids"; the W5 hexane sheet is STEADY with
   `column.two_liquids` [8] and `column.two_liquids_distillate`.
8. **Known limits.** A test that stops at a liquid trial where a vapour would also form (three phases) gives one liquid;
   no wet-grid feed the frozen flash split is one phase now. A wet feed unstable towards a vapour that substitution does
   not converge stays refused off the endpoint (EtOH/W 10/90 at 390 K, 200 kPa, refused by every flash). At the endpoint a
   failed restart keeps the endpoint's liquid: the hexane column's feed at 345 K and 101.3 kPa is one liquid (as 0.34.0)
   while the same feed at 100 kPa splits (β 0.336); Wilson's K put some feeds whose own root is a vapour at the liquid
   endpoint (the distillate at 330 to 345 K). Follow-ups, not this fix's.
9. **Runs** (section 5): `test` 92 classes, 917 tests; `holdGates` 10, 69; `columnGates` 45, 265, 1 failure (the new case's
   test catalog), then that class 3 tests, 0; `equipmentGates` 20, 186; 0 failures on the final code. Printed lines against
   the lane: only the W5 hexane sheet (back to 0.34.0), the new case's two lines, and SAMPLE_BODY's packed size, which moves
   by wall-clock seconds stored in the run (23,186 / 23,187 / 23,185), not by the flash. The other 44 column-gate classes'
   printed lines were not kept (run 2 replaced the folder).

## 1. Rule B on the four grids

| Grid | States | Answered | Refused | Wrong one-phase vs VAPOR_LIQUID | Moved vs frozen outside the 181 | Of the 181 / 721 now one phase | Engine splits answered one phase |
|---|---|---|---|---|---|---|---|
| dense | 10,602 | 10,602 | 0 | 0 | 0 | 0 / 0 | 0 |
| coarse | 280 | 280 | 0 | 0 | 0 | 0 / 0 | 0 |

Dry scan and wet grid, frozen flash, lane flash (P3 at `cb9970087`) and the final flash:

| Mixture | States | Frozen one → split now (β; ΔH J/mol) | Refused by frozen → answered | Lane split → one liquid now | Lane refused → answered | Refused now |
|---|---|---|---|---|---|---|
| EtOH/W 10/90, 330 to 400 K | 90 | 8 (0.028 to 0.295; +1,135 to +11,646) | 0 | 0 | 3 | 1 (all refuse) |
| EtOH/W 40/60, 330 to 400 K | 90 | 1 (0.947; +38,171) | 4 (one phase) | 0 | 8 | 0 |
| EtOH/W 80/20, 330 to 400 K | 90 | 0 | 2 (one phase) | 0 | 0 | 0 |
| EtOH/W/C6 40/50/10, 310 to 400 K | 114 | 13 (0.182 to 0.336; +5,558 to +11,530) | 6 (one phase) | 33 | 19 | 0 |
| Tia Juana crude/W 90/10, 300 to 600 K | 186 | 4 (0.005 to 0.080; +131 to +2,387) | 0 | 0 | 1 | 0 |
| CO2/pc03 90/10, 200 to 240 K (dry) | 63 | 3 (0.795 to 0.815; +10,315 to +10,973) | 1 (split) | 0 | 0 | 0 |
| CO2/pc03 70/30, 200 to 240 K (dry) | 63 | 11 (0.384 to 0.541; +4,659 to +7,054) | 0 | 0 | 0 | 0 |
| C1/pc03 90/10, 110 to 130 K (dry) | 81 | 6 (0.748 to 0.791; +5,781 to +6,224) | 3 (split) | 0 | 0 | 0 |

Pressures 50, 75, 100, 150, 200, 300 kPa (wet); 0.4 to 1.0 MPa (CO2/pc03), 0.15 to 0.35 MPa (C1/pc03). Wet feeds on the
island view the column uses (`FluidThermodynamics.on`: the package with its cubic water, restricted to the island's compounds).
Answered by the frozen flash and refused now: 0 on every row. Split by the frozen flash and one phase now: 0 on every row.

- The splits of the frozen flash's one-liquid answers came with P3 and pass rule B's vapour test (a vapour trial, or the feed on its vapour root). The 13
  EtOH/W/C6 ones lie above the heteroazeotrope at their pressure (at 100 kPa 335 to 345 K, at 50 kPa 320 to 325 K): the
  hexane boils off with ethanol and water, a real vapour. The dry ones lie along carbon dioxide's or methane's saturation
  line (CO2 at 225 to 240 K, 0.7 to 1.0 MPa), the lighter phase a vapour (the column's own split or the engine's); P3's 18 two-liquid states (220 K at
  0.6 to 0.8 MPa and their methane twins) keep the frozen one liquid.
- The 33 EtOH/W/C6 states the lane split and rule B answers one liquid are the 320 K kind: a liquid trial, a "vapour"
  rich in water or hexane below the heteroazeotrope. Each equals the frozen flash's answer to the bit.
- The ethanol/water 40/60 state (0.947 vapour) is one Wilson's K put at the liquid endpoint far above the bubble point.

## 2. The 320 K distillate: why the kind of instability is read first

The ethanol, water and hexane column (`ColumnWaterTest.aLiquidThatWouldSettleIsANote`, the W5 sheet's hexane case), its
condenser at 320 K and 1 atm. Feed-flash calls of one solve, frozen / 0.34.0 / lane / final:

| Call | Composition (C6, EtOH, W) | Frozen and 0.34.0 | Lane (P3) | Final |
|---|---|---|---|---|
| feed, 345 K | 0.100, 0.400, 0.500 | liquid, Wilson endpoint, H −36,332.173 | refused (no split) | liquid, as frozen |
| condenser, 320 K | 0.604, 0.300, 0.095 | liquid, Wilson endpoint, H −31,422.481 | β 0.1004, H −28,008.466 | liquid, as frozen |
| condenser, 320 K (later) | 0.609, 0.296, 0.095 | liquid, H −31,350.965 | β 0.1045, H −27,796.617 | liquid, as frozen |

The stability test on the 320 K distillate: UNSTABLE, tm −0.0695, trial hexane 0.941, ethanol 0.050, water 0.009 on the
liquid root (reduced density 3.18); the feed on the liquid root (3.17). The lane's restart from that trial converged to a
"vapour" of hexane 0.48, ethanol 0.13, water 0.39 over a liquid of 0.618, 0.319, 0.063. On the feed composition the same
holds at 300 to 330 K (trial a hexane liquid; the "vapour" 78 to 93 % hexane at 300 to 320 K, where hexane's vapour pressure
is 21 to 48 kPa). Above the heteroazeotrope (335 and 340 K) the trial is a vapour and the split (β 0.18, 0.23) is real; rule B
keeps it.

## 3. The three moves

| Lane row | Base (0.34.0) | Lane and now | Behind it |
|---|---|---|---|
| 2: `FlowsheetAqueousColumnTest` W5 sheet wash, distillate | 10.495 mol/s | 10.496 mol/s | the feed, see below |
| 3: `FlowsheetAqueousColumnTest` W6 example, spirit | 10.4945 mol/s | 10.4963 mol/s | the same feed (preheater outlet 359.84 K) |
| 4: `ColumnWaterTest` W5 running wash, ethanol to distillate | 0.8474 | 0.8475 | the same feed |
| 5: `CrudeUnitSampleTest$SimulatorRunTest` SAMPLE_BODY packed | 23,186 | lane 23,187, now 23,185 | wall-clock seconds in the stored run, not a flash result (below) |

The feed of all three: ethanol 0.1, water 0.9 at 359.84 K, 101,325 Pa. Frozen and 0.34.0: LIQUID, all-liquid Wilson
endpoint, H −39,074.827872 J/mol. Lane and final: TWO_PHASE, the column's own iteration from the stationary phase in 47
iterations, β 7.6675e-5, y ethanol 0.432, H −39,071.722509 J/mol (+3.105 J/mol). Of the 7 feed-flash calls of each solve,
the 3 at the feed moved this way and the other 4 are the same answer. The flash's tolerance (1e-10 in ln K) does not bound
an intended change of answer: it is a changed answer of the kind P3 announced for the 181.

Row 5 has another cause than the feed. The sheet (14,547 bytes) and the results (66,280 bytes) have the same length in
the baseline, the lane and this run; only the packed (deflated) body differs, 23,186, 23,187 and 23,185. The stored run
carries each pass's and step's wall-clock seconds (`SimulatorResults` writes `seconds()`, which `FlowsheetSolver` takes
from `System.nanoTime`), so the compressed size moves by a byte or two from run to run on any code. Not a solved value;
no bound applies. Reported, for the owner's view, as noise of the test's printed line.

## 4. Tests

- `V3FeedFlashStabilityTest.aFeedUnstableOnlyTowardsASecondLiquidIsOneLiquid` (new): the 320 K distillate (UNSTABLE with a
  liquid trial) and the 345 K column feed are one liquid, the frozen answer to the bit; the W5 wash keeps β 7.6675e-5 with an
  ethanol-rich vapour. No dry two-liquid state off the endpoint exists on the grids (the dense and coarse grids' refused two
  liquids were 0 under form D, and rule B's off-endpoint path answers none of them), so no case for it.
- `FlowsheetAqueousColumnTest.aLiquidThatWouldSettleIsANotice`: asserts STEADY with exactly `column.two_liquids` [8] and
  `column.two_liquids_distillate` (it printed and accepted FAILED before).
- `ColumnWaterTest.aLiquidThatWouldSettleIsANote` unchanged (its note is the owner-approved one).

## 5. Runs and printed lines

Selection: `node tools/merge-lane/affected.mjs select --since cb9970087` on the final code (6 files changed): `test` 91 of
506, `holdGates` 6 of 10 patterns, `columnGates` 46 of 90 (`NativeColumnSolverTest` is excluded from the task),
`equipmentGates` 20 of 31, chain-100 skip, GameTests due (not run: the brief). `DeterministicMathSourceTest` reads source
files, not classes, so the selection cannot name it: added by hand. Every run alone on the machine, `--offline`,
`--max-workers 4`.

| Run | Task | Result files | Tests | Failures | Note |
|---|---|---|---|---|---|
| 1 | `test` | 92 | 917 | 0 | the 91 and `DeterministicMathSourceTest` (2 tests) |
| 1 | `holdGates` | 10 | 69 | 0 | |
| 1 | `columnGates` | 45 | 265 | 1 | the new case: its test catalog lacked the network's cubic water (`restrict` without `withWater`) |
| 1 | `equipmentGates` | 20 | 186 | 0 | `UnitTextTest` among them |
| 2 | `columnGates` | 1 | 3 | 0 | `V3FeedFlashStabilityTest` after the test fix |

Gradle also ran three compiles (`testClasses`); the probes and the trace ran on the built classes, without Gradle. No whole
task, no GameTest, no chain-100, no campaign.

Printed lines (`tools/floating-roof-tanks/final-lane/printed.sh`, times and allocation bytes masked), 36 classes that print,
against the lane at `9b1dd2bd6` and the 0.34.0 baseline. Every line that differs from the lane's:

| Class | Lane | Now | Base |
|---|---|---|---|
| `FlowsheetAqueousColumnTest` W5 hexane sheet | FAILED, `column.failed` (no split) | STEADY, `column.two_liquids` [8], `column.two_liquids_distillate` | as now |
| `V3FeedFlashStabilityTest` | – | two new lines: one liquid at 320 K (H −31,422.480635) and 345 K (H −36,332.172964), Wilson endpoint | – |
| `CrudeUnitSampleTest$SimulatorRunTest` SAMPLE_BODY packed | 23,187 | 23,185 | 23,186 (wall-clock seconds, section 3) |
| `ColumnFeedOnePhaseOracleTest` | 13 lines | the same 13, values equal, composition maps in another order (`Map.of` iterates in a per-JVM order) | 13 + 1 (the announced CO2 line) |
| 7 runtime classes (`ColumnRunTest`, three checkpoint tests, `MixedSetMergeTest`, `WorldTopologyLedgerTest`, `GaugeAccessoriesTest`) | a log4j console warning | none | as the lane |

The plunger lines (`PlungerPumpMoverTest`, `FlowsheetUnitsTest` `PLUNGER_SHEET`) equal the lane's. Against the baseline
only the announced moves differ (P1's plunger lines, the three wash values of section 3, the oracle's CO2 line).

**Not compared:** the 44 other `columnGates` classes of run 1. They passed, but run 2 (one filtered class in the same task)
replaced `build/test-results/columnGates/`, so their printed lines were not kept. Their flash answers are covered by the
grids (dense and coarse: equal to the lane's to the bit) but the column solves' printed lines are not read here.
