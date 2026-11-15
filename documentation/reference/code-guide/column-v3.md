# V3 column calculator: input, route, seeds, audit and publication

Up: [code guide](README.md) · the equations, Newton, linear algebra, thermo and the Rust library:
[column-v3-solver.md](column-v3-solver.md)

## Read this when

- A column result is wrong, missing, slow or rejected, and you need to know which stage (admission, seed, continuation,
  attempt, audit, hydraulics, publication) decided it.
- You add or change a column feature: a specification, side draws, steam, pumparound heat, tray pressure drop, a
  preset or a template.
- You touch the worker service that runs a running column's solve, or the column's wire form (`ColumnWire`).
- You need to know what a published label, digest or audit check means before trusting a number in the GUI.

The column has two callers: the process simulator's column unit, on the client's run thread
([process-simulator.md](process-simulator.md)), and a running column, on the server's bounded worker
([column-operation.md](column-operation.md)). Save design and Import: [process-simulator.md](process-simulator.md),
[equipment.md](equipment.md).

## What it does

The calculator simulates a crude-oil atmospheric or vacuum distillation column at steady state: a hot feed enters a
stack of equilibrium trays; vapour rises, liquid falls; a partial or total condenser at the top (node 0) runs at a
specified temperature and reflux ratio, and a reboiler or sump at the bottom (node N+1) takes a specified duty. Liquid
products can be drawn from trays, stripping steam injected low, pumparound coolers remove heat from tray zones, and water
that condenses on cold upper trays forms an immiscible free-water phase. The steady state closes every tray's component
balances, vapour-liquid equilibrium (PR78) and energy balance at once (MESH). A caller builds an immutable SI
`V3ColumnInput` and runs `V3ColumnCalculator.calculate` or `calculateWithStrategy`. The calculator screens the request,
builds a seed (the bundled learned Transformer by default, a classical sequential initializer as backup), walks
continuations from an easy surrogate to the authored request, corrects each rung with a damped banded Newton solver,
re-audits the candidate from scratch, re-solves once on a flow-dependent tray pressure profile, and returns an accepted
result or a typed failure. It never sees a Minecraft object.

## Class map

Paths under `src/main/java/com/wormzjl/icheme/`; `science/column/v3/` unless named. "pp" = package-private. Lines at the
snapshot. The solver core's classes are mapped in [column-v3-solver.md](column-v3-solver.md).

| Class | Role | Lines |
|---|---|---|
| **Game glue** | | |
| [`network/ColumnWire`](../../../src/main/java/com/wormzjl/icheme/network/ColumnWire.java) | A column's input, component choices, saved design and display result on the wire, for Save design, a constructor's template and a run's stored results; every decoder bounds what it reads (`readInput`, `readChoices`) | 377 |
| [`network/V3InspectionCodec`](../../../src/main/java/com/wormzjl/icheme/network/V3InspectionCodec.java) | Bounded byte form of the per-node inspection and audit (`encode`, `decode`) | 88 |
| [`runtime/ProcessSolveCoordinator`](../../../src/main/java/com/wormzjl/icheme/runtime/ProcessSolveCoordinator.java) | The one server-thread router of worker completions (`drainCompletedCalculations`, `stopCalculations`) | 83 |
| [`runtime/ProcessSolveServices`](../../../src/main/java/com/wormzjl/icheme/runtime/ProcessSolveServices.java) | Lifecycle adapter of the bounded service; three job families: a fluid island's slice, a running column unit's solve, a still's column solve (`submitFluidIsland`, `submitColumnUnit`, `submitStill`) | 545 |
| [`runtime/ProcessSolveJobs`](../../../src/main/java/com/wormzjl/icheme/runtime/ProcessSolveJobs.java) | The worker side: the command each family runs and its result (`ColumnUnitCommand` among them); no Minecraft type | 133 |
| [`runtime/BoundedCpuSolveService`](../../../src/main/java/com/wormzjl/icheme/runtime/BoundedCpuSolveService.java) | Generic bounded worker pool: one job per owner, a ready queue, deadlines, cooperative cancellation (`trySubmit`, `Admission`, `TerminalStatus`, `CancellationToken`) | 870 |
| **Contract** | | |
| [`V3ColumnInput`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnInput.java) | Immutable SI request (`SCHEMA_VERSION`); limits `MIN_STAGE_COUNT`, `MAX_STAGE_COUNT`, `MAX_SIDE_DRAWS`; `usesTrayHydraulics` | 216 |
| [`V3ColumnSpecification`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnSpecification.java) | The three controls (nested `ControlledQuantity`): condenser outlet T, organic reflux ratio, reboiler duty | 62 |
| [`V3SideDrawSpec`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3SideDrawSpec.java), [`V3SteamFeedSpec`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3SteamFeedSpec.java), [`V3PumparoundSpec`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3PumparoundSpec.java) | Feature rows and their pp arithmetic (draw withdrawal, steam node feeds and `upwardVaporProfile`, pumparound stage heat, `Split`) read by the residual, the audit and the streams | 37, 36, 97 |
| [`V3ColumnPresets`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnPresets.java) | The column's reading of a server column preset (`MaterialPresets.Column`): `input(preset, catalog)`, `validate`, `matches` | 73 |
| [`V3ColumnTemplate`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnTemplate.java) | A column with no feed and no package: trays, set points and connections as a `columnpreset` file holds them; `designFeed` (a template scales to the current feed) | 123 |
| [`V3Admission`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3Admission.java) (pp) | The request-only gates (`requestOnlyAdmission`: total draw below feed, `liquidSupplyScreen`, `staticCoolingAdmission`) and their verdict `RequestAdmission` | 131 |
| `V3LiquidSupplyScreen`, `V3HeatFeasibility` (pp) | The request-only liquid-supply check of each draw; necessary bounds on authored cooling | 176, 143 |
| **Route** | | |
| [`V3ColumnCalculator`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnCalculator.java) | Stateless façade: the entry points (`calculate`, `calculateWithStrategy`, `calculateWithAcceptedProfile`) and the catalog pin, the branch order (`calculateInCatalog`, `preferredCondenserBranch`), `calculateBranch` (the direct and base solves of a dry-top column or a pot, the continuation, the material-closed fallback, the coarse finite-difference recovery, the published failure), the warm start (`warmStartPass`, `warmCompatible`, `retargetWarmSeed`), the gates (`publishesSuccess`, `publishesRequestedGeometry`), `withPriorSupportNotes`, `diagnostics`, `closureTolerance`, and aliases the tests and tools read | 626 |
| `V3LearnedRoute` (pp) | The learned route: `calculateInCatalog` (validation, the screens, the candidates under `budgetPolls`, the classical backup), `correctNeuralSeed`, `neuralRungBudget`, `neuralExtension`, `initializationEvent`, `neuralFailure` | 230 |
| `V3ContinuationLadder` (pp) | The stage grid (`solveStageContinuation`, `continuationStageCounts`, `recoverContinuationStage`), the pressure legs (`requiresPressureContinuation`, `solvePressureContinuation`), the seed hand-off (`continuationSeed`, `projectedSeedOrPrevious`), `recoverWithBubblePointProjection`, `hasCondenserPhaseMismatch`, `initializeForSolve`, the auxiliary inputs (`withStageGeometry`, `withoutSideDraws`, `withoutPumparounds`, `withoutSteamWithSurrogateDuty`, `withTopPressure`) | 555 |
| `V3FeatureRamp` (pp) | `recoverWithDrawRamp` (steam, then stage heat, then draws, or one coupled homotopy): `rampSteps`, `steamRampFractions`, `fastRampSteps`, `rampRungBudget`, `INTERMEDIATE_RUNG_BUDGET`, the heat bounds (`requireCoolingBelowBaseCondenserDuty`, `condensationCappedTray`); nested `RampStep`, `EnergyShiftLog`, `HeatSubdivisions` | 711 |
| `V3AttemptLoop` (pp) | One rung's solve: `solveSingleProblem` (feed flash, `prepareAttempt` with the floor lift, the Newton attempt on the rung's Jacobian route, `audit`, the floor-support and wet-set refreshes with the free-water continuation), `feedFlash`, `correctCondenserPhase`, `runRung` | 485 |
| `V3HydraulicPublish` (pp) | The publication seam `publishAccepted` (digest, duty ledger, profile) and the tray pressure profile: `trayHydraulics`, `hydraulicCorrection` (`HYDRAULIC_CORRECTION_BUDGET_POLLS`), `summarised`, `advisory` | 246 |
| [`V3RampKind`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3RampKind.java), [`V3AttemptCode`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3AttemptCode.java), [`V3AuditFamily`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3AuditFamily.java) | Enums that carry their texts: a ramp rung's kind (`steam-ramp`, `wet-ramp`, `heat-ramp`, `draw-ramp`, `coupled-ramp`); why a Newton attempt stopped; an acceptance check's family | 31, 47, 44 |
| `V3SolvePass`, `V3SolvePolicy`, `RungOptions`, `ContinuationJacobianPolicy`, `PreparedAttempt`, `PhaseCorrection`, `CondenserAttempts`, `HydraulicPublication`, `NeuralProgress` (pp) | Shared types: one solve's pass and evidence; the chain's cutoff and closure (`OFF`, `OFF_FAST`); how a rung's Newton solve runs (`RungOptions.of`); the Jacobian route (`NONE`, `STAGE_LOCAL_BLOCKS`, `PRESSURE_LOCAL_PREDICTOR`); an attempt's frozen problem, support, wet set and seed; a rung's pass after its condenser correction; the request's attempt history; what the hydraulic step publishes; the learned pass's in-flight evidence | 38, 60, 20, 16, 19, 12, 34, 11, 95 |
| `HydraulicBudgetExceeded`, `NeuralBudgetExceeded`, `InitializationFailure`, `PathDependentHeatBound` (pp) | The two poll budgets' exceptions; a classical seed that failed (`INITIALIZATION_FAILURE`); a heat bound read off the continuation state (`NONCONVERGENCE`, `PATH_DEPENDENT_BOUND_SUFFIX`) | 9, 10, 14, 41 |
| `V3TruncationFallback` (pp) | Retries a failed chain with the optional cutoff off | 55 |
| [`V3SolveStrategy`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3SolveStrategy.java), [`V3SolveControl`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3SolveControl.java) | Warm-start policies (`AGGRESSIVE`, `DRIFT_SPEC`); the cooperative checkpoint (cancellation, deadline) | 14, 15 |
| [`V3InitializationOptions`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3InitializationOptions.java) | Learned or classical `Mode` (`LNN_FIRST`, `LNN_ONLY`, `CURRENT_ONLY`), `WetStart`, the iteration cap, `budgetPolls` (`DEFAULT_BUDGET_POLLS`), `Correction.PROGRESS` | 93 |
| **Seeds** | | |
| `V3ColumnInitializer` (pp) | Classical seed: `Mode` `SEQUENTIAL_MATERIAL_VLE` or `MATERIAL_CLOSED`; `projectMaterialBalancesAtFixedTemperature` | 1108 |
| `V3EnergyShiftPredictor` (pp) | Temperature-only energy correction at fixed flows for a rung whose stage heat or steam changed | 330 |
| [`V3NeuralInitializer`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3NeuralInitializer.java), [`V3NeuralRegistry`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3NeuralRegistry.java) | The learned-seed interface; the bundled registry (`bundled()`, `registry.json`), the SHA-pinned payload reader (`TransformerArtifact`, sidecar schema 5), `bind`, `Lumping` (`of`, `delump`) | 40, 249 |
| `V3AnchorTransformerInitializer` (pp, nested `NativeAnchor`) | The shipped Transformer and its material-closed input anchor; `supported` (formulation revision, topology, pressure window, global bounds, `Family.holds`); `propertyRevision` | 416 |
| [`V3NeuralSeed`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3NeuralSeed.java), [`V3GeneralNeuralFeatures`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3GeneralNeuralFeatures.java), [`V3FactorizedNeuralFeatures`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3FactorizedNeuralFeatures.java) | The seed on the public axis (`stateFor`); the input encoding (`global`, `nodes`); the output side (`decode`, `targets`) | 109, 116, 156 |
| **Acceptance and output** | | |
| `V3AcceptanceAuditor` (pp), [`V3AcceptanceAudit`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3AcceptanceAudit.java) | A fresh physical audit of a candidate from its state, never a cached residual; checks and advisories (`Check`, `hasFamily`) | 1083, 69 |
| [`V3ConvergenceEvidence`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ConvergenceEvidence.java) | The final Newton step's certificate | 95 |
| [`V3TrayHydraulics`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3TrayHydraulics.java), [`V3TrayHydraulicsSummary`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3TrayHydraulicsSummary.java) | Sieve-tray pressure drop, flooding and the entrainment limit on the accepted state; its bounded summary | 414, 70 |
| [`V3ColumnResult`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnResult.java), [`V3ColumnOutcome`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnOutcome.java), [`V3SolverDiagnostics`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3SolverDiagnostics.java), [`V3SolverFailureCode`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3SolverFailureCode.java) | Accepted result (`accepted`); success or typed failure; bounded evidence; the eight failure codes | 136, 38, 57, 13 |
| [`V3ColumnStreamProperties`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnStreamProperties.java), [`V3ColumnDutyLedger`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnDutyLedger.java), [`V3ColumnInspection`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnInspection.java) | Products (at most `MAX_STREAMS`, 4 plus one per side draw: overhead vapour or water vapour, liquid distillate, bottoms, free water), the energy summary (`fromAccepted`), the per-node profile | 236, 117, 83 |
| [`V3ColumnDisplayResult`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnDisplayResult.java), [`V3InputDigest`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3InputDigest.java) | The persistable, sendable presentation certificate; SHA-256 of the resolved input and revisions | 79, 158 |
| [`V3ColumnLabels`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnLabels.java) | `FORMULATION_REVISION`, `ASSUMPTIONS_REVISION`, `WET_ASSUMPTIONS_REVISION`, `HEAT_ASSUMPTIONS_REVISION`, `DERIVED_PAIRS_SUFFIX`; `formulationRevision`, `assumptionsRevision`, `closureSuffix`, `seedFormulationRevision`, `initializationFormulationRevision` | 127 |
| `V3SolveReport`, `SolveTelemetry` (pp) | What a solve publishes about itself: bounded events (`boundedEvent`, `mergedEvents`, `stageTraceEvent`, `flashTraceEvent`), failure details (`sideDrawDiagnostic`, `waterDiagnostic`), `boundedSolvePath`, `terminalFailure`, `failedAudit`, `failureCode`; one attempt's direction counters | 175, 98 |

Test tree: `V3HollandExample32` with `IndependentHollandMeshOracle`, `HollandB12Thermo`, `HollandExample32Data` (Holland
1981 Example 3-2, a benchmark only, package `icheme:holland_b12`); `V3TestInputs`, `V3JacobianOracle`,
`V3ManufacturedFlash`, `V3WaterPhaseQualification`.

## Main flow

1. **Caller.** The simulator: `ColumnUnit.solve` inside `MaterialRuntime.with` on the client's run thread, from the
   seed its column ended on in the steady run it starts from (`Report.Warm`) through `calculateWithStrategy`
   (`AGGRESSIVE`), else `calculate` with the bundled model (`V3NeuralRegistry.bundled().bind`) on `ColumnUnit.START`
   (`LNN_FIRST`, `AUTO`, 16 iterations, `DEFAULT_BUDGET_POLLS`). A Stop is the checkpoint's cancellation and drops the
   seeds (`FlowsheetRun.cold`). A running column: `ProcessSolveServices.submitColumnUnit` admits a `ColumnUnitCommand`
   under the solve deadline; `ColumnSolve.run` calls `calculateWithStrategy` from the table's seed. (Both callers are
   being changed by H-C5, one column solve for the sheet and the world: [column-operation.md](column-operation.md).)
2. **Warm start** (`calculateWithStrategy`): `warmCompatible` judges the seed (same package, basis, stage count, feed
   tray, draw, steam and pumparound trays; top pressure, feed temperature, condenser temperature, each feed flow and the
   reboiler duty within a relative drift of 0.50 under `AGGRESSIVE`); `warmStartPass` corrects it; else the cold route.
3. **Learned route** (`V3LearnedRoute.calculateInCatalog`): `validateInput`, the liquid-supply screen and static
   cooling; the model's candidates, each corrected by `correctNeuralSeed`, under one budget of control polls
   (`budgetPolls`; the event records `neuralPolls`). A candidate whose audit carries a water-dew-point advisory is kept
   while the loop looks for a clean one. With nothing accepted, `LNN_ONLY` returns the failure and `LNN_FIRST` makes one
   classical restart from the original input.
4. **Classical route** (`calculateInCatalog`): `V3Admission.requestOnlyAdmission`, `preferredCondenserBranch` (a seed
   flash of a 4-tray surrogate), `calculateBranch` → `V3ContinuationLadder.solveStageContinuation` or
   `solvePressureContinuation` → `V3FeatureRamp.recoverWithDrawRamp` → `V3AttemptLoop.solveSingleProblem` per rung.
   A rung that only solves and corrects its condenser phase goes through `runRung`. Fallbacks: a material-closed cold
   seed, `recoverWithBubblePointProjection`, a coarse-FD recovery; a failed preferred branch tries the alternate once.
5. **A rung** (`solveSingleProblem`, its `RungOptions`): feed flash, `prepareAttempt` (lifts the seed with
   `V3FloorLift.liftFloorSupport`, derives support and wet set), `V3SimultaneousColumnSolver.solve`, the audit, at most 3
   floor-support and 3 wet-set refreshes ([column-v3-solver.md](column-v3-solver.md)).
6. **Publication** (`V3HydraulicPublish.publishAccepted`, both routes): `trayHydraulics` when the diameter is above 0
   and the one bounded correction, then `V3ColumnResult.accepted` with `V3InputDigest`, `V3ColumnDutyLedger.fromAccepted`,
   streams and inspection.
7. **Completion.** A running column's: `IChemE.onServerTickPost` → `ProcessSolveCoordinator.drainCompletedCalculations`
   (at most 64 a tick) to its request's handler. The simulator takes its outcome on its own thread.

## Contracts

- **Units and indexing.** Feeds are component molar flows (mol/s) on the package's public axis; K and Pa absolute; W,
  positive into the column (pumparound coolers negative); reflux L/D, organic reflux over external organic liquid
  distillate. Nodes: 0 condenser (temperature specified, no T unknown, no energy row), 1..N trays, N+1 sump. The GUI
  shows N+2 total trays and feed f+1.
- **Limits** (`V3ColumnInput`, `V3ColumnProblemResolver.validateInput`): `stageCount` 0..`MAX_STAGE_COUNT` (0 is a pot:
  condenser and sump only); feed tray 1..N+1; side draws at most `MAX_SIDE_DRAWS` on trays 1..N, total below the feed;
  steam at most 2, up to N+1, at least 5 K superheat at injection pressure, total at most the feed, zero reboiler duty
  needs sump steam; pumparounds at most `MAX_PUMPAROUNDS`, 1 ≤ return ≤ draw ≤ N, finite nonzero duty, `RETURN_TRAY` or
  `UNIFORM` split; diameter (0, 15] m with sieve-tray hydraulics, 0 a prescribed uniform drop for tests only (the server
  refuses it: `REJECTED_INPUT: Set the column diameter`).
- **Water on the axis.** A basis may name `Water` (`V3ColumnProblemResolver.WATER`): one more compound of the cubic, its
  enthalpy the cubic's alone. A steam row is refused where the basis names Water with a feed above 0 (`waterOnAxis`,
  `STEAM_WITH_WATER_ON_AXIS`). Off the axis, water's vapour is the authored upward steam profile plus free water from
  the tray above, and free water forms on wet trays ([column-v3-solver.md](column-v3-solver.md)).
- **Pumparounds** model the heat effect only: no circulating stream, no return temperature.
- **What publishes.** A seed, a continuation rung, a parametric water state and a stored display result are never
  results. Success needs the requested problem reached, `Attempt.Converged`, `audit.accepted()` and the closure gates
  (`publishesSuccess`); only the authored request at requested geometry with a solved wet set publishes
  (`publishesRequestedGeometry`).
- **Convergence gates** (`V3ConvergenceEvidence`): scaled residual and final log-flow change at most the closure
  (default 1e-8, at most 1e-3); linear backward error at most 1e-12; temperatures per coordinate against
  1e-6 K + 1e-9 T.
- **Audit families** (`V3AuditFamily`): `FINITE_TOPOLOGY`, `LOCAL_COMPONENT_BALANCE`, `EQUILIBRIUM`, `ENERGY_BALANCE`,
  `GLOBAL_ENERGY_BALANCE`, `CONDENSER_PHASE`, `CONDENSER_ENERGY_BALANCE`, `SIDE_DRAW_SPLIT`, `TRUNCATION_MASS_DEFECT`,
  `PHASE_TRUNCATION_DEFECT`, `WATER_PROFILE`, `WATER_BALANCE`, `WATER_DEW_POINT`, `FREE_WATER_SPLIT`,
  `INTERIOR_PHASE_IDENTITY`, `CARRY_OVER`; a failure audit uses `UNAVAILABLE`. The three residual families and the
  condenser split are bounded by `max(EQUILIBRIUM_LIMIT, closure)` (`closureLimit`). A dry tray below its water dew
  point passes with a warning; an invalid modelled wet tray fails. `INTERIOR_PHASE_IDENTITY` checks positive
  liquid/vapour pairs with almost identical compositions, roots and enthalpies; a combined-flow TP flash must support two
  phases. `CARRY_OVER` always passes: under a drum that decants free water, tray 1's free water leaves with the vapour
  and decants in the drum (tray 1 books its latent heat, the condenser duty falls by the same); above the entrainment
  limit (`V3TrayHydraulics.entrainmentLimitMolPerSecond`) the column publishes with a warning naming the tray; a
  carry-over seed is not warm-startable.
- **Failures.** `INFEASIBLE_SPECIFICATION` comes only from request-only gates; a state-dependent heat bound
  (`PathDependentHeatBound`) publishes `NONCONVERGENCE`. The eight codes: `INVALID_INPUT`, `INFEASIBLE_SPECIFICATION`,
  `PROPERTY_OUT_OF_RANGE`, `INITIALIZATION_FAILURE`, `LINEAR_SOLVE_FAILURE`, `NONCONVERGENCE`,
  `ACCEPTANCE_AUDIT_FAILURE`, `INTERNAL_ERROR`. Hydraulics cannot fail a request: a failure there publishes the first
  accepted state with a warning.
- **Packages.** The column is vapour-liquid without crystal equations: it refuses a package with crystals or a reference
  spine, typed (`CrystalPhasesUnavailable`, `IdealGasFitUnavailable`, `REJECTED_INPUT`), even when the network runs that
  package; it runs on views without them.
- **Identity.** `datasetRevision()` is `V3PengRobinsonThermo.columnIdentity(catalog, packageId)`: the package's
  `scientificRevision`, then `;` and the identity of the pairs the column evaluates (`DerivedPairs.Result.identity`), at
  most `MaterialCatalog.MAX_IDENTITY` characters; a lens keeps the bare scientific revision. Seeds, digests, the
  published result and the simulator's warm seed key on it, so a pair-record edit retires them; a record's revision bump
  makes earlier results `STALE`.
- **Labels** (`V3ColumnLabels.formulationRevision(input, cutoff, closure)`): dry `v3-dry-mesh-r23` (`r24` with a
  cutoff), draws `r25-side-draws`, draws and heat `r26-side-draws-stage-heat`, heat only `r27-stage-heat`, steam
  `v3-wet-mesh-r34-steam`, steam and heat `r35`; each family adds `-phase-r1-derived-pairs-r1` before the optional
  closure suffix. The learned start's check reads `seedFormulationRevision` (without `DERIVED_PAIRS_SUFFIX`).
  Assumptions `v3-dry-assumptions-r4`, `v3-wet-assumptions-r3`, with `+v3-heat-assumptions-r1` for heat. The digest adds
  the water-data revision, the pumparound split rule, and the diameter with `v3-sieve-tray-bennett-r1`.
- **The learned seed** (`V3NeuralRegistry.bind`): `UNAVAILABLE` for a package with crystals, for a request whose active
  compounds' values are not the model's trained physics (`TrainedPhysics`), and for off-axis compounds above the
  model's share or without a lump. A bound request is read on the model's axis: each off-axis compound joins its lump,
  and the seed is split back by feed share. `supported` takes at most 3 draws, 4 pumparounds, 2 steam feeds.
- **Threads.** Workers never see Minecraft objects; the configuration and the catalog are captured at admission, so a
  reload does not affect an in-flight solve.

## Laws and constants

- **Continuations** (`V3ContinuationLadder`): stage grid 4, 8, 15, then 30 doubling, then the request
  (`continuationStageCounts`; 40 trays: 4-8-15-30-40). A request with features first solves a surrogate without draws
  and stage heat, steam replaced by surrogate reboiler duty. Every grid rung takes stage-local block steps from its seed
  (`STAGE_LOCAL_BLOCKS`). Pressure: a top pressure at most 100 kPa starts from a 150 kPa anchor, 10 kPa steps above
  110 kPa, 5 kPa below (feature cases 5 kPa throughout).
- **Feature ramp** (`V3FeatureRamp`): steam, then heat, then draws; 4 heat increments (subdivided at most 4 times, 2
  per rung), 8 draw increments when heat is authored. Intermediate steam rungs use `INTERMEDIATE_RUNG_BUDGET` (stop if
  the residual has not halved over 12 iterations, floor 1e-3).
- **Zero reflux and a dry top.** At exact zero organic reflux the resolver sets the first possible liquid stage to the
  feed or an earlier negative-duty pumparound return; `V3ColumnTopology.dryTopTrayCount` is the vapour-only prefix, whose
  stages keep component and energy balances and vapour flow with zero liquid and no VLE rows. The calculator tries an
  authored dry-prefix problem before auxiliary anchors; a pot tries its authored topology. At zero reflux the empirical
  wet-start supply threshold is bypassed, the exact necessary supply bound kept.
- **Tray hydraulics** (`V3TrayHydraulics`, revision `v3-sieve-tray-bennett-r1`): orifice dry drop, Bennett-Agrawal-Cook
  clear liquid, Fair flooding (Lygeros-Magoulas fit), a fixed tray (0.6 m spacing, 10 % downcomer, 10 % holes, 50 mm
  weir, 12.7 mm holes). A dry tray has the gas-orifice drop only. The profile is marched from the accepted traffic; if
  the total drop differs from the nominal by more than 10 %, the request is re-solved once warm (half step, then full),
  within 64 iterations and `HYDRAULIC_CORRECTION_BUDGET_POLLS`, so the profile does not depend on the machine; the
  summary event lists each correction's polls.
- **Fixed in code**: `MAXIMUM_NEWTON_ITERATIONS` 128; `Correction.PROGRESS` (extension blocks of 8 to 48, stop when the
  residual has not fallen below 0.9× over 8 iterations above 1e-6); 64 completions drained per tick.
- **Configuration** (`icheme-common.toml`, `IChemE`): `solver.workers` (0 automatic, limit `automaticWorkerLimit`),
  `solver.readyQueueCapacity`, `solver.deadlineMilliseconds` (from admission, queue time included, polled
  cooperatively), `solver.gracefulShutdownMilliseconds`, `forcedShutdownMilliseconds`, and
  `columnV3.columnV3LiquidSupplyScreenRatio` (the only column key a running column reads; 0 leaves only the ρ ≥ 1
  refusal). Every solve runs at cutoff 0 and the exact closure.
- **Data.** The default input is the network record's `default_column_preset`, `medium_crude` (package
  `icheme:tjl20_methane`, 40 trays, feed tray 37 at 638.15 K). Tia Juana is a test overlay (`TiaJuanaTestCatalog`; the
  literature case `literature_tia_juana` on `tjl19_dwsim`, `V3TestInputs.literatureCduInput()`). The learned model is
  `icheme:v3-column-open-r34-u1-v2` (`registry.json`).

## Formats

`V3ColumnInput.SCHEMA_VERSION`, the inspection blob (bounded, length-checked), the saved design (`ColumnDesignTags`) and
the protocol the column's packets ride on (`NetworkProtocol.VERSION`): [FORMATS.md](../FORMATS.md). `ColumnWire`
carries a `V3ColumnInput`, the `ColumnComponents.Choices` (with each exchanger's type, checked on decode), a saved design
and a `V3ColumnDisplayResult`; decoders bound every list and refuse non-finite doubles with `DecoderException`. Wire
formats get no compatibility work (AGENTS.md).

## Tests and gates

All under `src/test/java/com/wormzjl/icheme/`, `science/column/v3/` unless named. The column's tests run in
`columnGates` ([verification.md](verification.md)).

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| Request refused before any solve | `V3Admission`, `V3LiquidSupplyScreen`, `V3HeatFeasibility` | `V3LiquidSupplyScreenTest`, `V3PathDependentInfeasibilityTest`, `V3PumparoundContractTest` |
| Input limits | `V3ColumnInput`, `V3ColumnProblemResolver` | `V3ColumnInputTest`, `V3SideDrawContractTest`, `V3SteamFeedContractTest`, `V3PotColumnTest`, `V3ColumnFeedAndDrawLimitsTest`, `ColumnWireTest` |
| Continuation, ramp, recovery | `V3ContinuationLadder`, `V3FeatureRamp`, `calculateBranch` | `V3StageContinuationTest`, `V3ContinuationSeedTest`, `V3BoundedSolvePathTest`, `V3LearnedRecoveryTest`, `V3PumparoundSteamCalculatorTest` |
| Condenser phase | `V3AttemptLoop.correctCondenserPhase`, `preferredCondenserBranch` | `V3CondenserPhaseAuditTest`, `V3CondenserPhaseTransitionTest` |
| Zero reflux, dry top, false interior phase | resolver, initializer, calculator, auditor | `V3VacuumStartupTest`, `V3ExactWarmStartSweepTest` |
| Learned seed | `V3NeuralRegistry`, `V3AnchorTransformerInitializer`, `V3LearnedRoute` | `V3NeuralInitializationTest`, `V3NeuralRegistryTest`, `V3BundledModelTest` (`aFourthSideDrawIsAValidInputTheBundledModelDeclines`), `CrystalPackageConsumersTest` |
| Tray hydraulics | `V3TrayHydraulics`, `V3HydraulicPublish` | `V3TrayHydraulicsTest`, `V3TrayHydraulicsColumnTest` |
| Closure, labels, display | `V3ConvergenceEvidence`, `V3ColumnLabels`, `V3ColumnDisplayResult` | `V3ConvergenceClosureTest`, `V3ColumnDisplayResultTest`, `V3IdentityTest` |
| Wire | `ColumnWire`, `V3InspectionCodec` | `ColumnWireTest`, `V3InspectionCodecTest` |
| Templates and presets | `V3ColumnTemplate`, `V3ColumnPresets`, `V3ClientPresets` | `V3ColumnTemplateTest`, `V3ClientPresetsTest` (corpus `compat/presets/`), `V3LiteraturePresetTest`, `V3MethaneColumnTest`, `MaterialPresetsTest` |
| Water in the column | `V3CatalogPropertyPackage`, the resolver | `V3AqueousColumnTest` |
| Worker admission, deadline, shutdown | `ProcessSolveServices`, `BoundedCpuSolveService` | `BoundedCpuSolveServiceTest`, `ProcessSolveServicesRequestTest` |

End to end: `V3ColumnCalculatorTest`, `V3SideDrawCalculatorTest`, `V3PumparoundCalculatorTest`. Independent
references: `IndependentIdealMeshOracleTest`, `V3StructuralMatchingOracleTest`, `HollandExample32BenchmarkTest` (also
the task `v3HollandBenchmark`, which writes `build/reports/benchmarks/v3-holland-example-3-2.json`),
`V3Sotelo2019SideDrawCaseTest`. Slow sweeps: `V3ColdStartTest`, `V3ExactWarmStartSweepTest`.

## Traps

- "Dry" in class names (`V3DryMeshState`, `V3DryMeshCoordinateMap`, "dry-V3 façade") names the hydrocarbon basis:
  these carry free water too.
- `Dwsim` in method names is a numerical approach's name; no external simulator runs. The `tjl19_dwsim` package was
  reconstructed with DWSIM offline.
- There is one bubble-point projection, `V3ContinuationLadder.projectedSeedOrPrevious`; no preconditioner framework.
- `V3InitializationOptions.Mode` (the route) and `V3ColumnInitializer.Mode` (the classical seed's kind) are different
  enums; the calculator picks the classical mode per call site.
- `V3ColumnInput` keeps one shorthand constructor (side draws only; `V3HollandExample32.input` uses it); tests build
  inputs with `V3TestInputs.dry(...)`.
- The selected attempt's Newton count in the diagnostics is not the total work over all rungs and refreshes.
- `MAX_SIDE_DRAWS` is above what the bundled model was trained on (3 slots): a fourth draw always takes the classical
  route.
- Save design checks structure only (`ColumnDesign.of`); it never runs the solver. Import reads the saved design, not
  the input.
- The Holland facade is a benchmark only: no preset, template or simulator door builds its input.

**Snapshot:** `d6f721b94`, 2026-11-15.
