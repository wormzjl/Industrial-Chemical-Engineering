# F0-S1 The step's observations once per interval, its tables on the solver, an evaluation workspace: report

Batch F0-S1 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-a0684694aa0ae0da1`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `e056e149b`. **Nothing was compiled or run** (the brief: no Gradle, no JVM). Performance only: no arithmetic expression and no evaluation order changes; every result is meant to keep every bit.

| Step | Commit | What |
|---|---|---|
| P-09 | `e1ca0af5a` | The three static synchronized `WeakHashMap`s become per-solver fields: `BalanceStart.tableCache`, `MoverSuction.zoneEdgeCache`, `MoverSuction.releaseCache` |
| P-02 | none | **Stopped** (below) |
| P-07 (1/3) | `a325dd73b` | `PhaseLayout` row scratch: `equilibriumResidual`'s four arrays, a junction's totals and solid target |
| P-07 (2/3) | `25a352dc6` | `StepEquations` node scratch: connection solid moments, start moments once per pass, junction solid mixture, `held(Transport)` |
| P-07 (3/3) | `4b577d040` | A priority mixture's amounts in pooled arrays (edge pre-pass and caps) |
| last | this commit | This report; `fluid-science.md` rows (`MoverSuction`, `StepEquations`, `PhaseLayout`, the determinism line, two traps) |

## P-09: the tables on the solver

- **Values.** Each map's value depends only on its key state, the hull or drawn stream the entry records (checked on a hit as before), and the solver's model:
  - a table: PH equilibria at five pressures;
  - a floor: `releasePressure` on the state's liquids;
  - an edge: `releasePressure` on the drawn stream.

  So a hit returns what a new build gives. The table arrays are only read after their build (grep: no element is written into `tableP`, `tableRho` or `tableRhoV`), so whether they are shared does not matter.
- **What relies on the sharing.** Nothing relies on it for a result, only for cost. Before, the mint's throwaway solver (`PassiveNetwork.mintJunctions`, `new PassiveStepSolver(model).balanceStarts`) handed its tables to the island's first step, and `SolidEventIntegrator`'s `rates` solver shared floors with the interval's `implicit` solver. Now each solver builds its own entries.
- **Counters and polls.** Results and the Newton, Jacobian and LU counters are unchanged. On the first balance after a mint, and on a second solver over the same states, `flashCalls` and the checkpoint polls of those builds rise, never fall. The brief foresees this ("the mint's throwaway solver no longer shares tables with the first step"), but it is not "every poll count unchanged". Chain-100 (reservoirs and passive pipes, no junction, no pump) cannot see it. Junction lines that start from a mint can.
- **Locks.** The wrappers stay `Collections.synchronizedMap`. Each lock is now one solver's and uncontended. The solver is confined by its `SolverOwnership` latch, so the lock is only a guard.

## P-02: stopped

The brief's stop condition holds twice:

1. **The decode cache read by `observe` is not the last solve's.** `StepObservations.observe` reads `e.cachedTransport[donor]` and `[a]` (StepObservations:49-50, 55):
   - `PassiveStepSolver.java`:606 decodes `x` (`equations.states(x)`);
   - :738 evaluates `equations.residual(reconstructed)`, which decodes again every node whose reconstructed block differs, so at :778 the cache holds the reconstruction's transports for those nodes and `x`'s for the rest;
   - :781 releases the cache right after `observe`.

   The retained last solve (`LastSolve(equations, x)`) holds `x` only. Decoding it again gives different transports, so the same values cannot be read from the retained last solve.
2. **A later attempt of the same interval overwrites what `observe` reads before the interval ends.** `observe` and its callees read `solver.constants` (`vapourShares`, `liquidDensities`, `npshDesigns`, `condensedShares`, `vapourLocked`, the floors behind `suctions.vapourPressure` and `read`) and `solver.rateOnly`.
   - After an accepted step that is not the interval's end, the next attempt's `implicit.solve` (PassiveIntervalSolver:284) replaces `constants` (PSS:394) and `rateOnly` (:386).
   - That attempt's first pass also releases the accepted pass's decode cache (`adoptPrepared`, StepEquations:123-128).
   - The interval can then still end with a result whose observations are the earlier step's: a `Transition` caught at :320-329 sets `declared` (at the start, at the floor, or after 40 refinements) and the `Prefix` is built from `last` (:359).
   - So a read at the interval's end would read the later attempt's constants.

**For the main thread (a proposal, not built).** `TowerExchange.readings(original, states, flows, draws, model)` reads only its own arguments. Its deferral to the interval's end is bitwise safe on its own. `observe` would need a snapshot taken at acceptance:
- the accepted pass's transports (a copy of `N` references);
- its `SolveConstants` object (each solve builds a new one and writes only its own arrays);
- `rateOnly`, `states`, `flows`, `lawFlows`, `heads` and modes;
- `observe`, `MoverSuction.read`, `vapourPressure` and `vapourLocked` taking the constants as an argument.

That keeps one pass's transports alive until the interval ends (a RAM change against review 8.7 (c) E2). The observation's polls would also move from each accepted step to the interval's end. The step solver's public `solve` (used by `ValveCavitationTest`, `FlowmeterObservationTest`, `CoolingTowerTest`) would keep its eager observations.

## P-07: the escape table

Per residual evaluation (`StepEquations.residual` → `decode` → `assemble`), with the block sweep's sharing noted. "Pooled" means written before read within one call or evaluation and never leaving it: the rule of the scratch-field comment.

| # | Allocation (site) | Reaches | Decision |
|---|---|---|---|
| 1 | `f=new double[size]` (`residual`) | returned; `SparseNewton` holds current and candidate | left (rule) |
| 2 | `new double[3][n]` (`PhaseLayout.decode`) | adopted by the `State` (`adoptingState`), kept in `cachedStates` across evaluations, returned by `states(x)` into results | left |
| 3 | `State`, phases, `withSolidState` `State` and `Moments` (decode) | `cachedStates` | left |
| 4 | `transport()`: `totals()`, the bulk `Transport`; at a phase-port node `Transport[3]`, three phase records and a second bulk | `cachedTransport` across evaluations; read after it by `flows`, `nextMode`, `cavitates`, `observe`, `runsDry`, `linearisationHolds` | left |
| 5 | `equilibriumResidual`: `double[3]` sums, `double[3][]` fugacity views, `double[3]` logs, `int[3]` marks (PL) | the call | **pooled** (`slotTotals`, `slotFugacities`, `slotLogs`, `slotSeen`; cleared by `fill` to 0, null and -1) |
| 6 | `junctionRows`: `state.totals()` (PL) | the call | **pooled** (`totals(state)`: the same `v+l+a` sums, every element written) |
| 7 | `junctionRows`: `solidReference.clone()` (PL) | the call (`solidRows` reads three elements) | **pooled** (`junctionSolidTarget`, `arraycopy`) |
| 8 | `nodeAccumulate`: `inventory().solids().moments().values()`, a `Moments` and a `double[3]` per laid-out node | `arraycopy` source | **once per pass** (`startMoments(node)`, lazy, read only; the graph is fixed for the pass) |
| 9 | `nodeAccumulate` per connection: `transport.solids.values()` and the receiver's `carried.solids.values()` | the loop iteration | **pooled** (`momentsOf` into `carriedMoments`, `mixedMoments`) |
| 10 | `nodeSolidRows` (junction): `solidIncoming[node].clone()`; the start moments again | `solidRows` reads three elements | **pooled** (`solidMix`); the start moments as row 8 |
| 11 | `Transport.held()`: `boolean[3]` per phase-port draw (`drawn`, `capLimit`, `phaseRates`, `remainingAfterHc`, `drawnCarrierViscosity`) | `PhaseDraw.Segment.marginalAt`, which keeps nothing | **pooled** (`held(Transport)` into `heldScratch`); the record method removed |
| 12 | Pre-pass `drawn` priority mixture: the amounts `new double[C]` | `edgeDonor` and `edgeSuction`, read only while `edgesRead` | **pooled** (`donorMoles[edge]`, `suctionMoles[edge]`, zero-filled) |
| 13 | The same mixture's `Transport` and `Moments` records | `edgeDonor` | left: a record's fields are final; two small objects per mixture |
| 14 | Cap mixtures (`capLimit` at a mixed or weir end, `baseCap`'s filter room) | read for density, velocity limit and solids at once | amounts **pooled** (`capMoles`); the records left |
| 15 | `PhaseDraw.Segment.rates` `double[6]` (`phaseRates`, `remainingAfterHc`, `drawnCarrierViscosity`) | `drawRates` hands the same array to the booking (`draws`) | left: a pooled form needs a second entry in `PhaseDraw` |
| 16 | `cap` at a weir end: `Arrays.stream(pinned).noneMatch/anyMatch` with a lambda | the call | left (weir ends only; not a buffer) |
| 17 | Withdrawal (`totalAmounts`, `solidMoments().values()`), injection (`solidsPerSecond().moments().values()`), open-vessel releases (`each()`'s list, `Release.moles()`'s clone, `moments().values()`) | the call | left (modules and open vessels; `Release.moles()` clones by contract) |
| 18 | Tower node: `new double[E][]` draws, `drawRates`' arrays, `Arrays.asList(st)`, `TowerExchange.at`'s results; the flow array only in the block sweep | the call | left (towers only; `TowerExchange` allocates more than these) |
| 19 | D61 melt: `melting.split` result and `double[] removed` | the call | left (junctions above a crystal's melt only) |
| 20 | `PUMP_SUCTION_LIMIT` row: lambda `n->st[n]` | `suctionLimit` | left: it captures the evaluation's state array, which differs between residual and sweep |
| 21 | `PortStreams.columnEndPressure`'s `()->column`; `PhaseDraw.order` and `fill` at phase-port columns; `PipeResistance.pressureDrop`; the movers' laws | callees | left; not traced into (likely scalar-replaced after inlining, or outside the lane's rows) |
| 22 | Per Jacobian build: three pairs of cache clones, `x.clone()`, `perturbed` (`differentiate`) | the build | left (per build, not per evaluation) |

**Totals.** 22 allocation kinds found.
- 8 pooled: rows 5 (four arrays), 6, 7, 9 (two), 10, 11, 12 and 14's amounts. That is 13 arrays a node or connection can save.
- 1 kept once per pass: row 8.
- 13 left: 1-4 (they escape the evaluation or are the returned residual), 13 and 14's records (immutable records), 15-21 (rare paths, a callee's API, or a capture), 22 (per build).

**Expected saving per residual evaluation.** Sizes assume a 64-bit JVM with compressed oops: `double[3]` 40 B, `double[3][]` and `int[3]` 32 B, `boolean[3]` 24 B, `Moments` 40 B, `double[C]` 16+8C B.

Example island: 10 laid-out nodes (4 with two or more phases, 3 of them junctions), 10 connections, C = 12, no phase-port mixture.

| Rows | Saved |
|---|---|
| Equilibrium rows (row 5) | 4 × 144 B = 576 B (16 arrays) |
| Junction totals (row 6) | 3 × 112 B = 336 B (3) |
| Start moments (row 8) | 10 × 80 B = 800 B (20) |
| Connection moments (row 9) | 10 × 3 × 40 B = 1,200 B (30) |
| **Total** | **about 2.9 KB and 69 objects per evaluation** |

At a phase port, add 24 B per `held` call and 16+8C B per mixture. In the block sweep, rows 5, 8 and 9 recur for every column of a node and its neighbours, so a Jacobian build saves a multiple of this. What still dominates per evaluation is rows 2-4, the decode of every changed node, which the decode cache must keep.

## Checks run here (scripts in `tmp/f0s1/`, not tracked)

- `check.mjs`: braces, parentheses and brackets balance outside comments and literals, and every line is CRLF, in `BalanceStart`, `MoverSuction`, `StepEquations`, `PhaseLayout` (and `fluid-science.md`: 580 CRLF, 0 bare LF).
- `replace.mjs`: literal replacements with an expected count (CRLF byte for byte).
- `sites.mjs`: every read and write site of every pooled buffer, by method. For each one, the first access in the call or evaluation is a `fill` or `arraycopy`, or a write of every element later read:
  - `slotTotal`: `fill` (524) and per active slot (529); a supported slot that is not active reads 0.0, as a new array gave;
  - `f`: `fill(null)` (524), so the reads at 538 meet the same values or the same null as before;
  - `amountLog`: `fill` and guarded by `seen`, filled with -1 (532, 537);
  - `junctionTotals`: written whole (499) before 484 and 490;
  - `junctionSolidTarget`: `arraycopy` (491);
  - `carriedMoments` and `mixedMoments`: `momentsOf` (1225, 1238) before 1226, 1246, 1248;
  - `solidMix`: `arraycopy` (1603) before 1604;
  - `heldScratch`: three writes (1044) per call;
  - the mixture slots: `pooled` zero-fills (743) before the `+=` sums (731).
- No buffer is an input still being read. A pooled mixture's sources are node and start transports, never a mixture. `solidIncoming` and `solidReference` are copied, not aliased.
- Callers of changed signatures: only private overloads of `drawn` and `Transport.held()` changed. The latter is read nowhere else (grep over `src/main`, `src/test`, `src/fluidGameTest`, `src/mcpCompat`, `tools/unified-thermo-port-forms`, `tools/phase-ports-probes`). The renamed caches are named by no test or harness.

## Points where compiling is not certain (checked by reading)

1. `BalanceStart.java`:40 and 308-326: `tableCache` is an instance field of the holder, read from the inner (non-static) class `BalanceNet` as `BalanceStart.this.tableCache`. `BalanceTablesTest`'s `solver.balance.new BalanceNet(...)` still binds the holder.
2. `MoverSuction.java`:206, 237: the instance fields are initialised before the constructor body, and `zoneEdge` and `release` are instance methods.
3. `StepEquations.java`:1043-1046: `held(Transport)` beside locals named `held` (methods and variables are separate namespaces). The record `Transport` keeps an empty body (`{ }`).
4. `StepEquations.java`:1136: `private final double[][] donorMoles,suctionMoles,capMoles=new double[1][];`. These are two blank finals, assigned at :327, and one initialised field.
5. `StepEquations.java`:718, 723, 781, 792, 1115-1116: `drawn` overloads of 4, 5 and 7 arguments. The literal `1` and `0` widen as before.
6. `StepEquations.java`:253, 318, 1279: the field `startMoments` and the method `startMoments(int)` share a name, which is legal. `{@link #startMoments}` is javadoc only (no doclint).
7. `PhaseLayout.java`:496: the private `totals(State)` beside constructor locals named `totals`.

## Tests for the main thread

Bit for bit, so every reference must stand.

| Task | Classes | What it proves |
|---|---|---|
| `test` | the D-S5 list (62 classes in `test` and `holdGates`, D-S6's table) | the rows, the block Jacobian and the holders, bit for bit |
| `test` | `BalanceTablesTest`, `JunctionStartFormTest`, `SuctionZoneEdgeTest`, `ReleasePressureTest`, `PumpCavitationTest` | P-09: the tables, floors and zone edges per solver; the mint's start |
| `test` | `BlockJacobianEquivalenceTest`, `PassiveStepSolverTest`, `PhaseLayoutTest`, `TraceTruncationLayoutTest`, `DirectLiquidJacobianRowsTest`, `BackwardEulerStepTest`, `PhasePortPriorityTest`, `PhasePortTest`, `PrecipitatedSolidsTest`, `SolidTransportAcceptanceTest`, `CrystalDepositionIslandTest`, `HeatSideNetworkTest` | P-07: the equilibrium and junction rows (PL); the solid moments, junction solids, phase-port draws and mixtures (SE); the sweep against the coloured one |
| `test` | `FlowmeterObservationTest`, `ValveCavitationTest` | the observations are untouched (P-02 not built) |
| `fluidSolverRegression` | `FluidSolverRegressionTest` | chain-100 exact with its counters; P-09 cannot move them (no junction, no pump) |
| lane | `affected.mjs` for `PhaseLayout`, `StepEquations`, `BalanceStart`, `MoverSuction`; the 33 junction lines with `MIXED_GAS_COST`'s allocated MB, before and after | every reference unchanged; allocation falls; `flashCalls` may rise on minted islands (P-09) |

**If a reference moves**, suspect first:
- `4b577d040` (the pooled mixture amounts): it is the only change whose correctness rests on a buffer's lifetime across calls, through `edgeDonor` and `edgeSuction` until the next pre-pass.
- Then `25a352dc6` (the lazy `startMoments`).
- `e1ca0af5a` can move only counters, never a value, unless a model answer depended on its call history.
