# D-S6 The step solver's equations in files, the last slice of S-01: report

Batch D-S6 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-a323029f15e7d2038`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `743512ec1`. One code commit, `92de0d22c` (step 1, `StepEquations`). Then this report with the code guide. **Step 2 was not done**: the three parts do not separate (the counts are below). **Nothing was compiled or run** (the brief: no Gradle, no JVM). Structure only: the same floating-point operations and model calls in the same order, so chain-100, the junction lines, the printed counters and the poll counts must not move.

**Lines.** `PassiveStepSolver` goes from 3,526 to 1,968 lines (−1,558). `StepEquations` is 1,599 lines:
- 1,558 moved: the class and its doc line, de-indented by four spaces;
- 32 header lines: the package line, 29 imports and 2 blank lines;
- 6 lines of class document, the 2-line holder field, and 1 new constructor line.

## Step 1, `StepEquations` (`92de0d22c`)

**Form.** `Equations` was a non-static inner class, built once per pass. It is now a top-level package-private final class.
- It has two private final fields, `solver` and `model`. `model` is the solver's model, the same object, so its 48 reads keep their text.
- The pass loop builds it as `new StepEquations(this,model,graph,...)` (`PassiveStepSolver.java`:542). A pass still builds one object, holding an explicit reference in place of the implicit outer one, plus `model`. Nothing else is allocated.
- The nested record `Transport` moves with it (`StepEquations.Transport`).

**Qualifiers.** 43 reads go through `solver`:
- 41 have `solver.` inserted: `rateOnly` ×10, `viscosities` ×7, `molarMass` ×3, `emptyings` ×3, `preparedBuilt`, `preparedPass`, `carrierViscosity`, `sideCurve`, `inResidual` and `suctions` ×2 each, and once each `blanketInflows`, `boilUp`, `seeding`, `viscosity`, `curveKept`, `lawResidual`;
- `PassiveStepSolver.this.moverPower` becomes `solver.moverPower`;
- `constants` is read in the constructor line (below).

The solver's statics come in by 22 single static imports, so their text is unchanged. `PassStart` comes in by a type import. `PassiveStepSolver.saturates(...)` keeps its qualified text.

**The one edit that is not a qualifier.** `final double[] fixedOpenings=constants.heldOpenings.clone();` is now a blank final. The constructor's first line assigns it: `this.solver=solver;this.model=model;fixedOpenings=solver.constants.heldOpenings.clone();` (`StepEquations.java`:250).
- Why: a top-level class has no outer reference while its field initializers run.
- The same clone happens before the original constructor body.
- Only its order against the two other field initializers changes, `junctionWeight=1` and `missingEdge=-1`. These are constant stores into the new object, so the order cannot be seen. There is no initializer block.

**Users that follow.**
- The solver names `StepEquations` in `LastSolve`, `preparedPass`, `polishRefusedPoint`, `reactivate` and `acceptedEquations`.
- `StepObservations`, `MoverSuction`, `DeviceModes` and `PhaseActiveSet` drop the import of `PassiveStepSolver.Equations` and name `StepEquations` (parameter types and comments).
- Doc links: `{@link Equations#…}` in the solver, and `{@link PassiveStepSolver.Equations#…}` in `PortStreams`, `StartSeeds` and `OneWayClosures`, now read `StepEquations#`. Nine `{@link #…}` links in the moved text name solver members, so they now read `PassiveStepSolver#`.
- No test, GameTest, `mcpCompat` or `benchmarks` file names the type in code. `BlockJacobianEquivalenceTest`:160 uses `var`.
- No harness under `tools/unified-thermo-port-forms/` or `tools/phase-ports-probes/` names it in code: the only hits are comments in solver copies.

**Members widened** (in `PassiveStepSolver`, private to package-private; nothing became public):
- the fields `preparedPass` (now typed `StepEquations`), `preparedBuilt`, `inResidual`, `emptyings`, `blanketInflows` and `viscosities`;
- the methods `sideCurve`, `curveKept` and `viscosity(State,Prepared)`. The one-argument `viscosity` stays private;
- the statics `SOLID_CLAMP_FRACTION`, `TRACE_CLAMP_FRACTION` with `TRACE_RETENTION` (one declaration), `FILTER_CAPACITY_MARGIN`, `closedEdge`, `pockets` and `saturates`.

`inResidual` shares its declaration line with the still-private `heatCurveSamples` and `heatCurvesInResidual`. Not widened: `model`, `ownership`, `transport`, `sideCurves`.

## Step 2: not done. Cross reads between the three parts

The parts follow the review's 2.2 division:
- `EquationLayout`: the fields, the constructor, `probe`, `adoptPrepared`, `remoteSuction` and `buildSparsity`;
- `PortDraws`: `startSegments` to `transportAt`;
- `Rows`: `size` to `carries`.

The counts are simple-name reads in one part of a member declared in another (`tmp/ds6/crossreads.mjs`):

| From → to | Reads | Members | Most read |
|---|---|---|---|
| rows → layout | 356 | 61 | `graph` 59, `layout` 44, `solver` 23, `dt` 21, `offsets` 19, `model` 17, `edgeOffset` 15 |
| draws → layout | 183 | 25 | `graph` 49, `model` 23, `solver` 13, `segments` 12, `dt` 11, `releases` 10 |
| layout → rows | 27 | 12 | `nodeSize` 11, `checkpoint` 3, `edgeFlow` 3, `buildInitial`, `heldCurves`, `rowWeights`, `transport` |
| rows → draws | 13 | 7 | `drawn` 5, `flow` 3, `cap`, `baseCap`, `drawRates` |
| draws → rows | 6 | 5 | `checkpoint` 2, `decode`, `transport`, `filterCapFlow` |
| layout → draws | 5 | 5 | `startSegments`, `capacities`, `overflowCapacities`, `releases`, `kept` |

That is 590 cross reads in all. The parts also fail both conditions of the brief:
- **A cycle.** The draws call the rows' decode, and the rows call the draws' `drawn`, `flow` and `cap`.
- **A split method.** The constructor assigns the fields of all three parts and calls into the other two while it builds. Separate part objects would split the constructor.

So `StepEquations` stays whole.

## The solver as it stands (1,968 lines, in file order)

1. The step's constants.
2. The slice inputs: inlet refusals, `keepOpen`, blanket inflows.
3. The side-curve cache and its counters, and the workspaces.
4. The records `CarriedPoint`, `PassStart`, `Seeded` and `SolveConstants`.
5. `replayStart` and `sameLines`.
6. The eight holder fields and the constructors.
7. `Result`, the solve entries, `portRate`, and the pass loop `solve`.
8. `polishRefusedPoint`, `liquidPH` and the keys `WorkspaceKey`, `PassKey`, `Proposal`.
9. The supports and reactivation, and the identity helpers.
10. The shared laws: law flows, static drive, movers' laws, `kappa`, the velocity cap, viscosities, `sideCurve`.
11. The planners `plan`, `couple`, `ports`.
12. The sealed pockets, the balance cache, `carriedPoint`, `balanceStarts`.
13. The boundary rules; `poolHeat`, `boilUp`, `holdsNoFluid`, `dry`.
14. The accepted pass (`acceptedEquations`).
15. The records `FlowSample` and `PipeObservation`.

S-01's moves inside the package are complete.

## Checks run here (scripts in `tmp/ds6/`, not tracked)

- `map.mjs` and `deps.mjs`: the member maps and the cross reference that chose every qualifier. The 30 unqualified `transport` reads are the local or parameter `Transport transport`. An escaped one would name the solver's private `ConservativeTransport.Workspace`, and the original would not have compiled.
- `cut1.mjs`: the cut.
  - It checks the exact count of each qualifier and literal edit, that every static site is imported, and that every import is used.
  - It applies qualifiers at code positions and link rewrites in comments only.
  - It keeps CRLF byte for byte. It ran first as a dry run.
- `verify.mjs` (against `743512ec1`):
  - 9 files; 1,619 lines removed and 1,656 added.
  - After normalising qualifiers, `private`, the type name and `#` links, 1,612 removed lines reappear.
  - The other 7 are the four holder imports, the `new Equations` line, the old `fixedOpenings` initializer and the old constructor header.
  - 42 added lines have no removed counterpart: header, document, the holder field, the new constructor lines and the solver's construction line.
  - 0 CRLF faults. Braces, parentheses and brackets balance in every file.
- `resolve.mjs` (0 problems):
  - every `solver.x` (18 names) is a non-private member, and its arity matches only the accessible overload (`viscosity`: 2 against 1);
  - the 22 static imports are package-visible statics, and every import is used;
  - no unqualified solver instance member is left but the `transport` locals;
  - the 52 `StepEquations` members that other files reach are all non-private.

## Points where compiling is not certain (checked by reading)

1. `StepEquations.java`:42 and :248-250: three blank finals (`solver`, `model`, `fixedOpenings`), assigned in the only constructor before any read. The parameters shadow the fields, so the assignments use `this.`.
2. `StepEquations.java`:3-24:
   - `import static …dry` beside the field `dry`. Fields and methods are separate namespaces, so `dry(node,…)` resolves to the import.
   - `holdsNoFluid` imports both static overloads.
3. `StepEquations.java`:428: `PassiveStepSolver.saturates(...)` is called from the instance `saturates(int)`.
4. `StepEquations.java`:1050: `SparseNewton.Equations.super.residualNorm(residual)`. The class implements the interface directly, so this is valid.
5. `StepEquations.java`:123-129: `var previous=solver.preparedPass` (typed `StepEquations`) and `previous.probePrepared` (private, in the same class).
6. `this` passed as a `StepEquations` to the renamed parameters of `MoverSuction.suctionLimit`, `DeviceModes.nextMode`/`cavitates`, `StepObservations.observe`/`npshRatio` and `PhaseActiveSet.flashes`/`anyDry`/`filled`.
7. Links to private solver members are javadoc only. The build runs no doclint.

**Left stale (outside the brief).**
- `{@code PassiveStepSolver.Equations…}` comments in `ConservativeTransport`:120, :131, :583, `JunctionCrystals`:13, `PassiveNetwork`:71, `PhaseDraw`:39, `PhaseLayout`:456, `ElevatedBlockLineIslandTest`:18, :25 and `FilterBlockLineIslandTest`:129, :136.
- Harnesses outside the lane's folders that build on their own solver copies. They name `PassiveStepSolver.Equations` or match the class name `PassiveStepSolver$Equations`:
  - `tools/process-control/seed236-probe/…/Probe236.java`;
  - `tools/unified-thermo-port-m5/trace/src-instr/M5RowTrace.java`;
  - `tools/fluid-world-inspector/AuditTrace.java`:21 and `tools/wti-pumping-rate-hold/trace-src/…/AuditTrace.java`:25;
  - the JFR site lists of `tools/junction-holdup-prototype/`.

## Tests for the main thread

Structure only: every reference bit for bit. The list is D-S5's 62 classes, unchanged. No test was edited. The move touches only the solver, the four holders and the new file.

| Task | Classes | What it proves |
|---|---|---|
| `test` | `science.fluid.network`: `PassiveStepSolverTest`, `BlockJacobianEquivalenceTest`, `SteamTurbineConnectionTest`, `BalanceTablesTest`, `RestResolutionTest`, `FlowControlTest`, `JunctionStartFormTest`, `HeatSideNetworkTest`, `CrossflowHeatTest`, `PrecipitatedSolidsTest`, `SolidClosureFeasibilityTest`, `SolidConservationTest`, `ValveCavitationTest`, `FlowmeterObservationTest`, `SuctionZoneEdgeTest`, `ReleasePressureTest`, `PumpRiseScalingTest`, `InletPhaseTest`, `MachineMoverTest`, `ShaftPowerBookingTest`, `PumpRateWarmStartTest`, `PressureValveContinuationTest`, `VentGatePolishTest`, `DrumFillColdStartTest`, `JunctionWaterTraceTest`, `EmptyNetworkTest`, `LevelHeadTest`, `PhasePortPriorityTest`, `VelocityClampTest` | the rows and the block Jacobian (`acceptedEquations`, `differentiateEntries`, `layout`, `offsets`, `filterOffsets`) bit for bit; the holders that take a `StepEquations` |
| `test` | `runtime.fluid`: `RetainedSolverTest`, `LiquidJunctionTransientTest`, `MixedGasJunctionTransientTest`, `KeptLuStorageTest`, `MachineHealthTest`, `ColumnRunTest`, `StillRuntimeTest`, `ExchangerCouplingTest`, `FiredHeaterFuelTest`, `HeatSideTopologyTest`, `SteamHeaterCoilTest`, `RegulatingValveHoldTest`, `InlineFilterLineHoldTest`, `FullTankSolidsEventTest`, `SolidRuntimeTest`, `PipelineFittingTest`, `DeadHeadBesideFlowingBranchTest`, `DeadHeadedLineIslandTest`, `PumpCavitationTest`, `SteamEjectorRuntimeTest`, `ReliefValveWorldTest`, `IslandSetsPresentedTest`, `EmptyRiserTest`, `EmptyTankFillsTest`, `LiquidCo2LetDownTest`, `VesselPhaseSeedTest`, `SlurryTankPumpTest`, `FilterBlockLineIslandTest` | the equations in worlds; the P-18 bundle hand-over (`preparedPass`) and the retained last solve (`RetainedSolverTest`) |
| `test` | `PortAccountsTest`, `LayeringTest`; `compileFluidGameTestJava` | no package rule crossed; the GameTests compile |
| `holdGates` | `ExtremeTopologyIslandTest`, `HoldClassWorldsTest`, `JunctionSeedingGateTest` | the extreme islands, the hold-class worlds, junction seeding |
| `fluidSolverRegression` | `FluidSolverRegressionTest` | chain-100 exact, with its counters |
| lane | `affected.mjs` classes for `PassiveStepSolver`, `StepEquations` and the four holders; the 33 junction lines, the printed lines, the fluid GameTests | every reference unchanged |

If a reference moves, the only edit that is not a qualifier is `StepEquations.java`:250.

## Code guide

In `fluid-science.md`:
- a new class-map row for `StepEquations`;
- the `PassiveStepSolver` row: `StepEquations` in place of the inner `Equations`;
- 24 mentions of `Equations.x` or `PassiveStepSolver.Equations` now read `StepEquations`;
- the pass-loop step;
- the trap line: "about 2000 lines", with all row assembly in `StepEquations` and why it is one class.

`node tools/code-guide/map.mjs --readme` set the README's `science/fluid/**` count from 80 to 81. `--check` reports the counts current. The batch README's history line is left to the main thread.
