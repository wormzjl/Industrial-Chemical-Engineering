# Test archive 2026-11-02

Purpose: the 20 test methods the owner approved for removal on 2026-11-02, with source copies and a re-attach patch. Each
one failed the bar of `documentation/2026-11-02-test-suite-optimization/CRITERIA.md`: it repeats a check another test
makes, or it pins a drawn value that no rule or format names. The per-test reasons are in that batch's `parts/`.

Batch: `2026-11-02-test-suite-optimization`.

Removing commit: `c1b35ce2d` (branch `claude/test-suite-optimization-8fbeab`, parent `5b0d574af`).

The 11 weak candidates of the same review were not removed. They are named in `TEST_SUITE_REVIEW.md`.

## Contents

- `sources/`: the 10 files the commit changed under `src/`, as they were at `c1b35ce2d^`.
- `reattach-src.patch`: `git diff c1b35ce2d c1b35ce2d^ -- src`; restores all of it.

## How to run: re-attach

From a worktree root at `c1b35ce2d` or a descendant that has not touched these files:

    git apply --check tools/test-archive-2026-11-02/reattach-src.patch && git apply tools/test-archive-2026-11-02/reattach-src.patch

To bring back one method, copy it from `sources/` (or `git show c1b35ce2d^:<path>`); the two agree. In
`ColumnBlueprintTest`, five of the methods also need the private `link` helper, and the imports of `Fitting` and `Kind`.

## What was archived, and what it leaves uncovered

| Class | Method | Kept by, or lost |
|---|---|---|
| `FlowsheetPainterTest` | `marksStayInsideTheBoxTheyAreDrawnFor` | Renders. It was the only caller of `FlowsheetPainter.rounded`, which stays in the code with no caller. |
| `KitLayoutTest` | `compactMetricsPlaceTheSameRowsShorterAndAChoiceDrawsItsTitleOnly` | The door sweeps run in COMPACT. The 11 px height is left to the renders. |
| `PhysicalFluidTopologyTest` | `aLaidPipeBesideAHandPlacedOneDoesNotJoinIt` | `aTeeLaidBetweenTheLinesJoinsThemOnlyWhereBothHaveTheJoint`, `twoLinesLaidSideBySideStayTwoLines`. |
| `AssemblyMatcherTest` | `aColumnStrippedBySumpSteamHasEveryPortPipedAndNoVapourReturn` | `everyCaseOfTheDesignTableIsCompleteOnItsPlantBuiltAsDesigned`. |
| `ColumnBlueprintTest` | `withoutAKettleTheBottomsLeaveThroughTheirValve`, `theRefluxLineIsInEveryDesignWithItsTeeAndTwoValves` | The same sweep builds these plants. |
| same | `eachDrawSteamStageAndPumparoundHasItsNumberedSlots`, `theEjectorComesWithItsFlagBeforeTheCondenser` | The same sweep. Lost: an air cooler in an exchanger's slot reads WRONG_KIND, not MISSING; the EJECTOR slot's place in the list. |
| same | `aColumnWithoutAComponentDesignHasNoBlueprint` | No path reaches it: both callers test for null first. |
| `PipeRackGeometryTest` | `anOpenLaneEndIsBlindFlangedAndAJoinedOneIsNot` | `LineGeometryTest.unconnectedEndsCloseInABlindFlange`, the `flanges` render. |
| same | `theBentsStandWhereTheFrameSays`, `aJunctionsNeighboursStandAWholeBentAgainstIt`, `aStackedRackCarriesTheColumnsUpToTheRackOnIt` | `tools/pipe-laying-renders` (`stacked`, `flanges`, with `-Dclash`), which a person reads; no gate runs it. |
| `PipeRouteTest` | `anAimedStartLeavingAwayComesRoundOutsideItsCell` | `everyAimedRouteAlongARunIsOneTheServerLays`. Lost: "this route is found" and BLOCKED for the next cell. |
| `ShapeRasterizerTest` | `skirtHoleTakesTheLinesSize` | `aTurnedBottomOutletTurnsItsPortAndSlots`. Lost: lines of 0.1 and 0.6 m, and other diameters at a chosen line size. |
| same | `tankGaugeFacesPointOutwardsIncludingTheUnderside`, `tankGaugeHasAClearGlassSleeveAndConnectedEndPipesInEveryHeading` | `tools/tank-gauge-enclosure`, which a person reads; `everyPieceStaysInsideItsCellAndFacesOutwards` keeps the glass's winding on the sampled tanks. |
| same | `theLargestTankLaysOut` | `EquipmentLayoutGoldenTest.everyLayoutIsTheRecordedOne` holds this tank's cell count exactly. |
| `HeatCurveTest` | `agreesWithTheConstantCpExponentialAndItsEnergyBalance` | `latentHeatLeavesAtConstantTemperatureBeforeTheLiquidCools` reaches the same call with the same arguments. |
| `VesselGeometryTest` | `hydrostaticHeadUsesLayerHeightsAndDensities` | `bootHeadStandsOverTheBodyBottom`. |

## History

- 2026-11-02: archived by `c1b35ce2d`. The suite went from 2071 runs to 2051: exactly these 20 names, none added, 0 failures.
