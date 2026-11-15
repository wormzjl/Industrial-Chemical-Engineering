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

## 5. Commands

- Step tests: `./gradlew --console=plain test --tests '*SparseNewtonTest' --tests '*UpLookingLuParityTest' --tests '*PassiveStepSolverTest' --tests '*DirectLiquidJacobianRowsTest' --tests '*PhaseLayoutTest'`
- Chain-100: `./gradlew --console=plain --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact`
- Bench (alone; `tools/codebase-review-bench/README.md`): `./gradlew fluidServerBenchmark -PfluidBenchmarkRunId=<fresh id> -PfluidStressProfile=true -PfluidBenchmarkMemory=true -PfluidBenchmarkProfile=mixed100`
- The final lane once, when the code is final.
