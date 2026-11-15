# D-S3 The plant's planners out of the step solver, and the laws stated twice (S-06, S-08): report

Batch D-S3 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-a046ea43a0361a627`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `d33c0a18a`. Two code commits: `2ffc709b4` (S-06) and `0757290cb` (S-08), then the report and the code guide. **Nothing was compiled or run** (the brief: no Gradle, no JVM). Structure only: the same floating-point operations in the same order and the same model calls in the same order, so chain-100, the junction lines, the printed counters and the poll counts must not move.

**Lines.** `PassiveStepSolver` goes from 5,808 to 5,793 lines (−15): S-06 −7 (12 in, 19 out), S-08 −8 (33 in, 41 out). `PortStreams` goes from 251 to 257 (+6, `spanned`). `NetworkReduction` changes one line. No new file, no file moves, no visibility change except the new package-private `cakeResistance`.

**S-06: no planner moves.** Every planner has a caller in `science/fluid`, or is called only by a planner that has one (table 1):

- `PassiveIntervalSolver.solveKept` (science/fluid/network, lines 98 and 99) calls `implicit.ports` and `implicit.couple` at each interval start.
- Its step loop calls `PassiveStepSolver.plansHeat` (268) and `implicit.plan` (277).
- `couple` calls `pools`, `flueGas` and `valvePipe`. `ports` calls `sameShares`.

`LayeringTest`'s `fluid-free-of-plant` forbids `science/fluid` to name `science.plant`, so by the brief's rule they all stay. No planner is called from `runtime/**`, `science/flowsheet/**`, `science/plant/**`, `src/fluidGameTest` or `tools/**` (I checked by grep). The review's premise was that the running column, the still and the heat units call the planners. In fact they build the plans (`PortPlan`, `ExchangerRelay.Read`), and these reach the planners through `ProcessSolveServices`:395 (not my lane), then `RetainedSolver`:65, then `PassiveIntervalSolver.solve(..., reads, ports)`. So `Stills`, `ColumnOperation` and the sheet have no planner call to change.

Moving a planner out would also move its call. The runtime would then plan the graph before `PassiveIntervalSolver` calls `PassiveNetwork.sizeJunctionHoldups`, which mints junctions through the model. The model calls would come in another order, and the poll counts are results. **Option for the main thread** (not done, design, not structure): `science/fluid` declares a slice-planner interface that `PassiveIntervalSolver` calls at the same two points, and `science/plant/port` implements it. Then `ports`, `couple` and `plan` could move with their calls in the same place. `plan` and its `sideCurve` read the solver's `sideCurves` cache and counters, so the interface would have to hand those over.

**S-06: `reading` builds no solver.** The brief's condition holds: `reading(partner)` was read only for its `model` and for `spanned`, and `spanned` reads nothing but the model. The changes:

- **`PortStreams.spanned(model, s, port, volume, low, high, films, checkpoint)`** has the old private `spanned`'s body: the same `endMoles`, sum, `endMass` and `HeatCurve.sample` calls, in the same order.
- **`partnerModel(partner)`** returns `partner.model()==null ? model : partner.model()`. That is exactly `reading(partner).model` on every path: null or this model gave `this`, and any other model gave a solver whose `model` is that model.
- **The field `partnerReader` goes.** The partner solver's constructor made no model call and counted nothing: two `requireNonNull`, a `ConservativeTransport.Workspace(ownership)` that only stores the latch, and empty maps, arrays and a viscosity workspace. So dropping it removes no counted operation.
- **The seven call sites** in `couple` (2454, 2459, 2460, 2464) and `pools` (2576, 2579, 2580) evaluate their arguments in the old order.
- **No `PortStreams` function became public**, because no planner moved.

**S-08.** Three laws are now stated once (tables 2 to 4). Two are left with their difference named (tables 5 and 6). The pipe inversion (`initialMassFlow`'s bisection 1880, `supplyFlow` 3229, `BalanceNet.runFlow` and `curveFlow`) is PERF in the review: left.

- **Filter cake law:** `static double cakeResistance(InlineFilter filter, double viscosity, double loading)` (PSS:53) is `R*(viscosity/.001)*(1+99*loading*loading)`, the common left-to-right prefix of the three copies. Each reader finishes it on its own operands: the balance's `prepare` (3400) and `NetworkReduction.resolutionFlow` (NR:238) divide by the density; `Equations.edgeRows` (5494) multiplies by the fluid volume, divides by the mass and multiplies by the law flow. Java evaluates `a*b*c/d` as `((a*b)*c)/d`, so each result is the old one bit for bit. Its home is the solver, not `InlineFilter` (not in my lane): `NetworkReduction` already reads `PassiveStepSolver.boundaryAllowed`, `holdsNoFluid` and `poolHeat`.
- **Mixing-enthalpy bisection:** `private static double[] mixingBracket(model, y, heat, mass, tLow, tHigh, pressure, checkpoint)` (PSS:3201) is the shared loop: the window, 60 halvings, the same `flashTP` calls. `seedAt` flashes the midpoint inside its old `try`. `BalanceNet.mixed` flashes both sides after its PH answer and keeps the nearer side. A refused flash throws through the function to the same handler as before (seedAt's `catch`; none in `mixed`).
- **Incidence table:** `incidence(graph)` (PSS:3801) now also builds the tables of `Runs.of` (129), `startPoint`'s solids flows (1831) and the `Equations` constructor (4460). Integers only. `Runs.of` counted the degree in the same loop that marks actuated nodes. Now `incidence` builds the table and a separate loop marks them, and `interior` reads `nodeEdges[i].length==2`, which is the old `degree[i]==2` (the count before the reset). The arrays are the same.

**Planners, `tools/` and tests.** No harness under `tools/` names `reading`, `spanned`, `partnerReader`, `cakeResistance`, `mixingBracket` or `incidence` (grep, archived copies included). No test reads them: they were private. `RegulatingValveHoldTest`:137 and `FlowControlTest`:119 call `new PassiveStepSolver(model).ports(...)`, which is unchanged.

**Code guide** (`fluid-science.md`): the `PortStreams` row gets `spanned` and `partnerModel`. The `PassiveStepSolver` row gets the planners that stay and why, and the three laws stated once. The component-basis paragraph changes `reading` to `partnerModel` and `PortStreams.spanned`. `node tools/code-guide/map.mjs --check`: counts current (no file was added, so `--readme` was not needed). Stale, not mine: the trap line "`PassiveStepSolver` is about 4900 lines" (fluid-science.md:342) gives old line numbers. No `build.gradle` change: neither gate group copies `PassiveStepSolver`, `PortStreams` or `NetworkReduction` (`columnGates` loads `HeatRouting` and `PipeResistance`; `equipmentGates` loads `HeatCurve` and the heat laws, all unchanged).

**Points where compiling is not certain** (each checked by reading, none compiled):

1. PSS:53, `cakeResistance` is a package-private static. `InlineFilter` is in the same package. `PassiveNetwork.Pipe.filter()` is declared `InlineFilter` (PN:272). NR:238 calls it qualified.
2. PSS:129, `Runs.of` (a static method of a private nested record) calls the enclosing class's private static `incidence`: legal. The local `edges` went with the loop that read it.
3. PSS:4460, `nodeEdges=incidence(graph);` assigns the final field `Equations.nodeEdges` once, as the removed block did. `graph` there is the constructor's parameter, which is the same object as `this.graph`. No other line of the constructor read `degree`; I checked by grep.
4. PSS:1831, `int nodes=...;int[][] nodeEdges=incidence(graph);`: `nodes` is still read by the loop below, and `degree` was read nowhere else.
5. PSS:3189 and 3794, `var bracket` is a `double[]`. In `mixed`, `double lower=bracket[0],upper=bracket[1];` declares no name declared again later. `mixingBracket`'s parameter `model` shadows the instance field inside a static method, which is legal.
6. PSS:2585, `partnerModel` returns `FluidThermodynamics`. `ExchangerRelay.Partner.model()` is a `@Nullable FluidThermodynamics` of the same import (`science.fluid.thermo`). `ownership` is still read elsewhere (8 uses).
7. PortStreams:178, `spanned` returns `HeatCurve` (same package) and takes `HeatCurve.Films`. `endMoles` and `endMass` resolve to `PortStreams`' own statics.
8. Javadoc links to private members (`BalanceNet#prepare`, `Equations#edgeRows`, `BalanceNet#mixed`) are doc only; the build has no doclint (as in D-S1).
9. Line endings: every changed file is still CRLF throughout (a node check, `tmp/d-s3/crlf.mjs`). No non-ASCII byte was added.

**Tests for the main thread** (structure only: every reference bit for bit):

| Task | Classes | What it proves |
|---|---|---|
| `test` | `PassiveStepSolverTest`, `BlockJacobianEquivalenceTest`, `SteamTurbineConnectionTest`, `BalanceTablesTest`, `RestResolutionTest`, `FlowControlTest`, `JunctionStartFormTest`, `HeatSideNetworkTest`, `CrossflowHeatTest`, `PrecipitatedSolidsTest`, `SolidClosureFeasibilityTest`, `SolidConservationTest` (all `science.fluid.network`) | the step solver bit for bit; the cake law in the edge row and the balance (filter and solids tests), and in `resolutionFlow` (`RestResolutionTest`); the mixing bisection (`JunctionStartFormTest`'s mint, `BalanceTablesTest`); `ports` (`FlowControlTest`); the heat sides (`HeatSideNetworkTest`). `CrossflowHeatTest` is the brief's: it names no solver, so it may load none of the changed classes (the lane's `affected.mjs` decides) |
| `test` | `RetainedSolverTest`, `LiquidJunctionTransientTest`, `MixedGasJunctionTransientTest`, `KeptLuStorageTest`, `MachineHealthTest`, `ColumnRunTest`, `StillRuntimeTest`, `ExchangerCouplingTest`, `FiredHeaterFuelTest`, `HeatSideTopologyTest`, `SteamHeaterCoilTest`, `RegulatingValveHoldTest`, `InlineFilterLineHoldTest`, `FullTankSolidsEventTest`, `SolidRuntimeTest`, `PipelineFittingTest`, `DeadHeadBesideFlowingBranchTest`, `DeadHeadedLineIslandTest` (all `runtime.fluid`) | the planners in worlds: the column's and the still's ports, an exchanger's partner on its own model, a kettle's pool, a fired heater's plan; `Runs` (dead heads); the filter lines |
| `test` | `PortAccountsTest` (`science.plant.port`), `LayeringTest` | the plant layer unchanged; `science/fluid` names nothing of `science.plant` |
| `holdGates` | `ExtremeTopologyIslandTest`, `HoldClassWorldsTest`, `JunctionSeedingGateTest` | the extreme islands, the hold-class worlds and junction seeding (the mint, the bisection, the incidence tables) |
| `fluidSolverRegression` | `FluidSolverRegressionTest` (chain-100) | chain-100 exact, with its counters |
| lane | the classes `tools/merge-lane/affected.mjs` names for `PassiveStepSolver`, `PortStreams` and `NetworkReduction`; the 33 junction lines and the printed lines; the fluid GameTests (they load the solver) | every reference unchanged |

## Table 1: the planners

Line numbers are the current ones.

| Planner | Line | Reads | Callers | Stays because |
|---|---|---|---|---|
| `plansHeat(graph)` (static) | 2354 | its argument | `PassiveIntervalSolver`:268 | caller in science/fluid |
| `plan(graph, flows, checkpoint)` | 2358 | `sideCurve`: the fields `sideCurves`, `heatCurveSamples`, `heatCurvesInResidual`, `inResidual`; `model` | `PassiveIntervalSolver`:277 | caller in science/fluid; reads solver fields |
| `flueGas(graph, reads)` (static) | 2393 | its arguments | `couple` | its caller stays |
| `couple(graph, reads, flows, seconds, checkpoint)` | 2421 | `model`, `pools`, `flueGas`, `valvePipe`, `partnerModel` (was `reading`: `partnerReader`, `ownership`), `VALVE_DEADBAND` | `PassiveIntervalSolver`:99 | caller in science/fluid |
| `VALVE_DEADBAND` (constant) | 2491 | none | `couple`, `ports`, `GasBlanket`, `ShaftPlan` (science/fluid), `Ejectors`, `ShaftTrains`, `Stills` (runtime) | read in science/fluid |
| `ports(graph, plans, checkpoint)` | 2500 | `model` (`flashTP`), `sameShares`, `VALVE_DEADBAND` | `PassiveIntervalSolver`:98; `RegulatingValveHoldTest`:137, `FlowControlTest`:119 | caller in science/fluid |
| `sameShares(a, b)` (static) | 2536 | its arguments | `ports` | its caller stays |
| `valvePipe(graph, valve)` (static) | 2543 | its arguments | `couple` | its caller stays |
| `pools(graph, reads, seconds, checkpoint)` | 2556 | `partnerModel` (was `reading`), `holdsNoFluid`, `ExchangerRelay.planPool` | `couple` | its caller stays |
| `reading(partner)` → `partnerModel(partner)` | 2585 | was `partnerReader`, `ownership`, `model`; now `model` only | `couple` ×3, `pools` ×3 | rewritten: builds no solver |
| `spanned(...)` → `PortStreams.spanned(model, ...)` | PortStreams:178 | `model` only | `couple` ×2, `pools` ×1 | moved to `PortStreams` (science/fluid), not to the plant |

## Table 2: the filter cake law (merged)

R is `filter.cleanResistance()`; L the loading.

| Place | Old expression | Now |
|---|---|---|
| `NetworkReduction.resolutionFlow` (NR:238) | `R*(viscosity/.001)*(1+99*L*L)/density`, L = `filter.loading()` | `cakeResistance(filter,viscosity,L)/density` |
| `BalanceNet.prepare` (3400) | `R*(carrierViscosity(s)/.001)*(1+99*L*L)/rho[e][dir]`, L = `filter.loading()` | `cakeResistance(filter,carrierViscosity(s),L)/rho[e][dir]` |
| `Equations.edgeRows` (5494) | `R*(carrier/.001)*(1+99*L*L)*fluidVolume/transport.mass*law`, L = the unknown `x[filterOffsets[edge]]` | `cakeResistance(filter,carrier,L)*fluidVolume/transport.mass*law` |

The shared prefix is `(R*(mu/.001))*(1+((99*L)*L))` in each. The only model call, `carrierViscosity(s)`, is made once, as before. The record accessor `cleanResistance()` is now read after it, not before; it is pure.

## Table 3: the mixing-enthalpy bisection (loop merged, endings kept)

| Step | `seedAt` (3186) | `BalanceNet.mixed` (3789) | Same? |
|---|---|---|---|
| y | `amounts[c]/mass` | the same | same (kept at each place) |
| PH first | none | `equilibriumPH` where a hydrocarbon is held; return its folded answer | differs (kept in `mixed`) |
| window | `max(tLow-20, MIN)`, `min(tHigh+20, MAX)` | the same | same → `mixingBracket` |
| loop | `k<60 && upper-lower>1e-9`; `t=.5*(lower+upper)`; `flashTP(t,p,y.clone(),cp)`; `h/m < heat/mass` → lower, else upper | the same | same → `mixingBracket` |
| end | `flashTP(.5*(lower+upper))` into `states[j]` | `flashTP(lower)`, `flashTP(upper)`, the nearer side, `folded(…, true)` | differs (kept at each place) |
| refusal | `catch IllegalArgumentException` → false, around the loop and the end | none (throws to the caller) | differs (kept: the function throws through) |

## Table 4: the incidence table (merged)

| Place | Old | Now |
|---|---|---|
| `incidence(graph)` (3801) | degree count, sized arrays, reset, fill in edge order | unchanged, the one statement |
| `Runs.of` (129) | the degree count in the loop that marks actuated nodes; `interior` from `degree[i]==2`; then the same table | `incidence(graph)`, then the actuated loop; `interior` from `nodeEdges[i].length==2` |
| `startPoint` solids flows (1831) | the same table, inline | `incidence(graph)` |
| `Equations` constructor (4460) | the same table, inline, `count` = `graph.reservoirs().size()` | `incidence(graph)` |

## Table 5: the fixed-opening restriction (left)

| | `BalanceNet.runFlow` (3510) | `Equations` row, `VALVE_CONFLICT` (5530) |
|---|---|---|
| Expression | `restriction=.5/(rho*A*A)*(1/(u*u)-1)`, then `restriction*q*q` in f and `2*restriction*q` in the slope | `head-.5*flow*flow/(rho*A*A)*(1/(u*u)-1)` |
| Order | coefficient first, `((.5/(rho·A·A))·(1/u²−1))·q·q` | `(((.5·q)·q)/(rho·A·A))·(1/u²−1)` |
| Guard | NaN opening → restriction 0 (`flow` returns 0 for an opening of 0 before) | opening 0 → the row is `flow` |
| Verdict | **different operation order** (the flow squared multiplies before the division in the row, after the coefficient in the balance) and different guards: kept both | |

`FlowControl.opening` (PSS:341) is the inverse law (the opening from a flow and a head), not a copy.

## Table 6: the mode transitions (left)

| Device | Pass loop, `nextMode` (964 to 1016) | Balance, `BalanceNet.solve` (3590 to 3641) |
|---|---|---|
| Scope | every pump and valve, one converged point | pressure valves at a junction inlet that is not hard-closed, and movers on their law or suction limit; up to 4 rounds, each followed by a new solve |
| Pressure valve CLOSED → REGULATING | `up.pressure() > target+.01 && head > .01` | `pa > target+.01 && drive > .01`, with `drive = pa − pb − column·g·dz` on the balanced pressures |
| REGULATING → CLOSED | `flow < −1e-10` | `inflowExcept(a,e,p) <= 0` |
| REGULATING → OPEN | `flow > capLimit·(1+1e-8)`, or `head < −.01` | `inflow > flow(e,target,pb)·(1+1e-9)` |
| OPEN → REGULATING | `!fixed && up.pressure() < target−.01 && flow > 1e-10` | `pa < target−.01 && flow(e,pa,pb) > 1e-10` |
| OPEN → CLOSED | `flow < −1e-10` | none |
| Pump | vapour lock, reverse flow, reopen on rise beyond the demand by the shutoff band, `cavitationMargin < −band`, `lawResidual > band` | MOVER_LAW → PUMP_SUCTION_LIMIT where `pa < floor·(1−1e-9)` at `cavitationFloor` of the law's flow; on the limit, the anchor (`suctionAnchor`) and prescribed flow re-read; no reopen or close |
| Side effects | none (returns the mode) | writes `closed`, `anchor`, `p`, `mover`, `suctionLimited`, `prescribed`, calls `prepare` |
| Verdict | **different predicates, operands, bands and side effects**: the balance decides on its own balanced pressures and inflows (the review: on purpose). Kept both | |
