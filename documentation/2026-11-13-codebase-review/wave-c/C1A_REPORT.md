# C1a Water module and the kernel's duplicates: report

Batch C1a of `WAVE_C_BRIEFS.md` (thread wave-b-t1, Opus high), branch `claude/review-wave-b-t1-thermo-e26136` after merging the review branch at `90d1a9e03`. **Tests pending the lane** (no Gradle run yet). Main thread decisions 2026-11-13: the mapping in `science/material/WaterRecords` (no cache: hot callers hold the constants), the `HydrocarbonModel` 73 call, the two gate-group lists in `build.gradle`; the stated shift accepted.

## Commits

| Finding | Commit | What changed | Lines (src/main) |
|---|---|---|---|
| S08 | `f442031ad` | `PengRobinsonKernel.cubicDenominator`, `cubicPressure` and the four pressure derivatives, in `TranslatedPengRobinson.fill`'s order; `fill`, `Values.pressureVolumeDerivative`, `PhaseIdentification.parameter`, `WaterShare.pureCubic`, `FluidTpEquilibrium.cubicPressure` call them. The share's `-rt`, `2.0 * rt` are `(-R) t`, `(2 R) t` exactly | +60 −24 (four copies → one) |
| S12 | `57828dc8e` | `science/math/SmallDenseSolve`: `cholesky`, `solveCholesky`, `factorShifted`, `solve3`, `solvePivoted` (was `solveSmall`), operation for operation, from `TangentPlaneStability` and `FluidTpEquilibrium` | moved 101 |
| S16 | `182e1cd01` | `science/thermo/SubstitutionAcceleration`: the factor `lambda/(1 - lambda)` (all three), the engine's acceptance `0 < lambda < 1` and cap scale (flash, three-slot balance), the one limit 20. The stability test keeps its own acceptance (`den > 0 && num < den`) and cap (`extra = 20/largest`): merged, they differ where the quotient rounds to one, so fewer lines go than the review's −40 | −11 +helper |
| F2, X39 | `8e74115ce` | the module `science/thermo/water` (below); one commit, since `WaterShare` and the module's first callers compile only together | +631 −319 |
| F6 | `e0d64cf4a` | `HenryConstant` reads `WagnerPruss` (no column import); its stale javadoc corrected | 6 |

The module: `WaterConstants` (the record's doubles; it imports no material type), `WagnerPruss` (pressure, log slope, the 80-step bisection; the column's association), `Iapws97Region1` (one table; `evaluate` is the share's superset, nine sums term by term in table order; `liquid` is `WaterRegion1`'s held continuation), `Iapws97Region2Ideal` (from the share), `SteamIdealGas` (Shomate, held past 273.16 to 900 K), `WaterDatum` (liquid offset, Watson), `DiluteSteamViscosity` (from `WaterRegion1` 143-169), `SteamVirial` (moved from `thermo/phase`). Callers keep their API and delegate: `V3WaterProperties` (holds the constants per thread in its existing scratch), `WaterRegion1` (a facade over `Iapws97Region1.liquid`), `WaterShare` (keeps the gaps, the weight, the fades and its below-triple-point continuation with entropy; its Region 1 table, Region 2 ideal part, Wagner line and hard-coded record numbers are gone; it takes the `WaterConstants`), `TrainedPhysics.Water` (Wagner, Watson and Shomate through the module), `FluidThermodynamics` (its four water calls; holds the constants in a field), `HenryConstant`.

Region 1 confirmed bitwise before merging: the two evaluators had the same power tables, the same per-term expressions and the same accumulation order for each of the six shared sums; the superset adds `g`, `gppp`, `gppt` as independent accumulators, and every property formula matches (`R*T*gp/P*`, `R/P* (gp - tau gpt)`, ...). Hard-coded numbers in `WaterShare` against the record: Wagner coefficients, Tc 647.096, Pc 22.064e6, triple point 273.16 identical in every digit (the bundled and every test overlay's `water.json`). `RS = 461.526` is IF97's constant, not a record field: it stays (`Iapws97Region1.GAS_CONSTANT`). No second shift.

Imports that no longer cross the wrong way: `HenryConstant` (material) → `column.v3.thermo.V3WaterProperties`; `V3WaterProperties` (column) → `fluid.thermo.WaterRegion1` (network); `FluidThermodynamics` (network) → `column.v3.thermo.V3WaterProperties` (three FQN calls); the column → `thermo.phase.SteamVirial`.

## Stated shifts

- The one reader of the line in `WaterShare` is `liquidGap`: `p_sat(at)` and `d p_sat/dT` set the continuation below p_sat, and the branch test is `p >= p_sat`.
- The share's h, s and v below water's saturation pressure move in their last digits, nothing at or above it, chain-100 untouched (350 K, about 1.5 bar, p_sat 41.7 kPa).
- `TrainedPhysics.Water.saturationPressure` moves about 1e-16 onto the V3 form (both sides of its comparison use it).
- Size, from `WaterModuleTest` (printed when it runs): p_sat moves by a few ulps of `(Tc/T) sum a_i theta^e_i`, so about 1e-15 relative above the triple point and up to Tc/T times that in the supercooled continuation; the test bounds it at 1e-14 max(1, Tc/T), d p_sat/dT at 1e-13 max(1, Tc/T).
- Expectations that move: none found by reading. `WaterShareTest`'s Maxwell test takes p_sat as its probe pressure (moves 1e-15, tolerance 1e-4 of v); `AqueousNetworkGatesTest` compares with tolerances. To be confirmed at the lane.

## Tests (pending the lane)

New: `WaterModuleTest` (every module function against the old formulas written out, bit for bit, on a grid with the triple point, the critical point, 1 to 273 K, 623.15 K and past 900 K; the share's shift bounded and printed), `CubicVolumetricBlockTest` (S08, 240,000 comparisons). Moved imports: `SteamVirialTest` (stays in `thermo/phase`, its helpers are there), `FluidPropertyCoverageTest`, `V3WaterPropertyDerivativesTest`, `WaterShareTest` (p_sat from the module). To run: `compileJava compileTestJava`; then `test --tests` on `WaterModuleTest`, `CubicVolumetricBlockTest`, `WaterRegion1Test`, `WaterShareTest`, `SteamVirialTest`, `AqueousNetworkGatesTest`, `DerivedPairsTest`, `CubicPhaseEvaluatorTest`, `TangentPlaneStabilityTest`, `FluidTpEquilibriumTest`, `FluidTpEquilibriumPhUvTest`, `FluidPropertyCoverageTest`, `RangeContinuationGateTest`, `DirectLiquidDeterminismTest`; `columnGates --tests` on `V3WaterPropertyDerivativesTest`, `V3FreeWaterTrayTest`, `V3SteamFeedContractTest`.

Not done: nothing of the brief. Not in this batch (as the brief): S04, S05, F9, the shared root finder, other lanes' constants.

## Run by the main thread (2026-11-13)

The thread could not be reached (the app paused cross-session messages), so the main thread test-merged this branch into the review branch and ran the named classes itself; all pass. Counts and the merge commit: the batch README history and `wave-b/FINAL_LANE_REPORT.md` (both final lanes of the review branch include this code). Any "tests pending" or "results pending" line above is superseded by this note.
