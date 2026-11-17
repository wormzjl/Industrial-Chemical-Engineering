# V3 column solver core: problem, equations, Newton, linear algebra and thermo

Up: [code guide](README.md) · the calculator's route, seeds, audit and publication: [column-v3.md](column-v3.md)

## Read this when

- A rung's Newton attempt stalls, meets a singular matrix, or stops with an attempt code (`STALLED_SUPPORT_FLOOR` and
  the others of `V3AttemptCode`).
- You add an equation, an unknown or a feature row, or change the degree-of-freedom ledger, the residual, the Jacobian
  or the band LU.
- A trace component appears or vanishes at a tray (support, floor, reinsertion).
- Free water on the trays, the condenser's phase branch or the column's steam and water are wrong.
- The column's PR78 session, its feed flash or its package admission misbehave.

## What it does

The calculator's every rung hands one resolved problem to this core: `V3ColumnProblemResolver` turns an input into a
`V3ColumnProblem` (topology, pressures, support, wet set), `V3DegreeOfFreedomLedger` enumerates its unknowns and rows
and proves the system square and structurally full rank, `V3MeshResidualEvaluator` assembles the MESH residual,
`V3BlockJacobianAssembler` the stage tri-block Jacobian, and `V3SimultaneousColumnSolver` runs a damped Newton method on
log flows and kelvin, each step a banded LU solve (`V3BandedPivotedSolver`). The thermo boundary
(`v3/thermo`) evaluates fugacities, enthalpies and flashes on the shared PR78 kernel; `V3WaterProperties` holds the
column's water on the shared water module. Support (which component phases are unknowns at a node) and the wet-tray set
are frozen inside one Newton solve and refreshed between solves. `science/column/still` holds the batch still's own
column, on its own thermo boundary.

## Class map

Paths under `src/main/java/com/wormzjl/icheme/science/column/`; `v3/` unless named. "pp" = package-private. Lines at the
snapshot.

| Class | Role | Lines |
|---|---|---|
| **Problem** | | |
| [`V3ColumnProblemResolver`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnProblemResolver.java) | Validates input, builds topology and pressure profile (`pressureProfile`), proves the ledger; `validateInput`, `resolve(input, branch[, profile])`; `WATER` | 243 |
| [`V3ColumnProblem`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnProblem.java) | The resolved immutable problem: pressures, support, wet set, water profile (`waterVaporFlow`, `nodePressurePascal`); nested `WaterCondenserRegime` | 351 |
| [`V3ColumnTopology`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ColumnTopology.java) | Node map and condenser branch; `dryTopTrayCount` | 119 |
| [`V3DegreeOfFreedomLedger`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3DegreeOfFreedomLedger.java) | Enumerates unknowns and rows, checks count and structural rank, fixes the order; int tables (an index table in `UnknownId` order, sorted references, Kuhn's matching); `Equation` records built only when `equations()` is read; `equationIds()`; nested `ContractDiagnostic` | 517 |
| [`V3StageBlockLayout`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3StageBlockLayout.java) | Validates and exposes the ledger's contiguous per-node block layout | 111 |
| [`V3ComponentBasis`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3ComponentBasis.java), `V3ActiveComponentBasis` (pp) | The public axis (at most 64 compounds); the exact-zero elimination to active coordinates | 58, 65 |
| `V3OperatingDomainValidator` (pp) | The pressure profile against the package envelope; may ask for pressure continuation (`PROPERTY_OUT_OF_RANGE`) | 129 |
| **State and equations** | | |
| `V3DryMeshState` (pp) | Internal state: component liquid and vapour flows, temperatures, free water; never leaves the solver | 187 |
| `V3DryMeshCoordinateMap` (pp) | Log-flow and kelvin Newton coordinates in ledger order | 151 |
| `V3MeshResidualEvaluator`, `V3MeshResidual` (pp) | MESH residual assembly; the scaled vector held flat (`size`, `equation(i)`, `scaledValue(i)`; `rows()` for tests) | 672, 104 |
| `V3StageEquilibriumRatios` (pp) | Per-node K = exp(ln φ_L − ln φ_V) of a candidate, for deciding and auditing an absent phase | 136 |
| `V3WetTraySet`, `V3FreeWaterContinuation` (pp) | The trays with free water, frozen for one solve; the parametric free-water search | 303, 461 |
| [`V3CondenserPhaseBranch`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/V3CondenserPhaseBranch.java), `V3CondenserComponentPhases`, `V3CondenserPhaseTransition` (pp) | The hydrocarbon condenser branch (`TWO_PHASE`, `LIQUID_ONLY`, `VAPOR_ONLY`); its component rule; the request-local condenser flash projection | 8, 42, 119 |
| `V3TruncationSupport` (pp) | Per-phase support (`BOTH`, `LIQUID_ONLY`, `VAPOR_ONLY`, `ABSENT`), `TRACE_FLOOR_FRACTION`, the floor refresh decisions, `Reachability`, `SupportPoints.sank` | 817 |
| `V3FloorLift` (pp) | Before an attempt derives its support (`liftFloorSupport`): restores a removed point its neighbours deliver to (`deliveredInflow`, `reinsertRemovedPoint`), a one-phase point's absent phase (`liftAbsentPhase`), caps a retained point far above its inflow (`capOversizedPoint`, `OVERSIZED_SEED_FACTOR`); `componentFloor` | 247 |
| **Jacobian and Newton** | | |
| `V3BlockJacobianAssembler`, `V3BlockJacobian` (pp) | The stage tri-block Jacobian: exact material rows and local thermo probes (`assembleLocal`); an off-band guard | 699, 146 |
| `V3FiniteDifferenceJacobian` (pp) | The whole-system FD Jacobian (`evaluateCompact`, `evaluate`), for the coarse-FD recovery | 382 |
| `V3SimultaneousColumnSolver` (pp) | Damped Newton, Armijo search, fallbacks, the final-step certificate (`verifyFinalNewtonCorrection`); `solve`, `RungBudget`, `Attempt`; nested `NewtonTrace` | 1146 |
| `V3NormalEquations` (pp) | Attempt-local normal products for the regularized correction's damping trials | 169 |
| `V3SolveScratch` (pp) | The banded solver's workspace and the local Jacobian's blocks, one set per calculation (`within`); counts band factorizations by call site (`Factorization`); keeps the untruncated feed flash (`FEED_FLASHES`) | 183 |
| [`V3BandedMatrix`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/linalg/V3BandedMatrix.java) (`linalg/`) | Band storage | 85 |
| [`V3BandedPivotedSolver`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/linalg/V3BandedPivotedSolver.java) | Row-equilibrated LU with partial pivoting inside the band (dgbtrf layout); `solve`; `holdsNegativeZero`; the pooled `Workspace` counts entries updated, full-width entries and full-width solves | 479 |
| **Thermo (`v3/thermo`)** | | |
| [`V3ThermoModel`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3ThermoModel.java), [`V3ThermoDerivatives`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3ThermoDerivatives.java) | The hydrocarbon property boundary (fugacity, enthalpy, TP flash; the phase argument `PengRobinsonKernel.Root`); its optional derivatives | 22, 29 |
| [`V3PengRobinsonThermo`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3PengRobinsonThermo.java) | The PR78 façade on a registered package: `fromRegisteredPackage`, `flashTP`, `columnIdentity` | 309 |
| `V3PengRobinsonSession` (pp) | One package's allocation-free PR78 arithmetic on the shared kernel (`kernelFor`): the derived pairs (`V3PropertyPackage.derivedPairs`), else the authored matrix; the feed's stability test on that kernel (`stability`, retried with the engine's larger budget when unresolved) | 282 |
| [`V3PropertyPackage`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3PropertyPackage.java), `V3CatalogPropertyPackage` (pp), [`V3PropertyComponent`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3PropertyComponent.java) | The package selected before a solve; the catalog adapter (`require`, `identity`, `crudeFeed`, `feedEngine` built on first use; refuses a non-`pr78`, a crystal and a spine package); one hydrocarbon's SI data | 44, 103, 60 |
| `V3FeedEngine` (pp) | `FluidTpEquilibrium` on the derived evaluator of the package's compounds without water, fluid phases only, an open domain: the split of a feed the flash proves unstable and cannot split, with whether its lighter phase is the column's vapour by its own state (`Split`); none for a feed with water or a lens | 116 |
| [`V3CrudeFeed`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3CrudeFeed.java) | A normalised assay vector on a registered package | 55 |
| `V3FeedFlash`, `V3TruncatedFlash` (pp, nested `Support`), [`V3FeedPhase`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3FeedPhase.java), [`V3FlashResult`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3FlashResult.java), [`V3FlashTruncationEvidence`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3FlashTruncationEvidence.java) | The rigorous TP flash and its outcome; the reference-checked truncated flash and its evidence | 409, 405, 8, 113, 91 |
| [`V3FugacityResult`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3FugacityResult.java), [`V3FugacityDerivatives`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3FugacityDerivatives.java) | One phase's result and its first derivatives on the public axis | 60, 111 |
| [`V3ThermoWorkspace`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3ThermoWorkspace.java), [`V3ThermoException`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3ThermoException.java) | Caller-owned scratch; the typed property failure | 56, 34 |
| [`V3WaterProperties`](../../../src/main/java/com/wormzjl/icheme/science/column/v3/thermo/V3WaterProperties.java) | The column's water on the water module (`WagnerPruss`, `Iapws97Region1`, `SteamIdealGas`, `SteamVirial`, `WaterDatum`): saturation, the steam as the network's second-virial gas, the liquid on IF97 Region 1 and the datum (`liquidEnthalpyOffset`), `saturationPressurePascal`, `wagnerPruss`, `steamContinued`, `withSteamContinuationAdvisory` | 276 |
| **The still's column (`still/`)** | | |
| [`still/StillColumn`](../../../src/main/java/com/wormzjl/icheme/science/column/still/StillColumn.java) | The batch still's column: the bubble-point method (Wang and Henke) with its energy-balance flow update, in pot mode, quasi-steady; `solve`, `Split`, `Profile`, `Bubble`; documented with the still in [batch-still.md](batch-still.md) | 1260 |
| [`still/StageThermo`](../../../src/main/java/com/wormzjl/icheme/science/column/still/StageThermo.java) | What the still's column reads of its thermo (the basis's constants, a phase's H and V, ln φ at a prepared temperature, a wet liquid's TP split); implemented by the plant layer's `NetworkStageThermo` | 68 |


## Main flow

One rung, inside `V3AttemptLoop.solveSingleProblem` ([column-v3.md](column-v3.md)):

1. `V3ColumnProblemResolver.resolve(input, branch[, profile])` → `V3ColumnProblem` (with its ledger proved).
2. Feed flash (`V3FeedFlash`, or the truncated flash at a positive cutoff), kept untruncated per calculation in
   `V3SolveScratch`. Wilson's K, then successive substitution on the PR78 K-values. Where that gives no answer (no
   two-phase bracket at the Wilson endpoint, the Rachford-Rice root lost, the iterations out) the engine's
   `TangentPlaneStability` on the column's kernel decides: stable is one phase (the endpoint's phase; else liquid where
   the reduced density is at least 1 on one root, the root of lower Gibbs energy on two). Unstable is first read for its
   kind (`towardsVapour`, rule B): where neither the test's stationary phase nor the feed, each on its root, is the
   column's vapour (reduced density below 1, or on a supercritical isotherm of its own composition), the feed would only
   form a second liquid and is one liquid (the endpoint's answer, or one phase with the detail `SECOND_LIQUID`), with no
   restart and no engine call; the trays' notes report a liquid that would settle. Unstable towards a vapour starts or
   restarts the iteration once from the test's stationary phase, and then takes the engine's split (`V3FeedEngine`, no
   feed with water, enthalpies on the column's roots). A split counts only where its lighter phase is the column's vapour
   (the column's own split read on its vapour root, the engine's by the engine's own phase); a split into two liquids is
   one liquid. At the Wilson endpoint no path refuses: with no vapour-liquid split the endpoint's answer stands. Off it, a
   feed unstable towards a vapour with no split, or an unresolved test, stays `FLASH_NONCONVERGENCE`.
3. `prepareAttempt`: `V3FloorLift.liftFloorSupport`, then `V3TruncationSupport` and `V3WetTraySet` for this attempt.
4. `V3SimultaneousColumnSolver.solve`: per iteration `V3MeshResidualEvaluator` (residual), `V3BlockJacobianAssembler.assembleLocal`
   (or the FD Jacobian), `V3BandedPivotedSolver.solve`, the Armijo line search, the fallbacks; at convergence `verifyFinalNewtonCorrection`.
5. Back in the attempt loop: the audit; a floor-support refresh (at most 3) or a wet-set refresh (at most 3) and the
   next attempt.

## Contracts

- **Rows** (`V3MeshResidualEvaluator`, ordered by the ledger): component material balances scaled by local throughput,
  hydrocarbon VLE with a log water-dilution correction, tray and sump energy balances (stage heat, steam enthalpy,
  free-water transport), one water-saturation row per solved wet tray. Summation is implicit through normalised
  compositions. Unknown counts depend on support and wet set: use the ledger, not a formula.
- **Dry top.** The vapour-only prefix keeps component and energy balances and vapour flow, with liquid exactly zero and
  no VLE rows; the ledger derives a square system from the phase map. General dry middle sections are not an
  active-set feature. The initializer uses total component traffic across a missing-liquid section; `prepareAttempt`,
  stage interpolation and `V3NeuralSeed.stateFor` keep absent phases, and a newly appearing liquid is seeded from local
  vapour and downstream draw demand (numerical guesses, never published floors). The projection guard refuses a
  collapsed-phase seed and, for dry layouts, needs a better frozen-scale residual merit without worse energy closure.
- **Support.** An always-on floor `TRACE_FLOOR_FRACTION` of the component flow scale removes negligible points even at
  cutoff 0. Support is fixed inside one Newton solve and refreshed between solves (reinsertion hysteresis ×10, at most 3
  refreshes, at most 1 after a stall with a 16-iteration polish budget). A sinking point is held at its floor only when
  dropping it cuts a feed path (`Reachability`). A release (`STALLED_SUPPORT_FLOOR`) is not a stall: the solve held a
  cut-path point for 12 iterations without halving its residual, or a point that began live, off the feed tray, sank
  below the hold level when the Newton step failed; the refresh drops it and the next attempt has the full budget. A
  positive optional cutoff also enables `V3TruncatedFlash` and the `V3TruncationFallback` retry.
- **Wet trays.** A tray whose vapour is supersaturated in water gains a free-water unknown F_n and a saturation row; the
  telescoped balance W_n = steam at or below n + F_(n−1) keeps the Jacobian banded. The sump is never wet.
  `V3FreeWaterContinuation` holds F_n as parameters (4 sweeps, 12 solves per tray, 6 admitted trays) before a certifying
  simultaneous solve; parametric states never publish. A side draw takes S/L of the tray's liquid; free water is not
  drawn with it.
- **Condenser.** The hydrocarbon branch and the water regime (`NONE`, `FREE_WATER`, `ALL_VAPOR`) are separate; one warm
  correction per rung (`V3AttemptLoop.correctCondenserPhase`).
- **The PR session** evaluates inside `NumericalBox`. Outside a caloric fit's ends it continues H with the edge Cp,
  keeping value and slope; an accepted extrapolation adds a notice. `V3PengRobinsonSession.kernelFor` builds the shared
  kernel from the package's derived pairs (`DerivedPairs.derive`), so the column and the network solve one equation of
  state on one list; a lens keeps its own matrix.
- **The column's water** (`V3WaterProperties`): `saturationPressurePascal` and `wagnerPruss` take 0 < T ≤ Tc (the
  supercooled branch below the triple point) and refuse above Tc; the steam's enthalpy and heat capacity hold the
  Shomate fit's end heat capacity past its data, and an accepted state with steam there gets one advisory, never a hold.
  No network class calls it: the network reads the same module functions.
- **Determinism.** Column numerics use `DeterministicMath`.
- **Threads.** A calculation's scratch (`V3SolveScratch.within`) is opened at the calculator's entry and dropped when
  it returns; every class here is immutable or attempt-local.

## Laws and constants

- **Pressures** (`V3ColumnProblemResolver.pressureProfile`): condenser and tray 1 at the top pressure, tray j at
  P_top + (j − 1)·drop, the sump equal to tray N.
- **Newton** (`V3SimultaneousColumnSolver`): a banded direction from the local stage blocks (exact material derivatives
  and local thermo probes) or the stage-coloured FD Jacobian; Armijo coefficient 1e-4, 20 line-search steps (40 for the
  coarse recovery); damped Gauss-Newton and normalised-gradient fallbacks limited to 0.25 log-flow and 10 K per step.
- **Band LU** (`V3BandedPivotedSolver`): the swap and update stop at the pivot rows' extent
  ju = max(ju, min(pivotRow + ku, n − 1)); a band holding a −0.0 after row scaling takes the full width
  (`holdsNegativeZero`), so the bits are a full-width elimination's; the update loop runs alone, then the growth maximum
  in its own pass.
- **Factorization counts** (`V3SolveScratch.Factorization`): an accepted solve's last solver event reads
  `band factorizations: newton n, local block n, verify direct n, verify damped n, damped fallback n, total n`.
- **Energy shift** (`V3EnergyShiftPredictor`, [column-v3.md](column-v3.md)): at most 40 K per tray.

## Formats

The core saves nothing. Saved and wire formats of the column: [FORMATS.md](../FORMATS.md).

## Tests and gates

All under `src/test/java/com/wormzjl/icheme/science/column/v3/` unless named; they run in `columnGates`.

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| An equation, unknown or feature | ledger, resolver, residual evaluator, block assembler, auditor | `V3DegreeOfFreedomLedgerTest`, `V3MeshResidualEvaluatorTest`, `V3BlockJacobianAssemblerTest`, `V3FiniteDifferenceJacobianTest`, `V3OperatingDomainValidatorTest` |
| Newton, linear algebra | `V3SimultaneousColumnSolver`, `linalg/*` | `V3SimultaneousColumnSolverTest`, `V3NormalEquationsTest`, `V3NewtonMatrixTest`, `V3BandedPivotedSolverTest` (the extent and the −0.0 band against a dense full-width reference), `V3PivotGrowthTest`, `V3BandFactorizationCountTest` |
| Trace components, support | `V3TruncationSupport`, `V3FloorLift`, the attempt loop's refresh | `V3TraceFloorSupportTest`, `V3TruncationSupportTest`, `V3PhaseTruncationTest`, `V3TruncationStructureTest`, `V3FlashTruncationColumnTest` |
| Free water, steam | `V3WetTraySet`, `V3FreeWaterContinuation`, `V3WaterProperties` | `V3FreeWaterTrayTest`, `V3WaterPhaseQualificationTest`, `V3WaterPropertyDerivativesTest` |
| PR session, package admission | `V3PengRobinsonSession`, `V3CatalogPropertyPackage` | `V3PengRobinsonKernelTest`, `CrystalPackageConsumersTest`, `RegroupedCrudeTest`, `V3AqueousColumnTest` |
| Feed flash | `V3FeedFlash`, `V3FeedEngine` | `V3FeedFlashOnePhaseTest`, `V3FeedFlashStabilityTest` (the 181, the 102 and a sample of the 721 states of the review's grid, from `feed-flash-stability-states.tsv`; rule B on the wet 320 K distillate), `V3FeedFlashEquivalenceTest`, `V3PengRobinsonThermoTest`; `ColumnFeedOnePhaseOracleTest` (in `test`) |
| The still's column | `StillColumn`, `StageThermo` | `BatchStillTest`, `StillHeadTest` ([batch-still.md](batch-still.md)) |

Fixtures: `V3TestInputs`, `V3JacobianOracle` (the FD Jacobian and block extraction), `V3ManufacturedFlash`,
`V3TjlReferenceCatalog`, `V3ZeroPairCrudePackage` (the medium crude with zero pairs, the rank-one kernel fixture).
Column tests run on the game's medium crude preset (`V3TestInputs.mediumCrudeInput`, `mediumCrudeDry`, `crudeBinary`).

## Traps

- `V3BlockJacobianAssembler`'s class comment still describes FD extraction; that extraction lives in the test tree
  (`V3JacobianOracle.assemble`), and the production class holds only `assembleLocal`.
- "Dry" in `V3DryMeshState` names the hydrocarbon basis: free water is carried too.
- The feed flash calls the equilibrium engine (`TangentPlaneStability`, `V3FeedEngine`): the column gate group loads
  `science/thermo/phase` and the stability test, so a change there reruns `columnGates`.
- `V3TruncatedFlash.Support.PhaseSupport` is the column's own enum, not the kernel's `PhaseSupport`.
- `V3WaterProperties` and the network share the water module: a change to a module function moves the column and the
  network together (both gate groups list the module in `build.gradle`).
- The Rust column library (a band LU through JNI, default off) was archived on 2026-11-17: `tools/rust-column-archive/`.

**Snapshot:** `410c37fcb`, 2026-11-16.
