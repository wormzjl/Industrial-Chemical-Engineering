# D-S1 The step solver's pure stream functions and its carried point (S-07, S-04): report

Batch D-S1 of `WAVE_D_BRIEFS.md` (subagent, Opus high), branch `worktree-agent-a67766d06a38b588a` after a fast-forward to `claude/codebase-review-refactor-8a85ef` at `8e4644144`. Two code commits: `0ac2552e7` (S-07) and `47276ff26` (S-04). **Nothing compiled or run** (the brief: no Gradle, no JVM). Structure only: the same operations in the same order and the same model calls in the same order, so chain-100, the junction lines, the printed counters and the poll counts must not move.

**S-07, `science/fluid/network/PortStreams`** (251 lines with its docs; public final class, private constructor). 23 functions (26 bodies with the overloads) leave `PassiveStepSolver`, table 1. Each body read only its arguments, the solver's `final` field `model` and the constant `GRAVITY`. Now each takes the model as its first argument, and reads `PassiveStepSolver.GRAVITY`. No body reads another solver field, an `Equations` field, a cache, a workspace or a counter. I found this by reading each body, and by a list of every call the bodies make (all go to `model`, `PhaseDraw`, `PhaseLayout`, `FluidThermodynamics` statics, `VesselGeometry`, `CoolingTower.PRESSURE` or each other).

- **Twins merged:** `portHead`, `endDensity` and `columnDensity`. The instance method was the single statement `return x(model, ...)`, so the two forms ran the same operations in the same order. The static stays, and the instance line and its "for a caller outside a solve" doc line go. `phaseDensity` and `mixedViscosity` were already static only.
- **Model per call:** the solver passes its own `model`. The two coupled-partner sites (`couple`, `pools`) passed through `reading(partner).endMass(...)` / `.endSpecificEnthalpy(...)`. They now call `PortStreams.endMass(reading(partner).model, ...)`. `reading` is still evaluated first and once per call, so the partner solver is created as before.
- **Callers:** `NetworkReduction` (6 calls and 1 doc link), `InletPhase` (3 calls and 1 doc), `TankOutlets`, `FluidWorldAuthority`, and the tests `ProcessOutletTest`, `OpenTankTest`, `LevelHeadTest` and `HeatPresentationTest` call `PortStreams`. So do `PipeObservation.atRest`, and doc links in `PassiveNetwork` and `PhaseDraw`. In the solver, 101 lines change. Each one only gains the `PortStreams.` qualifier and, where the call went to an instance method, a `model,` argument. I checked this mechanically (below).
- **Visibility:** `portHead` stays public (the runtime calls it). `sprayPressure`, `fluidMoles`, `solidShare(State,int)`, `fluidMass`, `fluidSpecificEnthalpy` and `mixedLiquidStream` are private (only `PortStreams` calls them). The others are package-private, as the static twins were.
- **Left in the solver:** table 2, each with the field it reads.

**S-04, `PassiveStepSolver.CarriedPoint`:** a private record of the nine fields, held in the field `carry` (table 3, by reference or by value).

- **Full assignments:** the five places that set all nine (the active-set change, the moved segments, the arrived dry vessel, the polish's closure, the accepted point) now make one `new CarriedPoint(...)` from the same expressions.
- **Partial assignments:** the start from the balance reset five fields and now calls `carry.withoutFlows()`. That keeps the input and output states, the rate flag and the modes, all by reference, as before. The regulating flow valve's restart replaced the modes alone and now calls `carry.withModes(List.copyOf(modes))`.
- **`replayStart`:** it resets to `CarriedPoint.none()`, with fresh empty arrays and lists as the field initialisers had. With a committed interval it builds one record from the same accessor calls, `endpointFlows()` twice as before (each returns a clone). It then calls `rememberOpenings` on `carry.flows()` and `carry.heads()`. That call now comes after the input states' list is built, not before: both only read, and the order changes no value.
- **Readers:** `refusedInlets`, the carried-modes check and loop, `startPoint` and `carriedPoint` read the record's accessors. `inputStates` (the solve's own) stays a field.

**Lines:** `PassiveStepSolver` goes from 5,976 to 5,745 lines (−231). Of these, 234 lines move to `PortStreams` and 6 twin lines are dropped. S-04 nets +9 lines: the record, its three methods and its docs add 26 lines, and the long assignment lines become one statement each.

**Mechanical checks** (node scripts in `tmp/d-s1/`, not tracked):

- Every kept solver line equals its old line once `PortStreams.` and `model,` are removed: 101 changed lines, 0 mismatches.
- Every moved body equals its old body in the same way: 234 lines, 0 mismatches.
- Every `PortStreams` call (173 of them, in the solver and inside the class) matches a declared overload in its argument count. Each has a model as its first argument exactly where the overload declares one, and none has a model twice.
- No unqualified call to a moved name is left in the solver.

**`tools/`:** one harness names a moved method. `tools/phase-ports-probes/hold-classes/src-t16/T16Gauge.java` calls `PassiveStepSolver.portHead`/`endDensity`. It is not edited: it is in the old `com.wormzjl.createcheme` package, and it already calls a three-argument `endDensity` that no longer exists, so it does not compile against today's code with or without this batch. `tools/phase-ports-probes/wp1/src/instr/PassiveStepSolver.java`, `tools/wti-pumping-rate-hold/trace-src/` and `tools/fluid-world-inspector/validation/baseline-src/` hold their own copies of the solver and are not affected. No harness under `tools/unified-thermo-port-forms/` names a moved method.

**Code guide:** in `fluid-science.md`, a `PortStreams` row and the moved names (`NetworkReduction` row, the solver row with `CarriedPoint`, the port heads, the connection row, the dry vessels, the round vessel's head, the symptom table, the static-column note, the carried modes, the job start's flows). Also `process-control.md` (`portHead`) and the README count (`science/fluid/**` 72 to 73, `map.mjs --readme`). There is no CHANGELOG line. The batch README history line is left to the main thread. No `build.gradle` change: neither gate group copies `PassiveStepSolver` or anything that calls `PortStreams`.

**Points where compiling is not certain:**

- `CarriedPoint` is a private nested record with array components. Its accessors `flows()`, `heads()`, `modes()` and `rateOnly()` clash with nothing: the solver declares no such member, and the field `rateOnly` is read unqualified as before.
- The field is called `carry`. The locals `carried` (the carry loop) and `previous` (two blocks) do not shadow it.
- `model` is used unqualified at every rewritten call site in an instance context, including `Equations`, `BalanceNet` and the lambdas. I found no local or parameter named `model` there. The only parameters with that name belong to the static `suctionKappa`, `wetShare` and `atRest`, and those already passed it.
- `endPressure` has two four-argument overloads: `(model, Reservoir, PhasePort, State)` and `(model, PassiveNetwork, Pipe, int)`. Their parameter types differ, as the instance overloads' did.
- `PortStreams` imports `java.util.List`, `PhaseLayout` (`science.fluid.solver`) and `FluidThermodynamics`. `TankOutlets`, `FluidWorldAuthority` and `HeatPresentationTest` reach it through their wildcard import of the network package, or by its full name. No other `PortStreams` type exists.
- `{@link PassiveStepSolver.Equations#draws}` names a package-private inner class. That is a doc link only, and the build has no doclint.

**Tests for the main thread** (structure only: every reference bit for bit):

| Task | Classes | What it proves |
|---|---|---|
| `test` | `PassiveStepSolverTest`, `BlockJacobianEquivalenceTest`, `SteamTurbineConnectionTest`, `ProcessOutletTest`, `OpenTankTest`, `LevelHeadTest`, `EmptyNetworkTest`, `PhasePortTest`, `PhasePortPriorityTest`, `CoolingTowerTest`, `InletPhaseTest`, `RestResolutionTest`, `FlowControlTest`, `PrecipitatedSolidsTest` (all `science.fluid.network`) | the step solver, its end functions and the rest test, bit for bit; the four edited tests |
| `test` | `RetainedSolverTest`, `LiquidJunctionTransientTest`, `MixedGasJunctionTransientTest`, `KeptLuStorageTest`, `MachineHealthTest`, `HeatPresentationTest`, `RestIslandTest`, `RestResolutionWorldTest`, `LiquidFullTankOutletsTest`, `OpenTankRuntimeTest`, `CoolingTowerRuntimeTest` (all `runtime.fluid`) | the carried point across jobs (`replayStart`); the runtime's port pressures |
| `test` | `LayeringTest` | `PortStreams` crosses no package rule |
| `holdGates` | `ExtremeTopologyIslandTest` (a slow gate) | the extreme islands |
| lane | the classes `tools/merge-lane/affected.mjs` names for `PassiveStepSolver`, `PortStreams`, `NetworkReduction`, `InletPhase`, `TankOutlets` and `FluidWorldAuthority`; chain-100 exact (`FluidSolverRegressionTest`), the 33 junction lines and the printed lines; the fluid GameTests (they load the solver) | every reference unchanged |

## Table 1: functions moved

Old place: `PassiveStepSolver` (old line numbers at `8e4644144`). New place: `PortStreams.<same name>`.

| Old | Old form | New signature | Why pure |
|---|---|---|---|
| `endPressure(Reservoir, PhasePort, State)` 2221 | instance, package | `endPressure(model, node, port, state)` | `model.liquidDensity`, `spray`, `sprayPressure`, `portHead` |
| `endPressure(…, DoubleSupplier)` 2226 | instance, private | `endPressure(model, node, port, state, arriving)` | `spray`, `sprayPressure`, `portHead`, the supplier |
| `spray` 2230 | static, private | package-private (`againstTower` calls it) | arguments only |
| `sprayPressure` 2231 | static, private | private | arguments, `CoolingTower.PRESSURE`, `GRAVITY` |
| `columnEndPressure` 2233 | instance, private | `columnEndPressure(model, …)` | `endPressure` |
| `endPressure(PassiveNetwork, Pipe, int)` 2237 | instance, package | `endPressure(model, graph, pipe, node)` | the graph's states; `columnDensity` in the lazy supplier |
| `portHead` 2262 / 2264 | twin: instance → static | `public static portHead(model, …)` (the static) | `model.liquidMass`, `heavierLiquid`, `phaseMass`, geometry |
| `endDensity` 2286 / 2288 | twin | `endDensity(model, …)` (the static) | `PhaseDraw`, `phaseDensity` |
| `phaseDensity` 2296 | static | unchanged | `model.vaporDensity`, `phaseMass` |
| `columnDensity` 2311 / 2313 | twin | `columnDensity(model, …)` (the static) | `PhaseDraw.fill`/`fillMixed`, `phaseDensity` |
| `runColumnDensity` 2329 | instance, private | `runColumnDensity(model, graph, chain, s, port)` | the chain's volumes, `columnDensity` |
| `endVelocityLimit` 2335 | instance, private | `endVelocityLimit(model, s, port)` | `model.phaseVelocityLimit`, `velocityLimit` |
| `mixedViscosity` 2349 | static (scratch an argument) | unchanged | `model.phaseViscosity` with the given scratch |
| `solidShare(State, PhasePort, double)` 2356 | instance, private | `solidShare(model, s, port, volume)` | `model.phaseMass`, state reads |
| `suctionMassFlow` 2369 | instance, private | `suctionMassFlow(model, …)` | `endDensity`, `model.phaseMass` |
| `endMoles` 2379 | instance, private | `endMoles(model, …)` | `mixedLiquidStream`, `fluidMoles` |
| `endMass` 2385 | instance, private | `endMass(model, …)` | `mixedLiquidStream`, `fluidMass` |
| `endSpecificEnthalpy` 2390 | instance, private | `endSpecificEnthalpy(model, …)` | `mixedLiquidStream`, `fluidSpecificEnthalpy` |
| `drawnStream` 2403 | instance, private | `drawnStream(model, rates, end, start)` | `fluidMass`, `fluidMoles`, `fluidSpecificEnthalpy` |
| `fluidMoles` 2416 | static, private | private | state reads |
| `fluidMass` 2419 | instance, private | private `fluidMass(model, s, phase)` | `model.phaseMass`, `solidShare(s, phase)` |
| `fluidSpecificEnthalpy` 2426 | instance, private | private `fluidSpecificEnthalpy(model, …)` | `model.phaseSpecificEnthalpy`, `phaseMass` |
| `solidShare(State, int)` 2734 | static, private | private | state reads |
| `endStream` 2741 | instance, private | `endStream(model, …)` | `model.molecularWeight`, `adoptingState` |
| `mixedLiquidStream` 2757 | instance, private | private `mixedLiquidStream(model, …)` | `model.molecularWeight`, `adoptingState` |

## Table 2: functions considered and left

| Function | Stays because it reads |
|---|---|
| `endViscosity` | `viscosities` (the solver's `MixtureViscosity.Workspace`), and `viscosity(s)`, which reads `ambient` (a lazily set field) |
| `viscosity(s)`, `viscosity(s, prepared)`, `carrierViscosity` | `viscosities`, `ambient` |
| `delivered` | `sideCurve` → `sideCurves` (the per-end curve cache), `heatCurveSamples`, `inResidual` |
| `spanned` | reads only `model`, but it is the heat curve of a planned side (the planners, S-06), called as `reading(partner).spanned`: not this slice |
| `massFlowLimit` | reads only `model` (`endDensity × area × endVelocityLimit`), but it is outside the slice's line ranges (the review's 1982-2014 group): a candidate for the next slice |
| `kappa` | `kappaStates`, `kappaValues` (a 16-slot cache) |
| `liquidMoles`, `liquidEnthalpy` | static and pure, but outside the slice's ranges (the review's 4233-4276 group): a candidate for the next slice |
| `LEVEL_HEAD_HEIGHT` (a constant between the functions) | not a function; nine uses name it on `PassiveStepSolver`, so it stays |

## Table 3: the nine carried fields in `CarriedPoint`

| Old field | Component | Set at the five places from | By |
|---|---|---|---|
| `previousPipes` | `pipes` | `pipeIdentities` | reference |
| `previousNodeIds` | `nodeIds` | `nodeIds` (the solve's `long[]`) | reference |
| `previousInputStates` | `inputStates` | the field `inputStates` | reference |
| `previousOutputStates` | `outputStates` | `states`, or `projection.states()` at the polish and the accept | reference |
| `previousRateOnly` | `rateOnly` | `rateOnly` | value (a boolean) |
| `previousFlows` | `flows` | `flows.clone()` | value (a clone) |
| `previousLawFlows` | `lawFlows` | `lawFlows` | reference, as before (each pass makes its own array and writes it before it is carried; the polish replaces it with a new one) |
| `previousHeads` | `heads` | `heads.clone()` | value (a clone) |
| `previousModes` | `modes` | `List.copyOf(modes)` | value (an immutable copy) |
