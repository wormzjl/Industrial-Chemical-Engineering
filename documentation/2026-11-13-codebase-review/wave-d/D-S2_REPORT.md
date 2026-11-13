# D-S2 The step solver's solve constants, pass start and one examination (S-02, S-03, S-05): report

Batch D-S2 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-a3e96d2dac632aae3`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `7b36c23ff`. Two code commits: `45f6a8e3f` (S-02) and `afb0382c7` (S-03). S-05 has no code commit: its copies differ (table 4). **Nothing was compiled or run** (the brief: no Gradle, no JVM). Structure only: the same floating-point operations in the same order and the same model calls in the same order, so chain-100, the junction lines, the printed counters and the poll counts must not move.

`PassiveStepSolver` goes from 5,745 to 5,808 lines (+63). S-02 adds 54 lines (142 in, 88 out): the class, its factory and its docs. S-03 adds 9 lines (52 in, 43 out). No new file.

**S-02, `SolveConstants`.** The 19 fields of the brief's list become the final fields of one private static final class, `SolveConstants` (table 1). The solver holds it in the field `constants`, and every reader reads through that field. A fresh solver's value is `SolveConstants.none()`: the old initial values (empty arrays, `()->{}`, an empty `BitSet`, `series` and `supplyGraph` null, `supplyDuration` 0), each a new object for each solver, as the field initialisers were.

- **One build per solve**, at the solve's start (`solve`, lines 450 to 458):
  - The input states.
  - The held openings: allocated, set to NaN, then the dependent valves read from `acceptedOpenings`.
  - `SolveConstants.of(...)`. It takes the standing set, the series, the supply graph `inputGraph` and the slice `rateOnly?0:dt`. It allocates the per-connection arrays with the initial values the old code gave them: floors and zone edges at +inf, supply densities and condensed shares NaN, the rest empty.
  - `suctionFloors`, `readSuctions` and `vapourLocks` then write into these arrays, in the old order. Their loop bodies are unchanged: a local alias at each method's top keeps the old names.
- **What moved, and why the results stay the same:**
  - The held-opening loop and the `ControlConstraints.dependent`, `standing` and `series` calls moved up, from after `SolverDiagnostics.count(implicitSolves)`, `ownership.check`, `lastSolve=null` and the dt check. All four are pure functions of the graph and of `acceptedOpenings`. They call no model and count nothing, and nothing in between changes `acceptedOpenings`.
  - The supply graph, slice and NaN densities moved up from just before the carry loop. Nothing between read them: their only readers are `riseDensity`, from `shutoffMargin` in the carry loop, `nextMode` and the rows, and `stocked`, from the seeds. All of these come after the old set point.
  - Inside a solve, every read therefore sees the value it saw before.
- **What differs only after a thrown solve:** the constants a solve leaves behind when it throws before the old set points. This covers the ownership check, the dt check, a cancellation inside the suction readings, and the early return for an island without connections.
  - Before, the solver kept a mix: the new suction readings with the previous held openings, standing set, series and supply. Now it keeps the new set whole. A cancellation inside `vapourLocks` leaves a partly written lock array in the new constants, where before the old array stayed.
  - The next solve or mint replaces all of them before any read. Outside a solve, they are read only through the retained `lastSolve` (`acceptedEquations`, read by `BlockJacobianEquivalenceTest` after a converged solve). `lastSolve` is null on the dt-check and early-return paths. It is not null after an ownership-check throw, which is a confinement fault.
- **The two lazy arrays** (`zoneEdges`, `supplyDensities`) are still arrays, filled in place at their first read (`zoneEdge(int)`, `riseDensity`). The reads, the model calls and their order are the same. `zoneEdge` and `riseDensity` copy `constants` into a local first, and nothing replaces `constants` during either call.
- **The mint** (`balanceStarts`, lines 3242 to 3246): it replaced the suction fields, the vapour lock and the supply fields for its seeded graph, and it never restored them. It kept the previous input states, held openings, standing set and series. The new code does the same thing:
  - It replaces `constants` with `SolveConstants.of(held.inputStates, n, ()->{}, held.heldOpenings, held.standing, held.series, graph, 0)`, where `held` is the previous constants: the same objects, by reference. Then it writes the suction readings and the lock as before.
  - `constants` stays replaced. The mint runs on a fresh solver (`PassiveNetwork.mintJunctions`, PN:101), so the kept values are `none()`'s.
  - The supply fields used to be set after `vapourLocks`. They are now set before it, and `vapourLocks` reads none of them.
- **`replayStart`** reset only `inputStates` among these fields. It now runs `constants=constants.withInputStates(List.of())`, and every other value stays the same object.
- **Readers:** they read through `constants`, or through a local alias of it:
  - `zoneEdge(int)`, `vapourPressure(graph,edge)`, `vapourLocked(int)` and `riseDensity`;
  - `moverPower`, `read`, `mover` and `breakdown`;
  - the observations (`observe`, `npshRatio`, `npshRequired`, `gasRatio`, `dry`);
  - `nextMode` (standing), `regulatesInSeries` (series), `initialModes` (held openings) and `stocked` (supply graph);
  - the phase checks (`phaseCorrection`, `foldedIterate`) and `carriedPoint` (input states);
  - `balanceStart`'s `BalanceNet` (held openings) and `Equations.fixedOpenings`.

  In `solve` itself, the five `CarriedPoint` constructions, the accepted-mode seed (`heldOpenings[edge]`) and the series test read the locals the build made. These are the same objects.
- **Fields of the same kind, left as they are:**
  - `rateOnly`: set at the solve's start and read by about 20 branches, rows included. It is not in the brief's list, and finding X-03 owns it.
  - `blanketInflows` and `sliceRefusals`: the interval solver sets them for each step or slice. The solve does not set them.
  - `balanceLawFlows`: reset in the solve and set again in its pass loop (the regulated pass), so it is not constant within a solve.
  - `emptyings`: set for each pass.
  - `acceptedOpenings`: carried across solves.
- **Not of the kind:** none of the 19, with two remarks. `inputStates` is read by the phase checks and the carried point, not by a row. `suctionCheckpoint` is the solve's checkpoint, not a value. Both are set once for each solve, so they are in the class.

**S-03, `PassStart`.** Three of the four flags each have exactly one reader on every path (table 2), so they travel together in one private record:
- `PassStart(double[] allowedRestart, boolean lawFlowsFromBalance, boolean[] flashRestated)`, with `PassStart.NONE`.
- The pass loop keeps a local `passStart`. The first pass gets the restated junctions of the initial seeds. A pass sets `passStart` where it set a field and then continues, and it resets `passStart` to `NONE` right after each `Equations` constructor, where the fields were cleared (`allowedRestart`, `flashRestated`) or consumed (`lawFlowsFromBalance`, cleared in `startPoint`).
- The record travels as an argument: `Equations(..., passStart)`, then `buildInitial(passStart)`, then `startPoint(..., passStart)`, then `restateFlashFlows(..., flashRestated)`.
- The other three `startPoint` calls (`closeIllegalStarts`, `initialPhaseSeeds`, the short `arrivingPhases`) pass `NONE`. At those calls the old fields held false, or a `flashRestated` that their null `seeds` never read.
- `flashSeeds` set its flag deep in the seed chain. It and its callers now return the seeds together with that flag, in the record `Seeded(states, flashRestated)`. This changes four return types: `flashSeeds`, both `arrivingPhases` overloads and `initialPhaseSeeds`.
- `flashSeeds` builds its flags in a local that starts at null. The old field was always null when `flashSeeds` was entered: a set was always followed by a `continue`, then the next constructor, which cleared it. So the local starting at null is exact.
- **`keepOpen` stays a field.** `reopenNext` (`PassiveIntervalSolver`:288) sets it, and the next non-rate solve consumes it. The rate solves in between read it and leave it: `portRate` (`PassiveIntervalSolver`:258, 269) and `coldRateSeed` (:281). `replayStart` clears it. Two calls can therefore read it, and today's order decides which one consumes it (table 2).

**S-05: the copies stay.** The pass loop's converged checks (lines 667 to 818) and the polish (`polishRefusedPoint`, lines 895 to 933) differ in their checks, their order and their operands (table 4). No `examine` method was written. Only the closing block, from the reconstruction encode to the counter and the maximum residual, is the same in both places, and that block is not an acceptance check.

**`tools/`:** no harness under `tools/unified-thermo-port-forms/` or `tools/phase-ports-probes/` calls a changed member. Every changed member is private, or is the constructor of the package-private inner class `Equations`. The only mentions are in comments: `d11/src/instr/ConservativeTransport.java`:92 and `wp6/removed/FluidThermodynamics.5f38474.java`:121. No test or GameTest reads a moved field: they were private, and nothing reads them by reflection (I checked with grep).

**Code guide** (main thread, at merge prep; not in my lane): `documentation/reference/code-guide/fluid-science.md`:125 calls `lawFlowsFromBalance` "a per-solve flag" (it is now `PassStart`'s). Line 212 names the field `flashRestated` (now `PassStart.flashRestated`, carried in `Seeded`). Line 420 (`allowedRestart`) and lines 175 and 462 name methods that still exist. No `build.gradle` change: no gate group lists `PassiveStepSolver`.

**Points where compiling is not certain** (I checked each by reading; none was compiled):

1. `PassiveStepSolver.java`:297 to 333: `SolveConstants` is a private static nested class with package-private final fields. It is read from the outer class and from the inner classes (`Equations`:4347 `constants.heldOpenings.clone()`, a field initialiser; `BalanceNet` through `balanceStart`:3278). In `of` (a static method), the locals `zoneEdges`, `supplyDensities` and `condensedShares` have the same names as the instance fields; that is legal.
2. :453 to 458, :484 to 485, :698: new locals in `solve`: `inputStates`, `heldOpenings`, `conflicts` (moved, not duplicated) and `int[] series` (inside the loop body). No lambda in `solve` captures any of them, and no other local of these names exists in `solve`. `inputGraph` is a reassigned parameter, and the new code uses it outside any lambda.
3. :1096, :1965, :2025: the locals `c` in `zoneEdge(int)`, `riseDensity` and `breakdown` shadow nothing. In `riseDensity`, the pattern variable `pump` of `if(!(... instanceof FlowControl.Pump pump))return suction;` is still in scope after the `if`, as before.
4. :3091: the local `heldOpenings` in `initialModes` is read inside a switch expression, not a lambda.
5. :256 to 261: the records `PassStart` (with a `static final` field) and `Seeded`. No type of either name exists in `science.fluid.network` or `science.fluid.solver` (the wildcard import); I checked with grep.
6. :562, :590, :605, :748, :760, :780, :797: `var seeds=seeded.states()` infers `List<FluidThermodynamics.State>`, the type `initialPhaseSeeds` returned before, so every later `seeds=...` assignment still types. The `var flashed` at :797 and the `var flashed` at :631 are in disjoint blocks (the `if` and the `catch`).
7. :5122 to 5127: `buildInitial(PassStart)` declares a local `double[] allowedRestart` with the same name as the outer method `allowedRestart(PassiveNetwork,int)`. Java keeps methods and variables in separate namespaces, so this is legal.
8. Every call site of each changed signature was updated, and I counted them by grep: `startPoint` 4, `flashSeeds` 2, `arrivingPhases` 3, `initialPhaseSeeds` 1, `restateFlashFlows` 1, `Equations(...)` 1, `suctionFloors` 2, `readSuctions` 2, `vapourLocks` 2.

**Tests for the main thread** (structure only: every reference bit for bit):

| Task | Classes | What it proves |
|---|---|---|
| `test` | `PassiveStepSolverTest`, `BlockJacobianEquivalenceTest`, `SteamTurbineConnectionTest`, `BalanceTablesTest`, `RestResolutionTest` (the rest test's class; there is no `NetworkReductionTest`), `FlowControlTest`, `SuctionZoneEdgeTest`, `ReleasePressureTest`, `InletPhaseTest`, `PumpRiseScalingTest`, `PressureValveContinuationTest`, `JunctionStartFormTest`, `VentGatePolishTest` (all `science.fluid.network`) | the step solver bit for bit; the zone edges and supply densities filled at first read; the vapour lock, the series and standing valves, and the pumps' rise; the mint (`JunctionStartFormTest`); the polish path (`VentGatePolishTest`) |
| `test` | `RetainedSolverTest`, `LiquidJunctionTransientTest`, `MixedGasJunctionTransientTest`, `KeptLuStorageTest`, `MachineHealthTest`, `PumpCavitationTest`, `EmptyRiserTest`, `EmptyTankFillsTest`, `LiquidCo2LetDownTest`, `VesselPhaseSeedTest`, `SlurryTankPumpTest` (all `runtime.fluid`) | `replayStart` and the input states across jobs; the mint (`PassiveNetwork.mintJunctions`) in worlds; the suction readings in machine health and cavitation |
| `holdGates` | `ExtremeTopologyIslandTest`, `HoldClassWorldsTest`, `JunctionSeedingGateTest` (slow gates) | the extreme islands; the hold-class worlds (the mint, flash seeds, allowed restarts); junction seeding |
| `fluidSolverRegression` | `FluidSolverRegressionTest` (chain-100) | chain-100 exact, with its cost counters |
| lane | the classes that `tools/merge-lane/affected.mjs` names for `PassiveStepSolver`; the 33 junction lines and the printed lines; the fluid GameTests (they load the solver) | every reference unchanged |

## Table 1: the fields moved into `SolveConstants`

| Field | Set by the solve (old place, now `solve`:438 to 446) | The mint | `replayStart` | Read at (now) |
|---|---|---|---|---|
| `inputStates` | its input states (old 395) | kept (same object) | `List.of()` | the local in `solve` (the five `CarriedPoint`s); `phaseCorrection` 2661, `foldedIterate` 2802, `carriedPoint` 3079 to 3080 |
| `suctionFloors` | `suctionFloors(...)` (old 397) | replaced | kept | `vapourPressure` 1079 |
| `zoneEdges` | +inf, NaN for each liquid pump (inside `suctionFloors`) | replaced | kept | `zoneEdge(int)` 1095 to 1099, filled at first read |
| `suctionCheckpoint` | the solve's checkpoint (inside `suctionFloors`) | replaced (`()->{}`) | kept | `zoneEdge(int)` |
| `drawnStreams` | `readSuctions` (old 397) | replaced | kept | `zoneEdge(int)`; written in `readSuctions` |
| `vapourLocked` | `vapourLocks` (old 397) | replaced | kept | `vapourLocked(int)` 1134 |
| `heldOpenings` | NaN, then the dependent valves' accepted openings (old 403 to 405) | kept (same object) | kept | `solve` 484 to 485 (local), `initialModes` 3091 to 3095, `balanceStart` 3278, `Equations.fixedOpenings` 4347 |
| `standing` | `ControlConstraints.standing` (old 406) | kept | kept | `solve` 484, `nextMode` 997 |
| `series` | `ControlConstraints.series` (old 406) | kept | kept | `solve` 698 to 702, `regulatesInSeries` 951 to 955 |
| `supplyGraph`, `supplyDuration`, `supplyDensities` | input graph, `rateOnly?0:dt`, NaN (old 452, before the carry loop) | replaced (seeded graph, 0) | kept | `riseDensity` 1964 to 1968 (the densities filled at first read), `stocked` 1719 to 1721 |
| `readControls`, `suctionMovers`, `npshDesigns`, `liquidDensities`, `wetShares`, `vapourShares`, `condensedShares` | `readSuctions` (old 397) | replaced | kept | `read` 2010 to 2011, `mover` 2016, `breakdown` 2025 to 2027, `moverPower` 2005, `observe` 2194 to 2200, `npshRatio`, `npshRequired`, `gasRatio`, `dry` |

## Table 2: the one-shot flags

Line numbers are the current ones.

| Flag (old field) | Set | Read | Cleared | Readers between a set and its clear | Done |
|---|---|---|---|---|---|
| `allowedRestart` | the pass loop, at an allowed-direction restart (748), then `continue` | the next pass's `Equations` constructor → `buildInitial` (5126) | after that constructor (old 547), and at the loop's start (old 532) | one: `Equations` is built only at 605 | `PassStart.allowedRestart` |
| `lawFlowsFromBalance` | the pass loop, where a flow valve leaves its open pass (760), then `continue` | `startPoint` (1802), on every call; true only at the next pass's constructor | inside `startPoint`, and at the solve's start (old 400) | one: the other `startPoint` calls (`closeIllegalStarts` 2973, `initialPhaseSeeds` 1546, `arrivingPhases` 1639) all run before the loop, where the flag was false | `PassStart.lawFlowsFromBalance` |
| `flashRestated` | `flashSeeds` (2697), reached from the initial seeds (562), from the dry vessels' `arrivingPhases` (779) and from the phase correction (797); each is followed by `continue` or by the loop's first pass | `startPoint` (1829) with non-null `seeds`: only `buildInitial`'s call | after the constructor (old 548), and before the initial seeds (old 505) | one: the three other `startPoint` calls pass `seeds` null, so the flag had no effect there; `flashSeeds` sets it only where it returns a new list, so 779 always continues | `PassStart.flashRestated`, carried in `Seeded` |
| `keepOpen` | `reopenNext`, from the interval solver (`PassiveIntervalSolver`:288) | `solve` 537, consumed only where `!rateOnly` | there; also in `replayStart` | two or more: `portRate` (`PassiveIntervalSolver`:258, 269) and `coldRateSeed`'s rate solve (:281) read it and leave it; the next step `solve` (:284) consumes it | **left**: today's order decides which solve consumes it, and its set point is in another class |

## Table 3: the record values the pass loop hands on

| Where (now) | Old field writes | `passStart` |
|---|---|---|
| before the loop (590) | `allowedRestart=null`; `flashRestated` from the seeds; `lawFlowsFromBalance` false (since 400) | `new PassStart(null,false,seeded.flashRestated())` |
| after each constructor (605) | `allowedRestart=null; flashRestated=null`; `lawFlowsFromBalance=false` (in `startPoint`) | `PassStart.NONE` |
| allowed restart (748) | `allowedRestart=restart` (the others clear since 605) | `new PassStart(restart,false,null)` |
| regulated pass (760) | `lawFlowsFromBalance=true` | `new PassStart(null,true,null)` |
| dry vessel laid out (780) | `flashRestated` from `flashSeeds`, or none | `new PassStart(null,false,arrived.flashRestated())` |
| phase correction (797) | `flashRestated` from `flashSeeds`, or none | `new PassStart(null,false,flashed.flashRestated())` |

## Table 4: S-05, the pass loop's converged checks against the polish

| # | Pass loop (667 to 818) | Polish (895 to 933) | Same? |
|---|---|---|---|
| 1 | `reactivate(equations,states,promoted)` (675): writes `promoted`, counts `traceReactivations`; on true, solves the pass again | the same `layout.reactivate` question asked on copies of `promoted`, with no counter (904 to 909); on true, returns null | operands and side effects differ |
| 2 | `keptEmptying` (679) | absent | differs |
| 3 | per edge: flow and head; a closed edge's flow, unknown and **law flow** set to zero; a dry end's outflow set to zero | the same, without law flows | operand differs (law flows) |
| 4 | per edge: `equations.drawn(...)` **before** the direction and closed checks (689) | `drawn` **after** them (918) | order differs |
| 5 | forbidden direction: the first such edge is recorded and the loop goes on (691); after the loop, a device change goes first (A272), then `phaseCorrection`, then the allowed restart, then the closure | returns `Polished.closing(edge)` at the first such edge (916) | check and order differ |
| 6 | mode change: `nextMode`, then the series rule (`flowEntry`, `regulatesInSeries`, pressure-entry tracking); one change for each pass | `nextMode(...)!=mode` returns null (919) | differs |
| 7 | `segmentsMoved` (767) | `segmentsMoved` (921) | same |
| 8 | `draws` (770) | `draws` (922) | same |
| 9 | `anyDry` → `arrivingPhases` (777 to 781) | absent | differs |
| 10 | `reconstruct` (782) | `reconstruct` (923), the same arguments | same |
| 11 | `filled` (785) | absent | differs |
| 12 | `phaseAppeared` (790) | `phaseAppeared` (924) | same |
| 13 | `recordFolds` (795): writes `foldedPhases`, counts `traceFoldPasses` | `phaseFolded` (924), which writes nothing | operand and side effect differ |
| 14 | `phaseCorrection(..., foldedPhases)` (796), then `flashSeeds` | `phaseCorrection(..., foldedPhases.clone())` (925), with no `flashSeeds` | operand differs |
| 15 | the reconstruction encoded, `verificationResiduals`, the maximum residual (798 to 801), the gate | the same lines (926 to 929) | same, but not an acceptance check |

Rows 1 to 6, 9, 11, 13 and 14 differ, so the brief's condition for one `examine` does not hold, and no code changed. Row 15 is one block repeated: a private `gateResidual(equations, x, projection)` could serve both places with the same operations. It is not in this slice, and I did not write it.
