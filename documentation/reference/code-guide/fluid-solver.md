# The fluid solver core: node rows, Newton, sparse LU

Up: [code guide](README.md). The step that drives it: [fluid-step.md](fluid-step.md). The property model it evaluates: [fluid-thermo-port.md](fluid-thermo-port.md).

<!-- j2a-packages: science/fluid/solver/ science/fluid/linalg/ science/fluid/diagnostics/ science/fluid/SolverOwnership.java -->

## Read this when

- A Newton stalls, backtracks to nothing or meets a singular matrix; the Jacobian looks wrong; an LU refuses a step (`SolveFailure`).
- A node's unknowns or rows are wrong: the phase split rows, the wall row (slack, taut, rigid, open), a junction's closure, the atmosphere at a height.
- A second worker on one island fails fast, or a result depends on what an island solved before.
- You need a cost counter for a campaign or a benchmark (`SolverDiagnostics`).

## What it does

`PhaseLayout` lays out one node for a frozen phase set: its unknowns, its rows and the decode from unknowns to a property state. `SparseNewton` solves the island's whole system (the rows `StepEquations` assembles, [fluid-step.md](fluid-step.md)) with a damped Newton on a coloured finite-difference or block Jacobian. Each linear solve is `SparseLuSolver`, an up-looking sparse LU (`UpLookingLu`) with reverse Cuthill-McKee ordering, equilibration and a backward-error check. `StepPhaseRules` holds the step's phase rules that run a Newton solve of their own: the outer phase check and a pipe junction's own split. `SolverOwnership` latches a retained workspace to one worker at a time, and `SolverDiagnostics` holds the opt-in process-wide counters.

## Class map

Under `src/main/java/com/wormzjl/icheme/science/fluid/`. Lines at `d6f721b94`.

| Class | Role | Lines |
|---|---|---|
| [`solver/PhaseLayout`](../../../src/main/java/com/wormzjl/icheme/science/fluid/solver/PhaseLayout.java) | One node's unknowns and rows for a frozen phase set: variable encoding (`encode`, `decode`, `reactivate`), vessel and junction rows (`residual`, `equilibriumResidual`, `junctionClosure`), a vessel's closure with its Jacobian from the base state's tangents (`wallClosure`, its `differentiateEntries`), the wall row (`wallRow`, `Wall`, `rigid`, `bagVolume`), the kernel support of its evaluations (`support`, `carried`, `carriedState`), the atmosphere by height (`atmosphere`), Kahan `sum`; nested `Vanished` | 742 |
| [`solver/SparseNewton`](../../../src/main/java/com/wormzjl/icheme/science/fluid/solver/SparseNewton.java) | Damped Newton: the `Equations` contract, coloured FD or block Jacobian, chord reuse, line search, `Workspace` (forks share one LU storage per structure), `KeptFactors` (storages of solves on their own workspace), `Settings`, `Nonconvergence`, `ITERATIONS` | 425 |
| [`solver/StepPhaseRules`](../../../src/main/java/com/wormzjl/icheme/science/fluid/solver/StepPhaseRules.java) | Static phase rules of a step on the model they judge: `phaseCheck` (with `tpSeed` and a vessel's `crossing`) and a pipe junction's own split `junctionSplit` | 174 |
| [`linalg/SparseLuSolver`](../../../src/main/java/com/wormzjl/icheme/science/fluid/linalg/SparseLuSolver.java) | Sparse LU on `UpLookingLu`: `Ordering` (RCM), equilibration, backward-error check, `Storage` (one worker's LU and buffers, refilled in place), `Factorization`, `SolveFailure` | 310 |
| [`linalg/UpLookingLu`](../../../src/main/java/com/wormzjl/icheme/science/fluid/linalg/UpLookingLu.java) | The mod's own column-by-column (Gilbert-Peierls) LU with row pivoting, bit for bit EJML 0.44's `LuUpLooking_DSCC` without a fill-reducing permutation; keeps each column's reach while the pattern and the earlier pivots repeat; `searches()` | 278 |
| [`linalg/SparseMatrix`](../../../src/main/java/com/wormzjl/icheme/science/fluid/linalg/SparseMatrix.java) | Immutable CSC matrix | 66 |
| [`diagnostics/SolverDiagnostics`](../../../src/main/java/com/wormzjl/icheme/science/fluid/diagnostics/SolverDiagnostics.java) | Opt-in process-wide counters and timers, off by default (`phaseSeeds`, `phaseRefusals`, `luColumnsFactored`, `luReachSearches`, `jacobianTangentsBuilt`, `jacobianExactColumns` among them) | 300 |
| [`SolverOwnership`](../../../src/main/java/com/wormzjl/icheme/science/fluid/SolverOwnership.java) | Exclusive-use latch of a workspace that outlives a job: `confinedToCurrentThread`, `released`, `acquire`, `release`, `check` | 43 |

## Main flow

`SparseNewton.solve(equations, x0, settings)` for one pass:

1. Evaluate the residual (`Equations.residual`); weight the rows.
2. Build the Jacobian: the block sweep `StepEquations.differentiateEntries` (a node column's perturbed phases from the base state's tangents, `FluidThermodynamics.tangents` and `PhaseLayout.decode(variables, offset, prepared, tangents)`), or the coloured one-sided finite differences (relative step 1e-6) where the block form does not apply. A vessel's closure (`PhaseLayout.wallClosure`) builds its Jacobian the same way: one tangent evaluation of the base state, then each column's trial state decoded on the tangents; a refused trial falls back to the differences. A junction's closure stays on differences.
3. Factor through `SparseLuSolver` on the workspace's `Storage` (ordering reused per pattern); solve.
4. `maximumStep` keeps amounts positive; `project` holds traces below `TRACE_CLAMP_FRACTION` × floor instead of shrinking the whole step.
5. Natural (affine-invariant) merit line search, up to 24 backtracks.
6. Chord steps reuse the Jacobian while `linearisationHolds` (`CHORD_CONTRACTION`, [fluid-step.md](fluid-step.md)).
7. Converged at the pass tolerance, or `Nonconvergence` with the last iterate after `ITERATIONS`.

Inside each evaluation, `PhaseLayout.decode` turns a node's unknowns into a `FluidThermodynamics.State` (named on `FluidThermodynamics.Prepared.carry` with its kernel support), and `PhaseLayout.residual` states the node's rows on it.

## Contracts

- **Every production solve takes `ITERATIONS` at most**: the pass, the polish, a vessel's closure, a junction's or a vessel's split, the crystal joint closure. The balance's own Newton has literal limits ([fluid-step.md](fluid-step.md)).
- **`Nonconvergence`** carries the last iterate and an optional `ThermoHold` (`phaseHold()`); `at(node)` attaches the node. A trial outside `NumericalBox` or with no equation-of-state root (`HydrocarbonModel.RootAbsent`) is a plain `IllegalArgumentException`: a rejected trial, never a hold.
- **A layout is evaluated by one thread at a time.** It keeps row scratch (`slotTotals`, `slotFugacities`, `slotLogs`, `slotSeen` of `equilibriumResidual`; a junction's `totals` and `junctionSolidTarget`).
- **LU storages live with the island's retained handle** (`RetainedSolver`, [runtime-engine.md](runtime-engine.md)) across steps and jobs, one per matrix role and structure, so a factorization of a structure the island has seen reuses its arrays and each column's kept reach while the pivots repeat:
  - the step Newton: one storage per active-set structure (`SparseNewton.Workspace.Shared`, shared by the structure's fork family; four structures kept);
  - the booking: one storage and ordering per matrix structure (`ConservativeTransport.Workspace`; four kept);
  - solves on a workspace of their own (a vessel's closure, a split, a crystal's joint closure): one storage per declared Jacobian pattern (`SparseNewton.KeptFactors`, `RETAINED_PATTERNS`), which `PassiveIntervalSolver` binds to the worker for each interval solve; a solve borrows it at its first factorization and gives it back at its end. Outside an interval solve, or on a thread without the island's latch, such a solve builds its own.
  - A new handle (a hold, a certificate wake or revalidation, a property suspension, a new island) rebuilds them. They are state, never saved.
- **A reused `UpLookingLu` gives a fresh one's bits**: only capacity and the kept reach carry over (its class comment, "Reuse across factorizations"). Pivoting is strict `>`, first of equals wins, no threshold.
- **Ownership.** One `SolverOwnership` per retained island solver: `acquire`/`release` around a job, `check` in every workspace entry; a second concurrent claim fails fast.
- **Determinism.** Every transcendental goes through `science.math.DeterministicMath`; sums use Kahan `PhaseLayout.sum`; maps that order work are `TreeMap` or `LinkedHashMap`.
- **`science/fluid/thermo` imports nothing of `science/fluid/solver`.** The phase rules that run a Newton solve are `StepPhaseRules` here, taking the model as an argument and reading its public fold (`folded`), `vanishing`, `nothing`, `sum` and engine entries (`LayeringTest`, rule `fluid-thermo-below-solver`).

## Laws and constants

**Node unknowns** (`PhaseLayout`): per present component its total (scaled by the node amount) and, for each further slot that supports it (a mask of `LIQUID`, `VAPOR`, `AQUEOUS`), `ln(n_ik / n_i,ref)`, with the row `ln(n_ik/n_k) + ln phi_ik = ln(n_i,ref/n_ref) + ln phi_i,ref`, all at the state pressure; then `ln(T/350)`, `ln(P/1e5)` and three solid moments. A node of two slots keeps the unknowns and rows it had. Edge unknowns (one law flow per connection, a device head scaled by 1e5 Pa, a filter loading) are `StepEquations`'.

**Vessel rows** (`PhaseLayout.residual`): backward-Euler component balances `n = n_old + dt (sum in - sum out)` with donor compositions; an energy balance with flow enthalpy, shaft work, the heat a heat-exchanging connection delivers with its stream, a kettle pool's heat (explicit on the step's start state) and the wall's work on the ambient; the phase-split rows above; the wall row. No stream or ledger carries potential energy; a pump's lift work arrives in the lifted fluid as heat.

**Wall row** (`PhaseLayout.wallRow`): `max(taut, slack) = 0` with `taut = (V - V_max)/V_max` and `slack = 2 (P_amb - P)/P_amb`. The Jacobian uses the branch of its build point (`Wall`, `linearising`). An open vessel's capacity is infinite (`PassiveNetwork.Inventory.wallCapacity`), so its row is the slack branch alone, its surface at the atmosphere of its height. A rigid vessel has the capacity token `-V_max` (`rigid`): its row is the taut branch alone, its bag is `V_max`, its wall does no work (`bagVolume`, `wallWorkPressure`), so it stands below the ambient when drawn.

**Atmosphere by height** (`PhaseLayout.atmosphere(y)` = `AMBIENT_PRESSURE` − `AIR_DENSITY` g (y − `SEA_LEVEL`)): the pressure at every boundary open to the air; sinks and open vessels stand at the atmosphere of their height. Closed slack bags and the seed paths keep `AMBIENT_PRESSURE`.

**Junction closure**: a junction's last basis component has no mixing row; the closure row states it. That row's sum stands near 1, where a difference step of a share under 1.1e-10 rounds away and leaves an empty column, so the largest share takes that place (the last of `junctionBasis`) and every trace keeps a row of its own. `junctionClosure` isolates a pipe junction's own rows (no net flow at a retained pressure), ending with `Vanished` where a point empties a start phase.

**Support** (`support`, `carried`): the kernel support of a node's evaluations is what the seed holds plus its component mask, so `ln phi` is defined at zero amount of what can arrive; a stand-in liquid admits every component; `equilibriumResidual` states no split without hydrocarbon components. `carriedState` evaluates a seed again on the whole list where one of its phases did not carry a layout compound.

**Phase check** (`StepPhaseRules.phaseCheck`): never inside a residual; a node at fixed amounts, energy and volume has one equilibrium, which its own rows find once its layout holds every phase it needs, so the check never swaps one populated phase set for another on a TP verdict, and an added phase starts at the node's own split. **Junction split** (`junctionSplit`): the junction's rows closed by a Newton solve of `PhaseLayout.junctionClosure` from the engine's PH answer; null where the closure fails or empties a start phase. The folds and shares these rules read are in [fluid-step.md](fluid-step.md) and [fluid-thermo-port.md](fluid-thermo-port.md).

| Constant | Meaning | File |
|---|---|---|
| `ITERATIONS` | the Newton's iteration limit for every production solve | `SparseNewton` |
| `RETAINED_PATTERNS` | kept storages of `KeptFactors` | `SparseNewton` |
| `AMBIENT_PRESSURE` | the same constant as `FluidThermodynamics.AMBIENT_PRESSURE` | `PhaseLayout` |
| `AIR_DENSITY`, `SEA_LEVEL` | the atmosphere's fall with height | `PhaseLayout` |
| `LOG_READ_SPAN` | the span a log unknown is read over | `PhaseLayout` |
| `TRACE_DIFFERENCE_GUARD` | the guard of a trace's difference | `PhaseLayout` |
| `MAXIMUM_BACKWARD_ERROR` | the LU's backward-error bound | `SparseLuSolver` |

## Formats

None. The LU storages and Newton workspaces are never saved.

## Tests and gates

| Rule | Test |
|---|---|
| The Newton, its structural reuse | `SparseNewtonTest` |
| Node layout and rows | `PhaseLayoutTest`, `TraceTruncationLayoutTest`, `DirectLiquidJacobianRowsTest` |
| The block Jacobian and the tangents | `BlockJacobianEquivalenceTest`, `HydrocarbonTangentTest`, `WallClosureJacobianTest` (the vessel closure's) |
| The LU, bit for bit against EJML on step matrices, random patterns and three singular ones | `UpLookingLuParityTest` (takes the step matrices from `SparseLuSolver.observer`, null outside it) |
| The kept LU storages | runtime `KeptLuStorageTest` |
| The LU solver; the jar ships no EJML | `SparseLuSolverTest`, `PackagedSparseLuTest` |
| A job does not depend on earlier jobs | runtime `RetainedSolverTest` |
| No direct `Math.log/exp/pow` | `DeterministicMathSourceTest` |
| The layer rule | `LayeringTest` |

EJML is a test dependency of the parity gate only; the jar keeps EJML's Apache-2.0 notice (`META-INF/licenses/`) for the derived `UpLookingLu`.

## Traps

- **The SLACK/TAUT "modes" are not an enum.** There is one semismooth row (`wallRow`) and a branch record (`PhaseLayout.Wall`). Search for `taut`.
- **`SparseNewton.Settings.defaults()` states its own iteration count and tolerance.** Only tests call it; production passes `ITERATIONS`.
- **`SolverDiagnostics` is measurement, never state.** Its counters are off by default and process-wide.
- **The block sweep is not exact.** A perturbed node's phases are first order in volume, enthalpy, heat capacity and ln phi (exact in T, P, amounts and water's share); a column whose composition shift passes `FluidThermodynamics.TANGENT_COMPOSITION_STEP` takes the exact phase. The Newton reaches the same root. The same holds for the vessel closure's Jacobian: a closure that iterates ends within its tolerance at the same root, in other last digits than differences gave.

**Snapshot:** `cdb2833e1`, 2026-11-15.
