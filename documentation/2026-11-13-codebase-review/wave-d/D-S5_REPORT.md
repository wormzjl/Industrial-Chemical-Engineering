# D-S5 The step solver in files, second slice: start seeds, movers' suction, one-way closures, device modes, phase active set (S-01): report

Batch D-S5 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-a00248091de51fe68`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `380015b56`. Five code commits, one per group in the brief's order: `9ffc75b3b` (`StartSeeds`), `0bbe93e2f` (`MoverSuction`), `95f1be7cd` (`OneWayClosures`), `a75b5c25d` (`DeviceModes`), `fdfef992c` (`PhaseActiveSet`), then this report with the code guide. **Nothing was compiled or run** (the brief: no Gradle, no JVM). Structure only: the same floating-point operations in the same order and the same model calls in the same order, so chain-100, the junction lines, the printed counters and the poll counts must not move.

**Lines.** `PassiveStepSolver` goes from 4,891 to 3,526 lines (−1,365). The five new files hold 1,377 moved lines:

| Step | Commit | New file (lines) | Moved | Header and footer | Solver after |
|---|---|---|---|---|---|
| 1 | `9ffc75b3b` | `StartSeeds` (450) | 415 | 35 | 4,478 |
| 2 | `0bbe93e2f` | `MoverSuction` (386) | 351 | 35 | 4,129 |
| 3 | `95f1be7cd` | `OneWayClosures` (248) | 215 | 33 | 3,918 |
| 4 | `a75b5c25d` | `DeviceModes` (195) | 169 | 26 | 3,751 |
| 5 | `fdfef992c` | `PhaseActiveSet` (254) | 227 | 27 | 3,526 |

The commit messages of steps 2, 4 and 5 give 353, 165 and 224 moved lines. The counts in this table are the right ones (from the cut ranges).

**Form: D-S4's form.** Each target is a package-private final class in `science/fluid/network`. It has two private final fields, `solver` and `model` (the solver's `FluidThermodynamics`, the same object), and it is built once in the solver's two-argument constructor (`PassiveStepSolver.java`:353). The holder fields are package-private finals: `seeding` (:341), `suctions` (:343), `closures` (:345), `devices` (:347), `phaseSet` (:349). None of these names is used as a local, a parameter or an `Equations` member anywhere in the file: `seeds` and `suction` are locals in `solve` and `Equations`, and `phases` is a record component, so those names were not used for the fields.
- Moved instance code reads the solver's mutable state and laws through `solver.`. Statics come in by single static imports, so the moved lines keep their text.
- The solver calls moved instance methods through the holder field, and moved statics by their class name (`OneWayClosures.closeBoundary`, `DeviceModes.shutoffBand`, `.regulating`, `.openStarved`, `PhaseActiveSet.rigid`, `.recordFolds`, `.phaseFolded`, `.phaseAppeared`, `.emptyingGraph`, `.stockSeeds`, `.keptEmptying`, `.anyDry`, `.filled`).
- No allocation per pass: five objects per solver. No call order changes: the same calls, through one more field load, or one more frame for the `reopenable` forwarder.
- Counters: no moved line names `SolverDiagnostics` or calls `checkpoint.run()` other than as the bodies did. No moved line uses `this`, `PassiveStepSolver.this` or `new Equations`. All checked by grep.

**Not a qualifier:** only step 4's two `Equations.Transport` reads by the accessor `density()` (the brief's listed exception, below). **Stop conditions:** none of the five groups is called from `Equations` in a cycle deeper than a plain call, and none writes solver state another group writes, so all five moved. The sections below give each group, what stayed (the `keepOpen` handling among it) and what the last slice, `Equations`, needs.

## The groups, step by step

**Step 1, `StartSeeds`** (`9ffc75b3b`). Moved: `initialPhaseSeeds`, `arrivingInVessel`, `freeLiquidSlot`, both `arrivingPhases`, `stocked`, `emptyBagSplit`, `startPoint`, `transported`, `VAPOUR_BITS`, `flashSeeds`, `restateFlashFlows`, the static `flashes(PassiveNetwork,…)`, `FLASH_STEP`, `FLASH_STEPS`, `settledFlash`, `flashAt`, `netInflow`, `laidOutFor`. Old lines 1532-1744, 1793-1855, 1860-1866, 2519-2639 and 2731-2741.
- Callers: `solve` calls `seeding.initialPhaseSeeds`, `seeding.arrivingPhases` (the nine-argument overload) and `seeding.flashSeeds`. `Equations.buildInitial` calls `seeding.startPoint`, and so does `closeIllegalStarts` (now `OneWayClosures`, as `solver.seeding.startPoint`).
- The instance `flashes(Equations,…)` (the rigid vessels) stayed for step 5. Each `flashes` call keeps its overload: the solve calls the five-argument one, `flashSeeds` the four-argument static.
- Qualifiers: 22 `solver.` (the fields `carry` ×3, `balanceLawFlows` ×4, `constants`, `rateOnly`; the calls `identitiesOf` ×2, `molarMass` ×2, `lawFlows`, `carriedPoint`, `delivered`, `endViscosity`, `lawFlow` ×2, `massFlowLimit`). It has no cycle: it calls nothing in `BalanceStart`. `Equations` calls `startPoint` plainly, and `startPoint` calls nothing in `Equations`.

**Step 2, `MoverSuction`** (`0bbe93e2f`). Moved: `cavitationMargin`, `suctionLimit`, `suctionSupply`, both `vapourPressure`, `suctionLimitPressure`, both `zoneEdge` with `ZONE_EDGE_CACHE` and `DrawKey`, `vapourLocks`, `vapourLocked`, `suctionHead`, `suctionFloors`, `drawnLiquid`, `release` with `RELEASE_CACHE`, `BUBBLE_WIDTH`, `releasePressure`, `releaseShare`, `riseDensity`, `read`, `mover`, `breakdown`, `readSuctions`. Old lines (after step 1) 1023-1289, 1666-1684 and 1722-1786.
- Qualifiers: 9 `solver.constants`.
- The solver's callers use `suctions.`: `solve` (the three readings, the lock, the limit), `nextMode`, `cavitates`, `initialModes`, `balanceStarts`, the mover laws (`mover`, `read`) and `Equations` (`riseDensity`, `suctionLimit(this,…)`).
- `BalanceStart` and `StepObservations` call `solver.suctions.x` (4 and 5 call sites), and their doc links name `MoverSuction#`.
- Tests, qualifier only: `SuctionZoneEdgeTest` (8 calls) and `ReleasePressureTest`:62 call `solver.suctions.zoneEdge` and `world.suctions.release`.
- Writes: only the solve constants' arrays and the two static caches, which nothing else writes. The `constants` field itself is still replaced by `solve` and `balanceStarts` only.
- No cycle: `Equations` calls `suctionLimit` and `riseDensity` plainly. They read `equations.graph`, `suctionSuppliers` and `suctionLinks` (package-private fields) and call no `Equations` method.

**Step 3, `OneWayClosures`** (`95f1be7cd`). Moved: the record `Runs`, `reopenable`, `allowedRestart`, `closeBoundary`, `closeDeadHeads`, `closeIllegalStarts`, `illegalWithEitherDensity`. Old lines (after step 2) 124-202, 857-878, 1983-2080 and 2247-2262.
- `reopenable` stays on the solver as a one-line forwarder (`PassiveStepSolver.java`:125), because `PassiveIntervalSolver`:286 calls it there.
- The `keepOpen` handling stays the solver's: the field, `reopenNext` (the interval solver's call) and its consumption in `solve`, which turns it into the `keep` array that `closeDeadHeads` and `closeIllegalStarts` receive, as before. `replayStart` still clears it.
- Qualifiers: 9 `solver.` (`acceptedPipes`, `acceptedNodeIds`, `seeding`, `identitiesOf` ×2, `staticDrive` ×2, `initialMassFlow` ×2).

**Step 4, `DeviceModes`** (`a75b5c25d`). Moved: `nextMode` (with its doc block, which stood before `regulatesInSeries`), `regulatesInSeries`, `regulating`, `cavitates` (with its doc block, which stood before the rigid `flashes`), `shutoffMargin`, `moverBounds`, `openStarved`, `demand`, `shutoffBand`, `initialModes`. Old lines (after step 3) 845-927, 941-948, 973-985, 1295-1318, 1727-1749 and 1967-1984.
- Qualifiers: 13 `solver.` (`constants` ×3, `suctions` ×7, `riseLimit` ×2, `lawResidual`).
- `MoverSuction` imports `shutoffBand` from `DeviceModes` (one import line, and four doc links).
- **The one edit that is not a qualifier** (the brief's listed exception): `nextMode`'s `suction.density` and `cavitates`' `equations.drawn(…).density` read the package-private record `Equations.Transport` from another top-level class, so they call its implicit accessor `density()`. `Transport` declares no accessor of that name (only `held()`), so the accessor returns the field: the same double.
- No cycle: `Equations` does not call these. They call into `Equations` (`capLimit`, `states`, `flow`, `drawn`, all package-private), as before.

**Step 5, `PhaseActiveSet`** (`fdfef992c`). Moved: the rigid vessels' `flashes(Equations,…)`, `phaseCorrection`, `rigid`, `foldedIterate` (with its doc block, which stood before `rigid`), `vesselSplit`, `foldedSeeds`, `recordFolds`, `phaseFolded`, `phaseAppeared`, `emptyingGraph`, `stockSeeds`, the method `emptyings`, `keptEmptying`, `anyDry`, `filled`. Old lines (after step 4) 860-883, 1607-1731, 1998-2048, 2051-2068 and 2079-2087.
- Qualifiers: 3 `solver.` (`constants` ×2, `molarMass`).
- The field `emptyings` stays in the solver. The solve writes it per pass, as `emptyings=…phaseSet.emptyings(…)`, and `Equations.nodeAccumulate` reads it.

## What stayed, and why

- `keepOpen`, `reopenNext`, and the consumption in `solve`: set on the solver by `PassiveIntervalSolver`, and consumed by the next non-rate solve (D-S2, table 2).
- `refusedInlets`, `sliceRefusals`, `refuseInlets`, `sliceRefused`: these are a mover's inlet refusals for the slice, not a one-way closure. The interval solver sets the field and reads `sliceRefused`.
- `reopenBand`: `BalanceStart`, `OneWayClosures`, and the tests `LevelHeadTest` and `PhasePortPriorityTest` read it as `PassiveStepSolver.reopenBand`.
- The boundary statics `boundaryAllowed`, `outOfDryEnd`, `dryEnd`, `againstTower` and `forwardForbidden`: they are read by `Equations`, every holder and `NetworkReduction` (outside my lane).
- The sealed-pocket statics `pockets`, `closedEdge`, `couples`, `anchors`, `root`, `flowValve`, `relieveSealedPockets` and both `sealedWithout`: `Equations`' layout reads `pockets` and `closedEdge`, and the pass loop reads `flowValve`. The six-argument `sealedWithout` has no caller in any source set (grep). They are a candidate for `EquationLayout`.
- The shared laws `lawFlow`, `lawFlows`, `initialMassFlow`, `staticDrive`, `donates`, `columnFromFirst`, `massFlowLimit`, `saturated`, and the shared statics `scaled`, `sameStates`, `sameFluidStates`, `sameSlots`, `molarMass`, `incidence`: the pass loop, `carriedPoint`, `BalanceStart` and `Equations` read them.
- `carriedPoint`, `needsBalance`, `balanceLawFlows`, the balance cache and `balanceStarts` (D-S4).
- `phaseCode`, `DRY` (the pass key), `dry`, `holdsNoFluid`, `holdsNoFluidVessel`, `HOLDS_NOTHING`: `Equations` and several holders read them.
- `liquidPH` and `PH_*`: a valve's cavitation, read only by `StepObservations.cavitation`, not a mover's suction. They are a candidate to join `StepObservations`.
- The movers' laws `riseLimit`, `lawRise`, `lawRiseSlope`, `lawResidual`, `moverPower` and `kappa` (with its 16-slot cache): `Equations`' rows, `BalanceStart`, `StepObservations` and `DeviceModes` read them.
- The public statics `wetShare` and `suctionKappa`: `ElectricStores`, `ShaftTrains`, `MachineHealthTest` and `ShaftPowerBookingTest` call them. The private `wetShare(Supply)` is now package-private, and `MoverSuction` imports it.
- `saturates`: the velocity cap, read by `Equations`.
- `polishRefusedPoint` and `Polished` (S-05).
- The records `Proposal`, `PassKey`, `WorkspaceKey`, `Result`, `LastSolve`, `PipeObservation` and `FlowSample`.

## What the last slice needs

`Equations` starts at `PassiveStepSolver.java`:1916 and runs about 1,560 lines.
- `Equations` reads these through the holders: `seeding.startPoint` (`buildInitial`), and `suctions.riseDensity` and `suctions.suctionLimit(this,…)` (`edgeRows`).
- It reads these solver members by simple name: `model`, `constants`, `rateOnly`, `emptyings`, `transport`, the movers' laws and `kappa`, `saturated`, `saturates`, `phaseCode`, `dry`, `holdsNoFluid`, `pockets`, `closedEdge`, `incidence`, `cakeResistance`, `sideCurve` and `sideHeat`, `poolHeat`, `boilUp`, `molarMass`, `endViscosity`, `carrierViscosity`, `viscosity`, and the constants.
- As a top-level class it would hold the solver in an explicit field, in place of today's implicit outer reference. That costs no new allocation: one reference per `Equations`, as `this$0` is now.
- The holders that import `PassiveStepSolver.Equations` (`StepObservations`, `MoverSuction`, `DeviceModes`, `PhaseActiveSet`) would import the new type.
- The reads from outside of `Equations`' fields (`graph`, `suctionSuppliers`, `suctionLinks`, `fixedOpenings`, `pressureScales`, `cachedTransport`, `cachedPrepared`, `edgeOffset`, `dry`, `columnEnds`) and of its methods (`capLimit(int,int,Transport)`, `flow`, `states`, `drawn(int,int,Transport,double)`) must stay package-private.
- `Transport` is already read by its accessors from outside: 13 reads in `StepObservations`, 2 in `DeviceModes`.

## Doc links, `tools/` and the code guide

**Doc links.** Links in the moved text to solver members now name `PassiveStepSolver#`. The solver's links to moved members name the new class, and so do the links in `BalanceStart`, `StepObservations`, `StartSeeds` and `MoverSuction` (done in the commit that moved the member). Comments only. Stale `{@code PassiveStepSolver.x}` mentions outside my lane are left:
- `FluidThermodynamics`:1100, :1194 (`arrivingPhases`, `emptyBagSplit`);
- `PassiveIntervalSolver`:203 (`PassiveStepSolver#reopenable`, still valid through the forwarder);
- the tests `DeadHeadedLineIslandTest`:23, `FilterBlockLineIslandTest`:155, `DrumFillColdStartTest`:23, `JunctionWaterTraceTest`:18 and :94;
- `tools/phase-ports-probes/extreme/src/ExtremeTopologyIslandTest.5f38474.java`:83-100.

**`tools/`, GameTests and other source sets.** No Java harness under `tools/unified-thermo-port-forms/` or `tools/phase-ports-probes/` (the solver copies under `wp1/src/instr/` excepted) and no file in `src/fluidGameTest` names a moved member in code. The hits are comments and `.patch`/`.py` files, which the lane does not compile. No `src/main` file outside the package called a moved member (grep over `science/fluid`, `runtime/fluid`, `RetainedSolver`). No `build.gradle` change: no gate group lists the solver or the new files.

**Code guide.** In `fluid-science.md`:
- five new class-map rows (`StartSeeds`, `MoverSuction`, `OneWayClosures`, `DeviceModes`, `PhaseActiveSet`);
- the `PassiveStepSolver` row: the holders, what stays, and `Runs` dropped from its nested list;
- the prose that names the moved members (phase check, arriving-phase seeds, dry vessels, flash seeds);
- the symptom table (pump modes and suction, pressure valve modes);
- the trap line, now "about 3500 lines", with the new files.

`node tools/code-guide/map.mjs --readme` rewrote the README's `science/fluid/**` count from 75 to 80, and `--check` says "counts current". The batch README's history line is left to the main thread, as in D-S1 to D-S4.

## Checks run here (scripts in `tmp/d-s5/`, not tracked)

- `javamap.mjs`, `deps.mjs`: the member map of the solver (comments and literals masked) and a name cross reference, which chose the ranges. I then read every candidate's callers by hand, because the cross reference is by name: locals and pattern variables named like members (`stocked`, `delivered`, `read`, `mover`, `dry`, `allowedRestart`) were checked one by one.
- `cut.mjs` with `step1.mjs` … `step5.mjs`: cuts the exact line ranges and applies the listed modifier and line edits by original line number (each must match once). It applies the qualifier rewrites only at code positions: not after `.`, `#` or `::`, a call only where `(` follows, a field only where it does not. It applies the doc-link rewrites only in comments, and edits other files only by literal replacements with an expected count. CRLF is kept byte for byte. Each step ran first as a dry run into `tmp/d-s5/`.
- `verify.mjs`: against `380015b56`, every line removed from the ten changed files reappears among the added lines once qualifiers, `#` links and `private` are normalised, except three lines: the constructor statement (each step appended a holder) and the two accessor reads of step 4. Braces, parentheses and brackets balance in every file. Every line is CRLF. Each step was also checked against its parent.
- `resolve.mjs`: every simple name in a new file that is also a solver member is a member of the new file, a static import, or a type, local or parameter (checked by hand: `delivered`, `lawFlow` in `StartSeeds`; `dry` in `PhaseActiveSet`). No remaining solver code names a moved member unqualified, except locals (`read`, `mover`, `allowedRestart`; checked by hand).
- `uses.mjs`: every import of every new file is used.
- `access.mjs`: every member reached across files (`solver.x`, `holder.x` chains, `Class.x`, `equations.x`, static imports) has a non-private declaration in its owner. Where an owner also has a private overload, the call's arity matches only the accessible one: `arrivingPhases` 9 against 6, `vapourPressure` 3 against 2, `zoneEdge` (Drawn, Runnable) against (int), `capLimit` 3 against 4, `drawn` 4 against 5.

## Tests for the main thread

Structure only: every reference bit for bit. The D-S4 list (41 classes) is the base. Added: the two edited tests, the tests of the moved groups' behaviour, and the runtime worlds D-S2 named for the suction readings and seeds. Every added class runs in `test` (none is in `slowGateClasses`).

| Task | Classes | What it proves |
|---|---|---|
| `test` | D-S4's 14 `science.fluid.network` classes: `PassiveStepSolverTest`, `BlockJacobianEquivalenceTest`, `SteamTurbineConnectionTest`, `BalanceTablesTest`, `RestResolutionTest`, `FlowControlTest`, `JunctionStartFormTest`, `HeatSideNetworkTest`, `CrossflowHeatTest`, `PrecipitatedSolidsTest`, `SolidClosureFeasibilityTest`, `SolidConservationTest`, `ValveCavitationTest`, `FlowmeterObservationTest` | the step solver bit for bit through every holder |
| `test` | added `science.fluid.network`: `SuctionZoneEdgeTest`, `ReleasePressureTest` (both edited: `solver.suctions`), `PumpRiseScalingTest`, `InletPhaseTest`, `MachineMoverTest`, `ShaftPowerBookingTest`, `PumpRateWarmStartTest`, `PressureValveContinuationTest`, `VentGatePolishTest`, `DrumFillColdStartTest`, `JunctionWaterTraceTest`, `EmptyNetworkTest`, `LevelHeadTest`, `PhasePortPriorityTest`, `VelocityClampTest` | `MoverSuction` (zone edges, floors, rise density, readings, the mover as it runs, wet share); `DeviceModes` (pressure valve carry, the polish's `nextMode`); `StartSeeds` (`stocked`, the entry traces, `transported`); `PhaseActiveSet` (dry and emptying vessels); `OneWayClosures` (`reopenBand`, the void and generator ends) |
| `test` | D-S4's 22 `runtime.fluid` classes: `RetainedSolverTest`, `LiquidJunctionTransientTest`, `MixedGasJunctionTransientTest`, `KeptLuStorageTest`, `MachineHealthTest`, `ColumnRunTest`, `StillRuntimeTest`, `ExchangerCouplingTest`, `FiredHeaterFuelTest`, `HeatSideTopologyTest`, `SteamHeaterCoilTest`, `RegulatingValveHoldTest`, `InlineFilterLineHoldTest`, `FullTankSolidsEventTest`, `SolidRuntimeTest`, `PipelineFittingTest`, `DeadHeadBesideFlowingBranchTest`, `DeadHeadedLineIslandTest`, `PumpCavitationTest`, `SteamEjectorRuntimeTest`, `ReliefValveWorldTest`, `IslandSetsPresentedTest` | the holders in worlds: dead heads and reopened runs (`OneWayClosures`), the suction limit and lock (`MoverSuction`, `DeviceModes`), the regulating valves' series rule |
| `test` | added `runtime.fluid`: `EmptyRiserTest`, `EmptyTankFillsTest`, `LiquidCo2LetDownTest`, `VesselPhaseSeedTest`, `SlurryTankPumpTest`, `FilterBlockLineIslandTest` | the seeds, the arriving phases and the dry vessels in worlds; a filter line's start flows (`startPoint`'s solids pass) |
| `test` | `PortAccountsTest` (`science.plant.port`), `LayeringTest` | the new files cross no package rule (they name only `science.fluid`, `science.thermo`, `science.machine` as the solver did) |
| `test` (dependency) | `compileFluidGameTestJava` | the GameTests compile (none names a moved member) |
| `holdGates` | `ExtremeTopologyIslandTest`, `HoldClassWorldsTest`, `JunctionSeedingGateTest` | the extreme islands, the hold-class worlds (closures, flash seeds, allowed restarts, mode cycles), junction seeding (the mint: `initialModes`, `closeDeadHeads`, the suction readings) |
| `fluidSolverRegression` | `FluidSolverRegressionTest` (chain-100) | chain-100 exact, with its counters |
| lane | the classes `tools/merge-lane/affected.mjs` names for `PassiveStepSolver`, `StartSeeds`, `MoverSuction`, `OneWayClosures`, `DeviceModes`, `PhaseActiveSet`, `BalanceStart`, `StepObservations`; the 33 junction lines and the printed lines; the fluid GameTests (they load the solver) | every reference unchanged |

The compile of `main`, `test`, `fluidGameTest` and the lane's harnesses is the first proof. Each commit is meant to compile on its own. If a reference moves, bisect by the five commits. Only step 4 holds an edit that is not a qualifier (the two `density()` reads).

## Points where compiling is not certain (each checked by reading, none compiled)

1. `PassiveStepSolver.java`:341-353: five more blank finals, assigned only in the two-argument constructor (the one-argument one delegates with `this(...)`). `this` is handed to the holders, which only store it.
2. `StartSeeds.java`:13-14, `OneWayClosures.java`:10-11, `MoverSuction.java`:9-10, `DeviceModes.java`:5, `PhaseActiveSet.java`:5: single-type imports of nested types of `PassiveStepSolver`:
   - `PassStart`, `Seeded`: package-private records now (`PassiveStepSolver.java`:179, :184);
   - `Result`: public;
   - `SolveConstants`: package-private since D-S4;
   - `Equations`: a package-private inner class, importable like D-S4's `StepObservations` import.
   No imported simple name clashes with a member of the importing file, and no file imports `science.fluid.solver.*`, so `SparseNewton.Equations` cannot shadow.
3. `StartSeeds.java`:272: `solver.carry.lawFlows()` and `.heads()`. The record `CarriedPoint` is package-private now (`PassiveStepSolver.java`:159); its accessors are public. `PassStart.NONE` is a package-private static field of a package-private record, and so is its canonical constructor, used by the solver only.
4. Static imports with overloads:
   - `holdsNoFluid` (`PhaseActiveSet`): both static, package-private;
   - `sameLines` (`OneWayClosures`): the `(PassiveNetwork,PassiveNetwork)` overload and the `(List,List)` one, widened at :314. `closeIllegalStarts` passes two lists;
   - `wetShare` (`MoverSuction`): the public four-argument one and `(InletPhase.Supply)`, widened at :1230. `readSuctions` passes one supply;
   - `dry` (`StartSeeds`): the static `dry(Reservoir,State)` only. `StepObservations`' instance `dry` is another class's.
5. `MoverSuction.java`:7: `import static …DeviceModes.shutoffBand;` (step 4). `DeviceModes` imports nothing from `MoverSuction`: no static-import cycle, and none would matter.
6. `DeviceModes.java` (`nextMode`'s parameter `Equations.Transport suction`, `cavitates`): `Transport` is a package-private record nested in the package-private `Equations`. `density()` is its implicit public accessor (the record at `PassiveStepSolver.java`:2890).
7. `OneWayClosures.java`: `Runs` is a private record nested in `OneWayClosures`. Its static `of` calls the statically imported `incidence`, legal in a static nested context. `closeBoundary` is static and package-private, and the solver calls it as `OneWayClosures.closeBoundary`.
8. `PassiveStepSolver.java`:125: the forwarder `reopenable(PassiveNetwork,Result)` names `Result` (nested, declared later in the file) and the field `closures` (declared at :345). Both are legal forward references in a method body.
9. `ZONE_EDGE_CACHE` and `RELEASE_CACHE` are now static fields of `MoverSuction`. They are initialised when `MoverSuction` is first initialised, which is at the first solver's construction, not at `PassiveStepSolver`'s class initialisation. Both are empty synchronized weak maps either way. No result reads the moment.
10. Doc links to private members (`PassiveStepSolver#reachableComponents` from `StartSeeds`:37, `MoverSuction#releasePressure` from `BalanceStart` and `StepObservations`) are javadoc only. The build runs no doclint (as D-S1 to D-S4).

## Members widened (private to package-private)

| Member (`PassiveStepSolver`) | Step | Read by |
|---|---|---|
| records `CarriedPoint`, `PassStart`, `Seeded`; field `carry` | 1 | `StartSeeds` (`startPoint`, the seeds' results), `OneWayClosures` (`PassStart.NONE`) |
| field `balanceLawFlows`; `identitiesOf`, `molarMass`, `lawFlows`, `carriedPoint`; static `phaseCode` | 1 | `StartSeeds`; `phaseCode` and `molarMass` also by `PhaseActiveSet`, `identitiesOf` by `OneWayClosures` |
| static `shutoffBand` (moved to `DeviceModes` in step 4, package-private there), static `wetShare(InletPhase.Supply)` | 2 | `MoverSuction` (`vapourLocks`, `readSuctions`) |
| fields `acceptedPipes`, `acceptedNodeIds`; static `sameLines(List,List)`, static `nodeIds`; `initialMassFlow`, `staticDrive` | 3 | `OneWayClosures.closeIllegalStarts`, `allowedRestart` |
| `lawResidual`; static `forwardForbidden` | 4 | `DeviceModes.nextMode` |
| moved and now package-private in their new class (they were private) | 1-5 | the solver's calls |

The last row covers:
- `StartSeeds.initialPhaseSeeds`, `arrivingPhases` (nine arguments), `startPoint`, `flashSeeds`;
- `MoverSuction.cavitationMargin`, `suctionLimit`, `vapourLocks`, `suctionFloors`, `riseDensity`, `mover`, `readSuctions`;
- `OneWayClosures.allowedRestart`, `closeBoundary`, `closeDeadHeads`, `closeIllegalStarts`;
- `DeviceModes.regulatesInSeries`, `regulating`, `nextMode`, `cavitates`, `shutoffMargin`, `moverBounds`, `openStarved`, `initialModes`;
- `PhaseActiveSet.flashes`, `phaseCorrection`, `rigid`, `foldedIterate`, `foldedSeeds`, `recordFolds`, `phaseFolded`, `phaseAppeared`, `emptyingGraph`, `stockSeeds`, `emptyings`, `keptEmptying`, `anyDry`, `filled`.

Nothing became public. Not widened: `model`, `ownership`, `keepOpen`, `sliceRefusals`, `emptyings` (the field), `DRY`, the sealed-pocket statics, and every other solver member.

## Edits that are not a pure move

| Commit | Where | Edit |
|---|---|---|
| each | `PassiveStepSolver.java`:340-353 | the holder field with its doc line; the holder's construction appended to the constructor's statement line |
| each | the new file's lines 1 to 26-35 and its last line | package, imports, class document, the holder's two fields and constructor, closing brace |
| 2 | `BalanceStart.java`, `StepObservations.java` | `solver.suctions.` on 4 and 5 calls; doc links to `MoverSuction#` |
| 2 | `SuctionZoneEdgeTest.java` (8 lines), `ReleasePressureTest.java`:62 | `.suctions.` |
| 3 | `PassiveStepSolver.java`:124-125 | the `reopenable` forwarder with its doc line |
| 3 | `StartSeeds.java`:250 | doc link `OneWayClosures#closeIllegalStarts` |
| 4 | `DeviceModes.java` (`nextMode`, `cavitates`) | `suction.density()`, `drawn(…).density()`: the record accessor (the listed exception) |
| 4 | `MoverSuction.java`:7 and four doc links | `shutoffBand` imported from `DeviceModes` |
