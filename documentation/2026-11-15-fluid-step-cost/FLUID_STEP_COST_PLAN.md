# Fluid step cost: a Jacobian pattern that does not follow rounding, and kept workspaces

Batch 2026-11-15-fluid-step-cost, branch `claude/fluid-step-jacobian` from `main` e9edc974b (0.31.0). Owner, 2026-11-15: "do the analytic Jacobian and workspace items first" (before any decision on a Rust port of the fluid step). A performance task: timed runs run alone. Numbers are from [PROFILE_REVIEW.md](../2026-11-13-codebase-review/PROFILE_REVIEW.md) (`mixed100`: 100 islands, 1,981 reservoirs, 47 components, two workers) unless marked (est.).

## 1. Expected

Which row of the step depends on which unknown follows from the island's topology and its active set (supports, modes, closures). It does not change inside a pass and it never depends on rounding. A sparse LU of a fixed structure does its symbolic work (the reach of every column) once and after that only arithmetic.

## 2. What the code does

- **The thermodynamic half of the Jacobian is already analytic.** Since 2026-11-12 ([ANALYTIC_JACOBIAN_PLAN.md](../2026-11-11-aqueous-solvents/ANALYTIC_JACOBIAN_PLAN.md)) a perturbed node's phases come from one derivative evaluation per phase (`FluidThermodynamics.tangents`), not from the equation of state per column. The rows are still differenced, one local column at a time, by the residual's own `nodeAccumulate`, `nodeRows`, `edgeRows`, `nodeTargetRows` (`StepEquations.differentiate`, lines 1475 to 1560).
- **The pattern the LU sees follows rounding.** `SparseNewton.Pattern.numericMatrix` keeps an entry only where the difference is not exactly 0. A weak dependence gives 0 in one factorization and 1e-20 in the next, so the pattern changes and `UpLookingLu` searches its reach again: 91 % of the columns factored, 10.7 % of the step's CPU, and no kept reach removes it (P-17c).
- **The ordering is taken from a much wider pattern.** `Pattern.symbolicMatrix` orders on the declared pattern (`StepEquations.columnRows`: a node column touches its own block, every row of each incident connection and the whole block of each neighbour). The numeric matrix is far sparser, so the fill-reducing ordering is not computed for the matrix that is factored. How much fill that costs is not measured.
- **Shares of the step's CPU:** linear algebra 44.4 % (the reach search is 10.7 of it), thermo kernel 35.7 %, residual and Jacobian assembly 12.7 %, flash 1.4 %. Median step 29 ms; worker occupancy 0.21.
- **Allocation:** kernel workspaces 7.5 GB a minute (21 % of the bytes; `PengRobinsonKernel$Workspace.<init>`, built per `FluidThermodynamics.Prepared`), the tangents 5.9 GB (`HydrocarbonModel.tangent` lines 206 to 217, `Tangent.at`), `PhaseLayout.decode` 3.1 GB.

So the item the profile asks for is not analytic values (assembly is under 13 %, and every row form would be derived by hand). It is a pattern fixed by structure. The main thread's earlier wording ("the analytic Jacobian") named the means the review had proposed; the reading above corrects it.

## 3. Steps

### S0. Measure what decides S1 (probe and counters; one bench pair, alone)

Counters in `science/fluid/diagnostics/SolverDiagnostics` (counted only under `ENABLED`, like the others), filled in `SparseNewton.factor` and `SparseLuSolver`/`UpLookingLu`:

1. entries declared, entries kept, entries of L and U, per factorization (sums and counts);
2. time of a factorization split into reach search, numeric elimination, triangular solves (`luFactorNanos` exists; add the split);
3. fresh and chord iterations and factorizations per accepted step (the counters exist; print them per step).

A probe under `tools/fluid-step-cost/` (a test-tree class the bench's init script adds; not in `src/main`): for every declared entry, its class (row kind: vessel component, energy, wall, equilibrium, junction mixing, continuity, enthalpy, normalization, solids, connection law, actuator, filter, weir; column kind: amount, phase split, temperature, pressure, solid moment, law flow, head, loading; relation: own block, across a connection upstream, across a connection downstream, the connection's own, a remote suction) and, over the run, whether it was never, sometimes or always kept, with the largest ratio of a sometimes-kept entry to the largest entry of its row.

Done when the report gives: the kept share of the declared pattern; the classes that are never kept; the magnitude of the entries that flip; the fill of L and U against the kept entries; the split of the LU's time.

### S1. The pattern by structure (results move in the last digits)

Compare at most three forms on the 200-seed quick check and one bench pair each, then build one:

- **A, class mask.** `StepEquations.columnRows` declares only the classes S0 shows can be kept (a rule of row kind, column kind and relation, read from the pass tables: nothing from an iterate). `numericMatrix` keeps every declared entry, zeros included; the ordering is computed on the same pattern. One pattern per structure: the reach is found once per workspace.
- **B, noise floor.** The declared pattern as today; an entry is kept when it is larger than 1e-13 of the largest entry of its row. No class table; the pattern still follows the state (a zero flow), but not rounding.
- **C, as today** (the reference).

Expected (est.): the reach search goes (about 10 % of the step's CPU). The numeric time moves with the fill, in either direction: A adds entries that are zero at a zero flow and orders better; S0's fill numbers say which wins.

What moves: every result in its last digits (the order of the triangular solves' sums follows the pattern; today it follows rounding). Bound: the Newton tolerance. Chain-100 is recorded again once, old and new values stated; conservation stays exact; a campaign seed may change class and is reported by the lane.

Gates: a new test that no entry outside the mask is ever nonzero, over the step tests' fixtures and the 200 seeds (form A is wrong where one is); `SparseNewtonTest`, `UpLookingLuParityTest`, `PassiveStepSolverTest`, `DirectLiquidJacobianRowsTest`.

### S2. Kept workspaces (bit for bit)

One kernel workspace per worker in place of one per `Prepared`; the tangent's arrays (`attractionN`, `logPhiT`, `logPhiP`, the n by n `logPhiN`) and `PhaseLayout.decode`'s buffers filled into storage of the pass where the value provably does not leave the evaluation (the rule P-07 used). Expected: about a third fewer bytes a step. Time: unproven. The two earlier allocation cuts (a third of the step's bytes, then 6 %) gave no time; this one is measured before it is called a gain.

Gates: every result bit for bit (chain-100 exact with no re-record, `EngineDigestTest`, `PreparedRefillTest`, the step tests); `MIXED_GAS_COST`'s allocated bytes fall.

### S3. Measure again, then the owner's decision

One bench pair on the final code against the 0.31.0 baseline (`mixed100`, JFR). The report states the step time, the shares and the worker occupancy on a fixture large enough to saturate the workers (a `mixed400` profile, if `mixed100` still reads 0.21). Then: analytic rows, the Rust gate, or stop.

## 4. Ruled out

- **A pattern that remembers** (the union of what a workspace has seen): the result would depend on when the workspace was built, so a loaded world and one that ran on would differ.
- **Factoring the declared pattern as it is:** thousands of entries that are structurally zero become fill.
- **Analytic rows now:** under 13 % of the step, about 30 row forms and every law derived by hand, entries that move near the sixth digit. Decided again after S3.

## 4b. S0 measured (2026-11-15): the census

`tools/fluid-step-cost/` (`PatternCensusTest` on the hold-fuzz worlds, seeds 1 to 200, 4 threads; output `tmp/pattern-census/census-2.txt`). 46,242 Newton factorizations of the step; a declared entry is classed by row kind, column kind and relation (own block; the other node of a connection, by whether the column's node is upstream at the iterate's law flow; the connection's rows and columns). Counts only, no times.

| | Share of the declared entries |
|---|---|
| Declared (mean 9,568 a factorization) | 1.000 |
| Kept today (not exactly zero; mean 4,295) | 0.449 |
| Never kept in any world: an equilibrium, normalization or wall row against another node's columns | 0.311 |
| Never kept: any row of a node against the columns of a node downstream of it, or across a stopped connection | about 0.2 |
| A mask by class and direction (every class that is ever kept) | 0.507 (1.13 times today's) |
| A mask by class for both directions | 0.675 (1.50 times today's) |

Between consecutive factorizations of one pattern (5,562 transitions on 256 tracked patterns):

- the kept set changes in 92.7 % (the bench read 91 % of columns searched again: the same thing);
- a connection changes direction or stops in 26.3 %;
- the kept set changes with no direction change in 66.5 %, by 57 entries on average. Of those entries 57 % are a vessel's component balance against another component's amount in its own block, 16 % a junction's mixing row against a law flow, 9 % a mixing row against an upstream amount.

What it says:

1. **Rounding is not the cause.** Of the entries that are kept in one factorization and not in another, 5 % have a largest size under 1e-12 of their row; the rest are real derivatives that are exactly zero at some states (a draw that stands, a compound absent from an outflow). Form B (a noise floor) is ruled out. The review's sentence "zero or 1e-20 by rounding" was wrong.
2. **The declared pattern is a third fiction.** A node's equilibrium, normalization and wall rows never read a neighbour (`nodeTargetRows` writes only the inflow rows), yet `columnRows` declares the neighbour's whole block. The ordering is computed on that pattern. Whether a true pattern gives less fill is the first thing to measure.
3. **Upwinding makes half of the cross-node entries zero**, and which half follows the flow's direction: a state, not a structure. A mask that knows the direction is 13 % larger than today's kept set and changes when a flow turns (26 % of the transitions on these transient worlds; fewer on a plant that runs). A mask for both directions never changes and is 50 % larger.

So S1's forms are (each against today's, C): **F1** the declaration made true (the never-kept classes leave `columnRows`; zeros are dropped as today): the ordering's effect alone. **F2** F1 and every declared entry of the upstream direction kept (the pattern changes only when a flow turns or a nonzero appears outside the mask, which a gate counts). The both-directions mask is not built: half again the entries.

Expected (est.), to be measured: F2 removes the reach search on a running plant (up to 10 % of the step) and adds 13 % to the matrix's entries; F1 and F2 change the fill through the ordering, in a direction only the bench can give. The net may be small.

## 4c. S2 measured, and the profile by stack (2026-11-15)

One bench pair each (`mixed100`, 60 s warm-up and 60 s measured, alone, 899 island steps, two workers): the baseline on `509d15e9d` (0.31.0 and the S0 counters) and S2 on `dafb95278`. Reports: `tmp/pattern-census/bench-base/`, `bench-s2/`; read with `tmp/pattern-census/bench-read.mjs` and `tools/fluid-step-cost/JfrAncestors.java`.

| | Baseline | S2 | Change |
|---|---|---|---|
| Island step, mean (ms) | 34.83 | 33.52 | -3.8 % |
| Island step, median (ms) | 34.54 | 32.85 | -4.9 % |
| Island step, p95 (ms) | 56.14 | 56.09 | none |
| Worker CPU a step, mean (ms) | 32.92 | 31.77 | -3.5 % |
| Worker occupancy | 0.261 | 0.251 | |
| Bytes allocated under the fluid step, the measured minute (GB) | 35.57 | 28.35 | -20.3 % |
| of them kernel workspaces (GB) | 14.26 | 9.83 | |
| of them phase tangents (GB) | 1.89 | 0.09 | |
| Collections in the measured minute | 23 | 20 | |
| Heap after a collection (GB) | 1.77 to 2.13 | 1.93 to 2.30 | +0.17 (+8 %) |
| Every solver counter (solves, iterations, residuals, entries, factorizations) | | identical | |

The time is one pair, and the run-to-run spread is not measured: the 4 % is a gain to confirm in S3, not a result. The two earlier allocation cuts gave none. The kept bundles cost 1.7 MB of heap an island.

**The baseline's counters, the measured minute.** 19,371 Newton solves, 25,710 iterations (18,237 with a new Jacobian). 761,947 residual evaluations, 716,866 of them inside a Jacobian build. 18,237 Jacobian builds, of which 1,563 are the step's block sweep; the other 16,674 are closure solves, 43 residuals each. The step's factorizations: 1,593, 8.4 s in all (5.3 ms each), 120,565 entries of L and U each against 4,410 kept and 10,966 declared; 0.913 of the columns searched their reach again; ordering 18 ms.

**CPU by what is on the stack** (baseline, 2,778 samples with a fluid frame; the rows overlap):

| Share | Part |
|---|---|
| 32.5 % | sparse LU; the reach search is 10.3 % |
| 28.6 % | the reconstruction after a step (`ConservativeTransport.reconstruct`) |
| 19.7 % | of it the vessel closure (`InventoryEquilibrium.close`, `PhaseLayout.wallClosure`); its difference Jacobian is 17.4 % |
| 18.0 % | the step's Jacobian sweep (tangents 2.6 %, decodes on them 4.8 %, transport rows 4.1 %) |
| 17.2 % | the step's residual evaluations (their decodes 13.5 %) |
| 36.5 % | the equation-of-state kernel, under all of the above |

**The vessel closure is the largest single item not yet touched.** Every vessel is closed to 1e-11 after every step (decision D36). The closure is a Newton on one node, about one iteration, and its Jacobian is differenced column by column on the equation of state: 43 full decodes a build, 16,674 builds a minute. The step's own sweep stopped doing that on 2026-11-12 (one derivative evaluation a phase, every column a first-order phase).

**What still allocates kernel workspaces** (S2 run, 9.83 GB a minute, 34.7 % of the bytes), all inside the reconstruction: the restatement `ConservativeTransport.repartition` 5.50 GB and the same inside the closure 1.36 GB (`adoptingState(..., null)`), the closure's first decode 1.52 GB (`decode(variables, 0)`), the closure's own bundle 1.43 GB (one per `wallClosure`).

### S2b. An evaluation with no bundle takes the thread's kept workspace (bit for bit)

`FluidThermodynamics`: where an evaluation has no `Prepared` it calls `hydrocarbon.prepare(t)`, a new kernel workspace (lines 575, 600, 633 and 957 of the file at `dafb95278`; line 350 and the constructor's line 162 are read first and left where they are not on a step's path). Each takes the workspace from a per-thread slot instead (the pattern of `HydrocarbonModel.tangentScratch`): the slot is emptied while the evaluation runs, the workspace is prepared at the temperature as `Prepared.pengRobinson` prepares a kept one (`translated().prepare(t, kept)`, then `carryAll()`), and it goes back to the slot when the evaluation returns. An evaluation that throws does not give it back (a refusal leaves a workspace half prepared), and a nested evaluation finds the slot empty and builds its own.

- Rests on: P-18's rule, which `PreparedRefillTest` holds: a kept workspace prepared at a new temperature on the whole list gives a new one's bits and refusals. No `State` keeps a workspace.
- Ruled out: passing a bundle down through `reconstruct`, `repartition` and `close` (six signatures for the same effect); a workspace kept in a field of the model (a model is read by several workers).
- Not covered: the closure's own bundle (1.43 GB), which lives in the closure object.
- Expected: 8.4 GB a minute fewer (30 % of the step's bytes); time unproven.
- Gates: `PreparedRefillTest` with a new case (bundle-less states at two temperatures and two supports, interleaved, equal to the bit those of a model that never evaluated before; at most one workspace built on the thread); `PassiveStepSolverTest`, `PhaseLayoutTest`, `HydrocarbonTangentTest`, `EngineDigestTest`; chain-100 exact with no re-record.

### S4. The vessel closure's Jacobian from phase tangents (results move in the last digits)

`PhaseLayout.wallClosure` gets `differentiateEntries`, as `StepEquations.differentiate` has for a node:

1. `prepared = prepare(x)`, `base = decode(x, 0, prepared)` on the equation of state, `tangents = model.tangents(base, prepared, storage)` with a `Tangent[3]` kept in the closure object.
2. For each column: `step = differenceStep * differenceScale(column, x[column])`; the trial's temperature; the base bundle where the temperature is the base's, else a second kept bundle refilled by `model.prepare(temperature, probe)`; `state = decode(trial, 0, bundle, tangents)`; the rows by the closure's own `PhaseLayout.this.residual(state, target, energy, capacity, workFrom, rows, 0, trial, bundle, sweeping)`; `entries[entryOffsets[column] + r] = (rows[r] - residual[r]) / step`, the arithmetic of `SparseNewton.Pattern.derivative`.
3. Returns the number of columns. An `IllegalArgumentException` (a trial outside the numerical box) returns -1 and counts `jacobianBlockFallbacks`: the coloured sweep runs, with its bounded one-sided stencil.

- Rests on: the closure's rows read the decoded state alone, the split of the amounts is exact in a tangent decode, and a phase the column moves by more than `TANGENT_COMPOSITION_STEP` or one without a tangent is evaluated on the equation of state (`FluidThermodynamics.slotPhase`).
- Pitfalls: the wall's branch. `SparseNewton.differentiate0` calls `linearising(x)` before `differentiateEntries`, so `sweeping` is set: every trial row is stated on it (decision A423), as the difference sweep's are. The base residual passed in was evaluated outside the bracket, on the state's own branch, as today. The solids' rows read the unknowns, not the state. `columnRows` is dense, so `entryRows` is 0 to size-1 per column.
- Not done: `junctionClosure`. It is 0 % of this profile, and every point it evaluates is read by the vanishing rule (`Vanished`, decision D38 C), which a first-order phase would read differently.
- What moves: a closed vessel's state within the closure's 1e-11, so every later result in its last digits. An entry differs from the difference sweep's near its sixth digit (no curvature inside the step); the closure converges to the same tolerance, in the same iterations where it took one. Chain-100 is recorded again once, in the final lane, old and new values stated. Conservation stays exact: the closure's result is restated at the inventory's amounts (`repartition`).
- Expected (est.): the closure's Jacobian from 17.4 % of the step's CPU to about 5 % (the step's sweep pays 7.4 % for 1.3 million tangent decodes and 31,000 node tangents; the closures need 717,000 and 16,674); `residualEvaluationsInJacobian` from 716,866 to the step's own.
- Gates: a new `WallClosureJacobianTest` after `BlockJacobianEquivalenceTest` (the entries against the column-by-column differences of `residual` inside the bracket, relative 1e-5 of each row's largest entry, on a two-phase vessel, a liquid-full taut one, a slack bag, one with an aqueous slot, one with solids; a trial outside the box gives -1); `PhaseLayoutTest`, `SparseNewtonTest`, `PassiveStepSolverTest`, `SolidClosureFeasibilityTest`; the 200-seed quick check (no new hold class); chain-100 in its declared mode, deviations inside the bound.

### S2b measured (2026-11-15)

Built by a subagent (`04cabf5f6`), merged as `bb771a9eb`: the sites converted are the private `state`, `slotPhase`, `tangents` and `adopt`; `crystalAnswer` (not on a step's path) and the constructor are left. One bench pair on `bb771a9eb` (`tmp/pattern-census/bench-s2b/`), against the two above:

| | Baseline | S2 | S2b | S2b against the baseline |
|---|---|---|---|---|
| Island step, mean (ms) | 34.83 | 33.52 | 30.64 | -12.0 % |
| Island step, median (ms) | 34.54 | 32.85 | 30.03 | -13.1 % |
| Island step, p95 (ms) | 56.12 | 55.25 | 48.90 | -12.9 % |
| Worker CPU a step, mean (ms) | 32.92 | 31.77 | 29.11 | -11.6 % |
| Worker occupancy | 0.261 | 0.251 | 0.230 | |
| Bytes allocated under the fluid step, the measured minute (GB) | 35.57 | 28.35 | 20.06 | -43.6 % |
| of them kernel workspaces (GB) | 14.26 | 9.83 | 1.45 | |
| Collections in the measured minute (their time, ms) | 23 (180) | 20 (157) | 11 (118) | |
| Heap after a collection (GB) | 1.77 to 2.13 | 1.93 to 2.30 | 2.05 to 2.39 | |
| Every solver counter | | identical | identical | |

Three runs in one direction, one pair each; the spread between runs of one code is still not measured. The collector's own time is 0.2 s of the minute in every run, so the gain is not its pauses: a kernel workspace is about 50 KB, written whole when it is built, and 14 GB a minute of that went through the workers' caches. The 1.45 GB left is the closure's own bundle.

### S4 measured (2026-11-15)

Built by a subagent (`5289cd36f`), merged as `2b35cbdb4`: `PhaseLayout.wallClosure` has `differentiateEntries` as planned. Two things it does beyond the plan, both today's behaviour kept: a `ThermoHold` (an `IllegalArgumentException`) in a trial returns -1 as any refused trial does, since the coloured sweep catches it the same way; a trial row that is not finite returns -1, as `SparseNewton.evaluate` refuses it. One bench pair on `2b35cbdb4` (`tmp/pattern-census/bench-s4/`):

| | Baseline | S2b | S4 | S4 against the baseline |
|---|---|---|---|---|
| Island step, mean (ms) | 34.83 | 30.64 | 27.42 | -21.3 % |
| Island step, median (ms) | 34.54 | 30.03 | 26.72 | -22.6 % |
| Island step, p95 (ms) | 56.12 | 48.90 | 45.46 | -19.0 % |
| Worker CPU a step, mean (ms) | 32.92 | 29.11 | 25.90 | -21.3 % |
| Worker occupancy | 0.261 | 0.230 | 0.205 | |
| Bytes allocated under the fluid step, the measured minute (GB) | 35.57 | 20.06 | 20.71 | -41.8 % |
| Residual evaluations, of them inside a Jacobian | 761,947 / 716,866 | the same | 45,061 / 0 | |
| Jacobian builds, of them block builds | 18,237 / 1,563 | the same | 18,231 / 18,231 | |
| Newton solves, iterations | 19,371 / 25,710 | the same | 19,365 / 25,696 | |
| Held intervals | 0 | 0 | 0 | |

The results move as expected and no closure gains an iteration: 6 fewer solves and 14 fewer iterations of 25,710 over the minute. No block build fell back. What the gates say of the moved results: chain-100 builds no closure Jacobian (every vessel of it is within the closure's tolerance as it stands), so its reference stays exact, every deviation 0; the hold-fuzz seeds 1 to 200 end at the same tick, with the one held seed they had (145). The references the final lane reads may move in their last digits (the `VIRTUAL_HOLDUP_DIGEST` line among them); it records them once.

**CPU by what is on the stack after S4** (2,203 samples with a fluid frame, against the baseline's 2,778; the rows overlap):

| Share | Part | Baseline |
|---|---|---|
| 36.6 % | sparse LU: the reach search 10.5 %, the rest of the factorization 13.9 %, the triangular solves 3.6 %, the matrix and the kept factors around them 8.6 % | 32.7 % |
| 22.3 % | the step's Jacobian sweep (decodes on tangents 5.6 %, transport rows 4.0 %) | 18.0 % |
| 21.5 % | the step's residual evaluations (their decodes 16.9 %) | 17.2 % |
| 14.9 % | the reconstruction after a step; the vessel closure 11.0 %, its Jacobian 5.0 % | 28.6 %; 24.4 %; 17.4 % |
| 10.2 % | viscosity, under the rows above | 8.9 % |
| 23.7 % | the equation-of-state kernel, under the rows above | 34.0 % |

The linear algebra is now the largest part, and S1's forms act on it. The closure's Jacobian came out at 5.0 % where 5 % was estimated.

Order: S2b (done), S4 (done), then S1's F1 and F2 (the pattern forms act on the 32.5 % of the LU, of which the reach search is 10.3 %; the census says their net may be small).

## 5. Commands

- Step tests: `./gradlew --console=plain test --tests '*SparseNewtonTest' --tests '*UpLookingLuParityTest' --tests '*PassiveStepSolverTest' --tests '*DirectLiquidJacobianRowsTest' --tests '*PhaseLayoutTest'`
- Chain-100: `./gradlew --console=plain --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact`
- Bench (alone; `tools/codebase-review-bench/README.md`): `./gradlew fluidServerBenchmark -PfluidBenchmarkRunId=<fresh id> -PfluidStressProfile=true -PfluidBenchmarkMemory=true -PfluidBenchmarkProfile=mixed100`
- The final lane once, when the code is final.
