# Part: science tests (fluid, thermo, material, column)

Scope: the rows of `added-by-file.csv` under `science/fluid/`, `science/thermo/`, `science/material/` and `science/column/`: 23 files, 110 added methods, 19.5 s. Proposal only. Nothing was changed and nothing was run. The guards below come from reading the code.

| class | methods | verdict | methods to remove | reason and named kept guard |
|---|---|---|---|---|
| `science/fluid/network/HeatCurveTest` | 11 | TRIM | `agreesWithTheConstantCpExponentialAndItsEnergyBalance` | S1, A2: a second pin of one K4 reference. Kept: `latentHeatLeavesAtConstantTemperatureBeforeTheLiquidCools`. Past the plateau it reaches the same `within` call with the same arguments (film 1, difference 100, exposure 4000) and asserts the same `400 000/e` J/kg and `300+100/e` K. Its tolerances are looser (1e-8, 1e-10), but the bits are the same. `beyondItsKnotsTheCurveExtendsItsEndLines` covers the entry at the top knot. |
| `science/fluid/network/HeatCurveTest` | | TRIM | `heatingAndEitherFlowSignGiveTheSameMarch` | S1. Heating: kept `beyondItsKnotsTheCurveExtendsItsEndLines` (`450-150/e`, a heated stream on the same segment) and the heating case of `aLinearFilmIntegratesExactlyAcrossSegments`. Flow sign: no production caller passes a signed flow to `march`. `HeatSide.heat`, `AirCooled.heat` and `ExchangerRelay.Stream.through()` all pass magnitudes. A reversed stream stays pinned by `aSideMarchesItsCellsInFlowOrderWithItsBypassAndGiveBack` and `HeatSideNetworkTest.aReversedSidePassesItsCellsInTheOtherOrder`. |
| `science/fluid/network/VesselGeometryTest` | 6 | TRIM | `hydrostaticHeadUsesLayerHeightsAndDensities` | S2. `VesselGeometry.head` has no branch on the form. Kept: `bootHeadStandsOverTheBodyBottom`, lines 42-43: the same two layers at 1000 and 800 kg/m3 through `head`. A sphere's `heightAt` stays pinned by `everyProfileIsMonotoneAndRoundTripsIncludingBootAndEndpoints` and `independentTenPercentHeightReferences`, and `ProcessOutletTest.aSlurryHeadWeighsItsSolids` runs `head` on a sphere. |
| `science/material/FluidAppearanceTest` | 1 | TRIM | `bundledCrudeFractionsDarkenWithNormalBoilingPoint` | S3: a colour pin. K6 read narrowly excludes a wrong look. No saved format and no AGENTS.md rule names the order; `thermo-materials.md` calls the colours a precomputed presentation palette. Judge: `tools/sight-glass-bubbles/palette.py` derives each colour from the boiling point through fixed anchors and draws the swatch chart. Owner decision S10 (the field is required) stays with `recordsWithoutAppearanceAreRefused`. Owner to confirm: the review of 2026-10-25 lists darker heavy fractions as expected behaviour. If the owner asked for that, this is K3 and it stays. |
| `science/fluid/network/ExchangerRelayTest` | 3 | C1 | none | In `theBypassAndTheWallMeetTheSetPointTogether`, delete lines 49-51: a timing loop of 50 plans and its print (A4), with no assertion. Every assertion stays. Saves about 0.03 s of the test's 0.039 s. |

Totals: 110 methods reviewed, 4 proposed for removal, 0.00 s saved (`cases.csv` gives all four 0 s), and about 0.03 s from the C1 row.

## Why so few

- 15.2 s of the 19.5 s is the four tests of `ColumnSolveTest` and `V3AggressiveStrategyTest`. Their run time is the other reviewer's. The next largest is `PressureValveContinuationTest` at 1.4 s, a conservation closure and a determinism gate.
- `OpenTankTest`, `ColumnCycleTest`, `PressureValveContinuationTest`, `PumpRateWarmStartTest` and `HeatSideNetworkTest` hold the conservation closures of their paths. Those are never candidates.
- The six new `NetworkPhaseEngineTest` methods are the gates of the PH false root and TP steam refusal batches. `SteamVirialTest` is an IF97 reference. The new `FlowControlTest` and `MaterialCatalogTest` methods encode owner rules.

## Looked at, stays

- `V3AggressiveStrategyTest.fortyTwoTrayAggressivePathUsesTheIntermediateSeed` (4.33 s). This is the largest possible saving. A slow solve is not K6 read narrowly, and the test's real gate is the path string, which one line in `V3StageContinuationTest` could pin: `continuationStageCounts(42, AGGRESSIVE)`. But no kept test is shown to solve the aggressive ladder. `coldFeatureColumnPublishesTheBoundedFastCertificate` may publish from its direct cold attempt, and nothing reads its path. With no named guard it stays. One printed path decides: if that test's path holds `15-30-40`, this method is S2 and saves 4.3 s. For the run-time reviewer; do not count it twice.
- `ProcessOutletTest.aGasOnlyStateHasZeroLiquidStreamVolumes`. It looks trivial, but `bootFallsBackToHydrocarbonAndThenGas` reads `PipeTransfer` volumes, which use `state.liquidVolume()` and not `phaseVolume`. No other test reads `phaseVolume` on a state without liquid.
- `MaterialCatalogTest.aViscosityRevisionBumpMovesOnlyTheFluidIdentity`. `everyPackageIdentityListsTheDeclaredRevisionOfEachRecordItNames` implies its identity strings, but AGENTS.md names the rule (K3) and the neural assertion is its own.
- `FiredCoilTest.aCoilValidatesItsFacts` and `ConvectionCoilTest.aCoilValidatesItsFacts`. `FluidCheckpointCodec` lines 401-402 build these coils from saved values, so they are K2.
- `V3VacuumStartupTest.aDryTrayHasOnlyGasPressureDrop`. `V3TrayHydraulicsTest` covers a tray with no vapour. This is the only test of a tray with no liquid.
- `FlowControlTest.aBareValvePassesReverseFlowLikeItsPipe`. A configured valve blocks reverse flow, so this is the only guard that a bare one does not.

## If approved

Update the counts and names in the code guide: `HeatCurveTest` (11) in `verification.md`, and `FluidAppearanceTest` (6, incl. the removed name) in `thermo-materials.md` line 433.
