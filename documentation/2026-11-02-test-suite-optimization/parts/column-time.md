# Column tests: wasted run time

Scope: every test of 0.5 s or more under `science.column` in `tmp/test-timing/cases.csv`. Proposal only, nothing was run. Seconds saved are estimates. Inputs: `BASE` = CDU17, 30 trays, 250 kPa, 750 Pa per tray, 332.15 K condenser, reflux 4.17, 8 MW, no draws, steam or coolers. `PILOT` = `Cdu17TestCatalog.pilotInput()`, 29 trays, 150 kPa, 400 K, reflux 2, three draws. `LIT` = preset `literature_tia_juana`, 40 trays, draws, steam, coolers. `METH` = preset `tia_juana` at 8 m. `N2` = preset `tia_juana_nitrogen`.

- Every row is a cold classical solve (`V3ColumnCalculator.calculate`, no model) unless it says LEARNED or AGGRESSIVE. Java or C++: no row gains. The C++ pass covers only the first trial and gave no speedup (batch 2026-10-28-native-column-default).
- No test here mutates its result or needs a repeated solve. `aStalled…` needs a checkpoint count, and one solve gives that count.
- Memo: `V3TestInputs.solved(input, closure)`, one map per JVM, key = input (`V3ColumnInput.equals` compares every field), closure and catalog. It keeps the checkpoint count and runs under the 60 s budget the callers assert. `V3PumparoundSteamCalculatorTest.wetBaseline` already works this way. A memo cannot cross `test` and `slowGates`: they are two JVMs.
- Saved seconds use warm solve times (BASE 0.35 s). The first crude solves in a JVM also pay for JIT warm-up. That cost moves to the next solve and is not saved. `cases.csv` does not say which closure test a `[2] B` row belongs to: I read the slower row as the default closure.

| test | seconds | what it solves | shared with | proposed change | estimated seconds saved |
|---|---|---|---|---|---|
| `RegroupedCrudeTest.previouslyDifficult…` | 22.35 | bonga, cold_lake_blend at 8 m | nothing in a merge lane | OWNER: the 2 solves on 2 threads, wall time only | 3.5 |
| `RegroupedCrudeTest.gameplayDraws…` | 15.19 | 5 presets at diameter 0 | nothing | OWNER: the 5 solves on 4 threads, wall time only | 9.0 |
| `V3SideDrawCalculatorTest.anOverSpecified…` | 8.92 | 30 trays, draws too large, screen off; the solve fails | nothing | OWNER: same contract on a 15-tray column; the tray and stage names in its assertions change; one probe must show it still fails | 6.0 |
| `V3SideDrawCalculatorTest.modestThirtyTray…` [1] to [4] | 0.51, 0.57, 0.53, 0.52 | 30 trays, 0.25× draws at 150, 100, 250 kPa, 100 kPa with cutoff | nothing | OWNER: drop [3], one more pressure of the same rule (S2) | 0.5 |
| `V3SideDrawCalculatorTest.theThesisArranged…` | 1.18 | 30 trays, 0.40× draws, 3 coolers | nothing | none | 0 |
| `V3PumparoundCalculatorTest`: `aOneKilowatt…`, `uniformFive…`, `theCondenserDutyBound…`, `theThesisThreeCooler…`; `V3ConvergenceClosureTest` default [1] A; `V3ColdStartTest.v1Scale…` (30 trays) | 0.87, 0.94, 0.79, 1.55; 0.51; 1.21 | BASE, then one other input each | BASE runs 7 times, with `coolingAbove…` | SAFE: memo | 2.1 |
| `V3PumparoundCalculatorTest.returnTraySplit…` | 0.57 | 5 MW uniform and 5 MW return tray | uniform input with `uniformFive…` | SAFE: memo | 0.3 |
| `V3PumparoundCalculatorTest.coolingAbove…` | 0.60 | BASE, then 1.02 × its duty | same two solves in `V3PathDependentInfeasibilityTest.aStateDependent…` (0.51, `test` JVM) | OWNER: remove (S1); that test asserts all of this and the solve path | 0.3 |
| `V3PumparoundCalculatorTest.aStalled…`; closure default [4] D | 1.03; 1.19 | BASE with 40 MW on one tray | each other | SAFE: memo, the test reads the stored checkpoint count | 0.9 |
| `V3PumparoundCalculatorTest`: `threeDefault…`, `aCoolerCancelled…`, `tjl19Package…` | 1.02, 1.62, 0.50 | PILOT with a cooler; PILOT with and without a cancelling pair; TJL19 with 5 MW | nothing | none; the pair test needs both solves | 0 |
| `V3ConvergenceClosureTest.theGlobalEnergy…`; default [2] B; loose [2] B | 1.86; 1.35; 0.63 | case B at both closures, twice | each other | SAFE: memo | 1.6 |
| closure default [3] C; `V3PumparoundSteamCalculatorTest.tjl19WithSumpSteamAndSideDraws…` | 1.11; 0.55 | TJL19, steam, 3 coolers, 3 draws | each other, same input | SAFE: memo | 0.5 |
| closure default [5] E; loose [3] C, [4] D, and loose [1] A, [5] E (below 0.5 s) | 0.73; 0.65, 0.70; 0.31, 0.38 | evaluation cases | nothing | OWNER: loose closure on B, C, D only; A and E add no new feature (S2) | 0.7 |
| `V3ColdStartTest.reasonableSetupMatrix…` | 3.10 | 22 cold solves of the two-tray binary | `feed_k_551.0` is `nearby551…` | SAFE: share the 551 K solve (0.1). OWNER: drop 8 one-factor points the corners cover (flow, pressure, condenser, reflux); `accepted >= 5` must still hold (1.1) | 0.1 + 1.1 |
| `V3ColdStartTest.v1Scale…` (2 to 15 trays) | in 1.21 | same specs at 2, 4, 8, 15 trays | nothing | OWNER: drop 4, 8, 15; it asserts only "no internal error" | 0.4 |
| `V3ColdStartTest.onePointOneBar…`, `onePointFiveBar…` is below 0.5 s | 2.72 | low-pressure 30 trays | nothing | none | 0 |
| `V3AggressiveStrategyTest`: `fortyTwoTray…`, `coldFeature…` | 4.33, 1.82 | AGGRESSIVE, no seed: 42 trays; LIT at 8 m | nothing | none; the path "15-30-42" needs the 42 trays | 0 |
| `V3MethaneColumnTest.methaneFeed…`; `V3TrayHydraulicsColumnTest.theDefaultPreset…` | 2.66; 1.53 | METH | each other, same path | SAFE: memo; keep the JSON writes; `forcedCurrentNeverTouchesModel…` still pins "no model" | 1.5 |
| `V3MethaneColumnTest.defaultNeuralFirst…` | 2.55 | METH, LEARNED | nothing, other options | none | 0 |
| `V3NeuralInitializationTest.exhaustedBudget…` | 2.52 | binary, with a real 2.5 s sleep | nothing | SAFE: budget 300 ms, pause 400 ms, same assertions | 2.1 |
| `V3LiteraturePresetTest.theLiteraturePresetPublishes…`; `V3StageContinuationTest.theWetLiteraturePreset…` | 1.10; 0.97 | LIT, no diameter | each other; feed differs in the last digits, the fixture's water record differs in revision and source text only | SAFE: move the second test's assertions to `V3LiteraturePresetTest`, on one static result | 0.9 |
| `V3LiteraturePresetTest.aHalvedTopCooler…` | 3.92 | LIT with half the top cooler | nothing | OWNER: warm from the preset's profile; the assertion needs no cold start; unproved that the warm route converges | 3.4 |
| `V3StageContinuationTest.theWetPresetAtHalfSteam…` | 2.91 | LIT at half steam | nothing | none; the cold resume is the claim | 0 |
| `V3ColumnFeedAndDrawLimitsTest`: `aTwoPercentNitrogen…`, `aFourthSideDraw…` | 2.81, 1.71 | N2 with 2 % N2; METH with a fourth draw; LEARNED, the model declines | nothing | none | 0 |
| `V3TrayHydraulicsColumnTest.anUndersized…` | 1.62 | METH at 5 m | nothing | OWNER: warm from the 8 m solve; the flood warning needs no cold start | 1.2 |
| `V3ColumnCalculatorTest`: `…SumpSteam…`, `…ColdCondenser…`, `…Intermediate…`, `…WaterLimited…`, `…Eighty…` | 0.74, 0.70, 0.70, 0.74, 0.86 | PILOT with steam, 5 condenser or steam points | nothing | OWNER: drop `…WaterLimited…` (353.15 K): same assertions as 343.15 K (S2) | 0.7 |
| `V3PumparoundSteamCalculatorTest`: `sumpSteamWithOne…`, `…ThreePumparounds…`, `tjl19SolvesSumpSteam…`, `…ZeroReboiler…` | 0.87, 0.79, 0.86, 0.52 | CDU17 or TJL19, steam, coolers | `wetBaseline` is shared already | none | 0 |
| `ColumnSolveTest`: `atAPrescribedDrop…`, `aStartSolves…` | 6.61, 2.41 | N2 by `ColumnSolve.run`, AGGRESSIVE, one cold start and one warm solve each | nothing, the designs differ | none; 6.6 s is probably JIT warm-up: the first crude solve of the `test` JVM | 0 |
| Holland `solverMatches…`, Sotelo `quarterLoad…`, `aLongContinuationPath…`, energy-shift `thePredictor…`, `aStateDependent…` | 0.65, 0.62, 0.60, 0.54, 0.51 | references and one path contract each | nothing in their JVM | none | 0 |
| all 226 tests of classes with `Cdu17FixtureExtension` | about 2 of their time | the catalog is parsed again before every test (9 ms) | nothing | SAFE: cache `Cdu17TestCatalog.withProduction()` per base catalog; the memo needs one catalog for its key | 2.0 |

Totals: SAFE 12.0 s, OWNER 26.8 s (12.5 s of it wall time only, from threads), together 38.8 s of the column tests' 128 s.

## RegroupedCrudeTest

- `gameplayDraws…`: 5 solves (tia_juana, wti_light_export, upper_zakum, bonga, dalia) at diameter 0, no tray hydraulics. Estimate from the table in `TRAY_PRESSURE_IMPLEMENTATION_NOTES.md` section 6.5, scaled to 15.2 s: about 2.0, 3.1, 1.6, 4.7, 3.8 s. No other test solves these inputs: a diameter moves every volume. All 5 stay: the fixture is the only pin of these captured volumes (K4).
- `previouslyDifficult…`: 2 solves at 8 m, about 3.5 s (bonga) and 19 s (cold_lake_blend). Only `ColumnRegroupingGameTests` solves them again, and no lane script runs that task, so this test is the merge pin of both. It stays cold: "without learned seeds" is its claim. cold_lake_blend alone is 15 % of the column time. The cost is in the solver, not the test: one trace of its solve path would show why it costs five times bonga.
- Threads: no shared state in this class, `MaterialRuntime.pinned` scopes each worker, and the fork risk (a wall-budget hold gate) does not apply, because tests still run one at a time. Alternative: warm `gameplayDraws…` from the 8 m solves. Only tia_juana and bonga have one: about 5 s, not counted.
- Also not counted: a separate column Test task with up to 4 forks would save about 60 s of wall time, more than any row here, but each fork pays its own warm-up and the memo splits. `-PtestForks=4` on `slowGates` itself stays out.
