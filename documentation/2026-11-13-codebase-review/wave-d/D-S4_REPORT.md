# D-S4 The step solver in files, first slice: the balance start and the observations (S-01): report

Batch D-S4 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-a7661664222c6a038`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `c931cd856`. Three code commits: `32335758e` (step 1, `BalanceStart`), `0f677772a` (step 2a, `StepObservations`: the readings and the ledger), `e98356bf9` (step 2b, `observe` and `exhaustMoisture`), then this report with the code guide. **Nothing was compiled or run** (the brief: no Gradle, no JVM). Structure only: the same floating-point operations in the same order and the same model calls in the same order, so chain-100, the junction lines, the printed counters and the poll counts must not move.

**Lines.** `PassiveStepSolver` goes from 5,793 to 4,891 lines (−902). `BalanceStart` is 755 lines (720 moved, 35 header, holder and footer). `StepObservations` is 218 lines (186 moved, 32 header, holder, import and class document). Step 1 moved 720 lines, step 2a 122, step 2b 64.

**Form: a holder per group, one per solver, holding the solver and its model.** Each target is a package-private final class in `science/fluid/network` with two private final fields, `solver` (the `PassiveStepSolver`) and `model` (the solver's `FluidThermodynamics`, the same object). The solver builds one of each in its constructor (`PassiveStepSolver.java`:420), in the package-private final fields `balance` (:414) and `observations` (:416). Why this form:
- The moved code reads mutable solver state that a solve replaces (`constants`, which a solve and the mint replace; `rateOnly`), and it calls 19 solver methods. A final copy of those would be stale, so the holder reads them through `solver`.
- `model` is final in the solver. The holder keeps its own final reference, so the 37 `model` reads of the balance and the 10 of the observations keep their text.
- The solver's statics are reached by single static imports. So the moved lines keep their text, and the only change in a body is the `solver.` qualifier: 18 places in step 1 (11 methods, `constants` once), 9 in step 2a, 8 in step 2b (where step 2a's six `observations.` qualifiers also drop).
- No allocation per pass: two objects per solver. A `BalanceNet` is built once per balance round, as today (an inner class of `BalanceStart` now, so `new BalanceNet(...)` binds `BalanceStart.this` in place of the solver).
- No call order changes: the same calls through one more field load. Counters: none of the moved code increments a counter (`SolverDiagnostics` is not named in any moved line). `checkpoint.run()` calls stay where they were.

**Step 1, `BalanceStart`** (`32335758e`). Moved: `BALANCE_ROUNDS`, the record `Start`, `supplierSeeds`, `sweepNeighbours`, `seedAt`, `mixingBracket` (only `seedAt` and `BalanceNet.mixed` call it), `supplyFlow`, both `balanceStart` overloads, the inner class `BalanceNet` (with `TABLE`, `TABLE_CACHE`), `maximum` and `solveBanded` (the banded solve; only `BalanceNet.solveOnce` calls them). Old lines 3040-3045, 3050-3052, 3086-3228, 3252-3799 and 3844-3863.
- The solver calls `balance.balanceStart(...)` (:552, :764, :2960) and `balance.supplierSeeds(...)` (:2947). It names the result type `BalanceStart.Start` (:550, :2908).
- `BalanceTablesTest` builds its net as `new PassiveStepSolver(model).balance.new BalanceNet(...)` and types it `BalanceStart.BalanceNet`.

**Step 2a, `StepObservations`** (`0f677772a`). Moved: `VALVE_INCIPIENT`, `cavitation`, `regime`, `npshRatio`, `npshRequired`, `gasRatio`, the five-argument `dry` (the static `dry(Reservoir, State)` stays), and `checkConservation` (the ledger). Old lines 2209-2283 and 3365-3411. The solver calls `observations.checkConservation` (:825). `ValveCavitationTest` reads `StepObservations.regime` and `StepObservations.VALVE_INCIPIENT`.

**Step 2b, `observe` and `exhaustMoisture`** (`e98356bf9`). Old lines 2147-2210; the solver calls `observations.observe(...)` (:845). This commit holds **the one edit that is not a qualifier**:
- `observe` and `exhaustMoisture` read the fields of the private record `Equations.Transport` (`carried.mass`, `suction.density`). A record's fields are private whatever the record's access, so code in another top-level class cannot read them.
- `Transport` is now package-private (:4255), and the 13 reads (10 lines) call its implicit accessors: `carried.mass()`, `.specificEnthalpy()`, `.moles()`, `.density()`, `.viscosity()`, `.solids()`, `suction.density()` and the drawn stream's `.density()`. `Transport` declares no accessor of these names, so each returns its field: the same value, and for `moles` the same array, not a copy. No operation, model call or allocation changes.
- The commit stands alone: if the main thread holds the brief's "qualifiers only" strictly, revert `e98356bf9`. Step 2a then stands with `observe` in the solver, calling `observations.npshRatio` and the rest.

**What stayed, and why:**
- `balanceStarts` (the mint's entry, PSS:2945) stays. It replaces the solver's `constants` (D-S2's mint rule, unchanged), runs the solver's suction readings, `initialModes`, `closeDeadHeads` and `relieveSealedPockets`, and is called on the solver by `PassiveNetwork.mintJunctions` (PN:101, outside my lane). Only its two balance calls gained `balance.`.
- `needsBalance`, `carriedPoint` and `initialModes` stay. They read the carried point, `rateOnly` and the held openings, and `solve`, `startPoint` and `reopenable` call them. `balanceLawFlows` and the cache (`balanceFor`, `balanceCache`, `balanceClosedFor`) stay too: only `solve`, `startPoint` and `stocked` read them.
- `incidence`, `scaled`, `sameStates` and the other shared statics stay. The pass loop, `Equations` and `carriedPoint` read them too.
- The records `FlowSample` and `PipeObservation` (old 5707-5751) stay nested in the solver. `PassiveStepSolver.PipeObservation` is a public type name read by `runtime/fluid/FluidWorldAuthority` (another subagent's lane), `Ejectors`, `MachineHealth` and the tests. Moving them would rename a public type.
- **Step 3, `StartSeeds`, was not done.** The brief allows it only if steps 1 and 2 went through cleanly, and step 2 needed the accessor form (2b). Its coupling is in "The order proposed".

**Doc links.** Links in the moved text to solver members now name `PassiveStepSolver#`, and the solver's links to moved members name `BalanceStart#`, `BalanceStart.BalanceNet#` or `StepObservations#`. These are comments only. Stale `{@code PassiveStepSolver.balanceStart}` or `.checkConservation` mentions are left, because the files are outside my lane: `PassiveIntervalSolver`:206, :436, `ConservativeTransport`:85, :99, `FluidThermodynamics`:1200, `Noise`:15, `ExtremeTopologyIslandTest`:99, `JunctionSeedingGateTest`:17.

**`tools/`.** No harness under `tools/unified-thermo-port-forms/` or `tools/phase-ports-probes/` names a moved member (grep). The only hits are the solver copies under `tools/phase-ports-probes/wp1/src/instr/`, which hold their own `checkConservation`.

**Code guide.** In `fluid-science.md`:
- Two new class-map rows, `BalanceStart` and `StepObservations`.
- The `PassiveStepSolver` row: the balance and the observations point there. The stale `BalanceNet.sealed` and `waterFloor` names are corrected to `still`, `rest` and `suctionFloor`.
- The constants table (`BALANCE_ROUNDS`, `BalanceNet.TABLE`, the balance Newton) and the trap "about 4900 lines".
- The `cavitation` and `checkConservation` mentions and the symptom table.

`node tools/code-guide/map.mjs --readme` rewrote the README's `science/fluid/**` count from 73 to 75, and `--check` says "counts current". The batch README's history line is left to the main thread, as in D-S1 to D-S3. No `build.gradle` change: no gate group lists the solver or the new files.

## Checks run here (scripts in `tmp/d-s4/`, not tracked)

- `cut.mjs`: cuts exact line ranges, applies qualifier rewrites only at code positions (comments and strings are masked) and doc-link rewrites only in comments, applies the listed modifier edits, and writes CRLF byte for byte.
- `verify.mjs`: against `c931cd856`, every one of the 963 lines removed from the solver and the two tests reappears among the added lines once modifiers and qualifiers are normalised (`ACCESSORS=1` also normalises step 2b's accessor reads). The two exceptions are the constructor line and `BalanceTablesTest`:29, each edited as listed. Braces and parentheses balance in all five files. Every line is CRLF.
- `resolve.mjs`: every simple name in a new file that is also a solver member is either a member of the new file, a static import, or a local (checked by hand: the record component `lawFlows`, the parameters and pattern variables `mover`, the local `read`).
- `deps.mjs` and `map.mjs`: the member map and cross reference that chose the ranges.
- Every import of the two new files is used (counted).

## Tests for the main thread

Structure only: every reference bit for bit. The D-S3 list is the base. `ValveCavitationTest` and `FlowmeterObservationTest` are added (they read moved code), and so are the runtime readers of observation rows.

| Task | Classes | What it proves |
|---|---|---|
| `test` | `PassiveStepSolverTest`, `BlockJacobianEquivalenceTest`, `SteamTurbineConnectionTest`, `BalanceTablesTest`, `RestResolutionTest`, `FlowControlTest`, `JunctionStartFormTest`, `HeatSideNetworkTest`, `CrossflowHeatTest`, `PrecipitatedSolidsTest`, `SolidClosureFeasibilityTest`, `SolidConservationTest`, `ValveCavitationTest`, `FlowmeterObservationTest` (all `science.fluid.network`) | the step solver bit for bit; the balance tables through `solver.balance` (`BalanceTablesTest`); the mint and supplier seeds (`JunctionStartFormTest`); the ledger (`SolidConservationTest`); the valve regime and a valve's read (`ValveCavitationTest`); the flow meters `observe` builds (`FlowmeterObservationTest`); the turbine's exhaust moisture (`SteamTurbineConnectionTest`) |
| `test` | `RetainedSolverTest`, `LiquidJunctionTransientTest`, `MixedGasJunctionTransientTest`, `KeptLuStorageTest`, `MachineHealthTest`, `ColumnRunTest`, `StillRuntimeTest`, `ExchangerCouplingTest`, `FiredHeaterFuelTest`, `HeatSideTopologyTest`, `SteamHeaterCoilTest`, `RegulatingValveHoldTest`, `InlineFilterLineHoldTest`, `FullTankSolidsEventTest`, `SolidRuntimeTest`, `PipelineFittingTest`, `DeadHeadBesideFlowingBranchTest`, `DeadHeadedLineIslandTest`, `PumpCavitationTest`, `SteamEjectorRuntimeTest`, `ReliefValveWorldTest`, `IslandSetsPresentedTest` (all `runtime.fluid`) | the balance start and the observations in worlds: suction readings, wear (NPSH ratio, dry, gas ratio, condensed share, exhaust moisture), the pump on its suction limit (`PumpCavitationTest`), the observation rows the runtime reads |
| `test` | `PortAccountsTest` (`science.plant.port`), `LayeringTest` | the new files cross no package rule |
| `holdGates` | `ExtremeTopologyIslandTest`, `HoldClassWorldsTest`, `JunctionSeedingGateTest` | the extreme islands, the hold-class worlds and junction seeding (the balance start on every cold start) |
| `fluidSolverRegression` | `FluidSolverRegressionTest` (chain-100) | chain-100 exact, with its counters |
| lane | the classes `tools/merge-lane/affected.mjs` names for `PassiveStepSolver`, `BalanceStart`, `StepObservations`; the 33 junction lines and the printed lines; the fluid GameTests (they load the solver; `PumpHealthGameTests` reads the rows) | every reference unchanged |

The compile of `main`, `test`, `fluidGameTest` and the lane's harnesses is the first proof. If a reference moves, bisect by the three commits: each is meant to compile on its own, and 2a compiles without 2b.

## Points where compiling is not certain (each checked by reading, none compiled)

1. `PassiveStepSolver.java`:414, :416, :420: two blank finals are assigned only in the two-argument constructor; the one-argument constructor delegates with `this(...)`. `this` is handed to the holders, which only store it. `this.model` is read after its assignment on :419.
2. `BalanceStart.java`:261: the `static final Map TABLE_CACHE` and `static final int TABLE` live in the inner (non-static) class `BalanceNet`. This has been legal since Java 16, and the form is unchanged from the solver.
3. `BalanceTablesTest.java`:29: `new PassiveStepSolver(model).balance.new BalanceNet(...)`, a qualified inner-class creation on a field access. `balance` is package-private and the test is in the same package.
4. `BalanceStart.java`:3-13 and `StepObservations.java`:3-7: single static imports of solver members, each package-private or public now. `holdsNoFluid` imports both overloads. No member of the new files has the name of an imported static, so no import is shadowed.
5. `StepObservations.java`:8-10 import the nested types `PassiveStepSolver.Equations` (a package-private inner class), `PipeObservation` and `FlowSample`. `Equations.Transport` (:39) resolves through the imported `Equations`; `SparseNewton.Equations` is not imported by simple name.
6. `StepObservations.java`:50-51: `var carried` and `var suction` infer `Equations.Transport`, now package-private (`PassiveStepSolver.java`:4255). The fields `e.graph`, `e.cachedTransport` and `e.columnEnds` and the four-argument `e.drawn` are package-private. The private five-argument `drawn` is not applicable from another class, and it has a different arity anyway.
7. `StepObservations` calls `solver.vapourPressure(e, edge, state)`. Only the package-private `(Equations, int, State)` overload is accessible, and it is the one called before. `solver.moverPower(...)` is the solver's seven-argument method. The six-argument one belongs to `Equations`, which is not in scope there.
8. `PassiveStepSolver.java`:1290: `PH_OTHER` became package-private with `PH_LIQUID` and `PH_VAPOUR`, because they share one declaration.
9. Doc links to private solver members (`PassiveStepSolver#initialMassFlow`, `#releasePressure`, `#suctionFloors`, `#vapourLocks`, `#readSuctions`, `#suctionLimit`, `#breakdown`) and to `BalanceStart.BalanceNet#prepare` are javadoc only, and the build runs no doclint (no `-Xdoclint` or `-Werror` in `build.gradle`).
10. Step 2b's accessor reads (above): the same values by the record's implicit accessors, verified by reading `Transport` (no explicit accessor but `held()`).

## Members widened (private to package-private)

| Member | Read by |
|---|---|
| class `SolveConstants`, field `constants` | `BalanceStart.balanceStart` (`heldOpenings`), `StepObservations` (`liquidDensities`, `npshDesigns`, `vapourShares`, `condensedShares`) |
| `boreArea`, `incidence`, `sameStates` (static) | `BalanceNet.runFlow`; `BalanceNet` constructor and `sweep`; `BalanceNet.seeded` |
| `suctionSupply`, `suctionLimitPressure`, `lawFlow`, `riseLimit`, `lawRise`, `lawRiseSlope`, `breakdown`, `massFlowLimit`, `endViscosity`, `delivered`, `carrierViscosity` | `BalanceStart` (`supplyFlow`, `balanceStart`, `BalanceNet`) |
| `vapourLocked`, `liquidPH`, `read`, field `rateOnly` | `StepObservations` (`dry`; `cavitation`; `observe`) |
| `PH_LIQUID`, `PH_VAPOUR` (with `PH_OTHER`), `liquidMoles`, `liquidEnthalpy` (static) | `StepObservations.cavitation` |
| record `Equations.Transport` | `StepObservations.observe`, `exhaustMoisture` (step 2b) |
| moved and now package-private in their new class: `BalanceStart.balanceStart` (both), `supplierSeeds`; `StepObservations.cavitation`, `npshRatio`, `npshRequired`, `gasRatio`, `dry`, `observe` | the solver's calls |

Nothing became public. Not widened: `model`, `ownership`, `carry`, `balanceLawFlows` and every other solver member.

## Edits that are not a pure move

| Commit | Where (line at the tip) | Edit |
|---|---|---|
| 1 | `PassiveStepSolver.java`:413-414, :420 | the field `balance` with its doc line; `balance=new BalanceStart(this,this.model);` appended to the constructor's statement line |
| 1 | `BalanceStart.java`:1-34, :755 | package, imports, class document, the holder's two fields and constructor, closing brace |
| 1 | `BalanceTablesTest.java`:12, :27, :29 | doc link, type `BalanceStart.BalanceNet`, `.balance.new BalanceNet` |
| 2a | `PassiveStepSolver.java`:415-416, :420 | the field `observations`; `observations=new StepObservations(this,this.model);` appended |
| 2a | `StepObservations.java` header | package, imports, class document, holder |
| 2a | `ValveCavitationTest.java`:15, :17-20, :23 | `StepObservations.VALVE_INCIPIENT`, `StepObservations.regime` |
| 2b | `StepObservations.java`:32-95 | 13 record field reads as accessor calls (above); one import (`FlowSample`); class document |

## The order proposed for the remaining groups

1. **`StartSeeds`** (this slice's step 3; about 520 lines).
   - Members: `initialPhaseSeeds` (PSS:1546), `arrivingInVessel`, `freeLiquidSlot`, both `arrivingPhases`, `stocked`, `emptyBagSplit`, `startPoint` (:1800), `flashSeeds` (:2538), `restateFlashFlows`, the static `flashes(PassiveNetwork, ...)`, `FLASH_STEP(S)`, `settledFlash`, `flashAt`, `netInflow`.
   - Holder form: the same, `solver` and `model`.
   - It reads `carry`, `balanceLawFlows`, `constants`, `rateOnly` and the identity cache (`identitiesOf`) through `solver`.
   - It needs the private records `PassStart`, `Seeded` and `CarriedPoint` widened, plus `carriedPoint`, `initialMassFlow`, `transported`, `phaseCode`, `laidOutFor`, `VAPOUR_BITS` and `HOLDS_NOTHING`.
   - The shared statics `scaled`, `sameStates`, `sameFluidStates` and `molarMass` stay in the solver: `BalanceStart`, `carriedPoint` and `Equations` read them.
   - Callers: `solve` (:566, :783, :801), `closeIllegalStarts` (:2823) and `Equations.buildInitial` (:4209); each becomes a plain call through `solver.seeds`. It calls nothing in `BalanceStart`, so it has no cycle.
   - One care point: the name `flashes` is overloaded, an instance overload on `Equations` (:1316, the pass loop's, stays) and a static one (moves). Each call site must keep its overload.
2. **`MoverSuction`** (`cavitationMargin` :1027 to `wetShare` :2105, the suction floors and readings, the zone-edge and release caches). It writes the solve constants' arrays (`suctionFloors`, `readSuctions`, `vapourLocks`) and is read by `BalanceStart`, `StepObservations` and `Equations`. Once it has moved, those holders read it through `solver.suction`.
3. **`OneWayClosures`** (`Runs`, `refusedInlets`, `closeDeadHeads`, `closeIllegalStarts`, `pockets`, `relieveSealedPockets`, `sealedWithout`, the boundary rules `boundaryAllowed` and the dry-end tests). `NetworkReduction` calls `boundaryAllowed` and `holdsNoFluid` on the solver: keep those statics where they are, or change that file's qualifiers in the same commit.
4. **`DeviceModes`** (`nextMode`, `regulatesInSeries`, `regulating`, `closeBoundary`, `initialModes`, `shutoffBand`, `saturates`, `allowedRestart`).
5. **`PhaseActiveSet`** (`phaseCorrection`, the folds, `emptyings`, `keptEmptying`, `filled`, `anyDry`).
6. **`Equations`** (PSS:3281-4837, about 1,560 lines) in three parts, as the review draws them: `EquationLayout`, `PortDraws`, `Rows` with `BlockJacobian`. It reads about forty solver members by simple name, so it goes last, when the holders above exist. `StepObservations` then reads `Transport` as the new file's type.

The solver would then keep the pass loop, the planners and the records that are public API.
