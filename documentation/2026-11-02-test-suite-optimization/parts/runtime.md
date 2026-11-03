# Part: runtime tests

Scope: the 28 rows of `added-by-file.csv` under `runtime/` (fluid, instrument, assembly, root). That is 193 added methods (fluid 143, instrument 29, assembly 18, root 3). These files take about 9 s in total, and that figure includes their older methods.

Finding: this area has very little to remove. Most methods are formats, conservation closures, determinism gates, owner decisions or the only check of a physical result. Four methods are proposed. They save 0.42 s, and one of them gives almost all of it.

## Proposals

| class | methods | verdict | methods to remove | reason and named kept guard |
|---|---|---|---|---|
| `runtime.fluid.PipelineFittingTest` | 21 | TRIM | `eligibilityAndMeshesCoverBothAxesHeadingsAndDiameterExtremes` (0.397 s) | S3. It pins the inline mover's mesh: finite vertices, the motor above one block, the flow arrow, the support foot, the block-face flange, the winding. No owner rule or saved format names them. `tools/inline-mover-accessories/InlineMoverRender.java` draws 242 views from `InlineMoverGeometry.polygons` (seven bores, four headings, both types). The `supports` table only refuses a click in `PipelineFittingItem`. The server's refusals stay in `eachHorizontalHeadingInstallsOneMoverWithoutChangingHostOrOwnedContents` and `verticalHostsAndConflictingOrDisabledMoverControlsAreRejected`. Lost: an automatic check of the foot, flange and winding. |
| `runtime.fluid.PhysicalFluidTopologyTest` | 30 | TRIM | `aLaidPipeBesideAHandPlacedOneDoesNotJoinIt` (0 s) | S2. `Device.joins` needs a joint on both sides, and a hand-placed pipe is a pipe with every joint. So this is the case "a joint faces a pipe without one". `aTeeLaidBetweenTheLinesJoinsThemOnlyWhereBothHaveTheJoint` keeps that case in its one-sided half, and `twoLinesLaidSideBySideStayTwoLines` keeps `ALL_FACES` joining. `parallelLanesInOneRackStayApart` keeps the "NO FLOW" line of an unconnected pipe. |
| `runtime.instrument.SensorReadingTest` | 4 | TRIM | `futureTargetsUseHeightGeometryAndRetainBootDatum` (0.003 s) | A5 in spirit. `ControlBinding` is a contract for a later cable batch ("no cable graph or level regulation is enabled here"). It has no caller in `src/main` and no saved form, so a break cannot reach a game. `VesselGeometryTest.everyProfileIsMonotoneAndRoundTripsIncludingBootAndEndpoints` and `bootHeadStandsOverTheBodyBottom` keep `volumeAt`, boot heights included. The cable batch brings its own test. |
| `runtime.instrument.GaugeAccessoriesTest` | 13 | TRIM | `housingIsClosedAtObliqueAnglesAndEveryConnectorRemainsFinite` (0.017 s) | S3, weak. It pins the dial's closed shell, its layer order, the probe depth and the connectors. `tools/accessory-refinements/AccessoryRefinementRender.java` draws 123 views, and its `audit()` checks finite vertices and the layer order. Two limits: the render still names `InstrumentSockets`, which is gone (`InstrumentGeometry.sockets` replaces it), and the shared-edge closure check exists only in this test. The owner may keep it. |
| `runtime.fluid.ExchangerCouplingTest` | 13 | C1 | none | `aCondensingMixtureCoolsAgainstWaterAtTwentySecondSlices` (0.74 s): remove the 50-run `System.nanoTime` loop. It only prints a time, no assertion reads it, and the lane reads no such line. Every assertion stays. Saves under 0.1 s (estimate, nothing measured). |

Totals: 193 methods reviewed, 4 methods removed, 0.42 s saved. The C1 row saves under 0.1 s more.

## Fails narrow K6, no live guard, so it stays

These 12 methods claim only a look, a sound or a refused click, and they total 0.17 s. The owner may remove them by hand.

- Sight-glass drawing: `PipePresentationTest.motionIsStableContainedAndBetweenTheLiquidAndGlass` (0.157 s), `bubbleTravelFollowsEveryRunAndAgreesThroughBothWindows`, `gasLiquidRatioControlsBubbleDensityAndOtherPhasesRetainHaze`. S3 fits, but no render judges them today: `BubblePreview`, `FlowPreview` and `SightGlassRender` still call `SightGlassBubbles` and `SightGlassDisplay`, which are gone. Port them first if these should go.
- Bubble direction: `PipePresentationTest.acceptedDirectionReversesAndMissingLocalMappingsDoNotInventMotion`, `PhysicalFluidTopologyTest.pipeViewsDescribeGraphForwardDirectionOnEveryWorldAxisIndependentOfIdentityOrder`, `endDrivenRunsSwapTheirLocalFlowDirectionWithTheGraph`, `localFlowDirectionFollowsTheSectionRatherThanTheEndpointsOfABentRun`, `aConstructedMoversDischargeUsesItsNozzleContactInsteadOfItsRemoteSuctionDatum`. `flowDirection` reaches only the sight glass. Each covers its own branch, so none is an instance of a sweep.
- Mover sound: `PipePresentationTest.soundGainIsBoundedAndIncreasesWithConstructorDiameterAndStageOrThrowCount`, `foundationAndHeadingDoNotMakeTheMachineLouder`. Nothing else reads `UnitDesign.soundGain`, and audibility is an owner check.
- Refused clicks: `GaugeAccessoriesTest.pipeSocketsRejectBendsAndJunctionsAndShareSelectionBounds`, `PipeLineResizeTest.aLineLongerThanTheLimitIsRefusedWhole`.

## Looked at and kept

- Never candidates: all of `FluidFactsCompatTest`, `AccessoryStoreCompatTest`, `AssemblyStoreTest`, `SensorStoreTest`, `VesselRebuildTest` (D7), the new cases of `FluidCheckpointCodecTest` and `FluidPacketCodecTest`, and the round trips in `PipelineFittingTest` and `GaugeAccessoriesTest`.
- `ColumnRunTest` (25): closures, determinism gates, owner rules Q1 and Q5, "Lower target wins" and the merge review's findings. `aPumpOnTheFeedLineRidesBehindItsValve` looks like `PumpAheadOfFlowValveTest.aTargetBelowThePumpsRateRegulatesWithThePumpOnItsHeadLimit`, but only it runs a pump behind a column's port plan.
- `ExchangerCouplingTest` (13), `HeatSideTopologyTest` (9): each is its own plan gate on the running network. `FiredCoilTest`, `ConvectionCoilTest` and `HeatControlTest` test the laws alone, so they do not imply these.
- `RegulatingValveHoldTest.aLiquidProductEntersALineHoldingItsGasAndTakesNewTargets`: its 546.3 K run is a prefix of `aDryLineKeepsRunningWhileItsOldGasWashesOut`, but its 486 K run is its own. A merge would save 6 ms and only lower the count, so it is not proposed.
- `GaugeAccessoriesTest.temperatureIsUnavailableUntilAcceptedFlowThenRetainedStale` is not implied by `SensorReadingTest.aPipeTemperatureNeedsAcceptedFlowAndRetainsItsOriginalSampleTimeWhenStopped`: `Instrument.Sample.temperature` and `Sensor.read` are two code paths.
- `GaugeAccessoriesTest.nativeReservoirSocketsStayDistinctUprightAndStableAcrossHeadingsAndSizes` and `levelPadsRemainDistinctAcrossNativeSizesAndHeadingsAndCoversAreClosed`: their mount ids are saved in `Instrument.Key`, and they are the only tests that tie those ids to the geometry.
- `OpenTankRuntimeTest` display and watcher cases: owner decisions FR4 and FR7. Assembly session and watch tests: the engine-owned presentation rule. `PresentationMailboxTest`: `submitImmediate` and `requestImmediate` have production callers.
- `TracePhaseFoldTest.waterThroughATankHoldingACrudeTraceKeepsUp` (1.2 s, the largest added method): a D41 hold-class world. It must reach tick 5580, so it has no cheaper form.

## Notes

- S3 here rests on a person reading the renders. No gate task runs them. The render claims come from a name check. Nothing was compiled or run.
- The removals change the code guide: `client-gui.md` (line 359), `equipment.md` (229), `instrument-accessories.md` (77), `pipe-laying.md` (298), and the test counts in `verification.md`.
- `cases.csv` holds `runtime.fluid.CoolingTowerRuntimeTest` (5), which is not in this tree. It is not reviewed here.
