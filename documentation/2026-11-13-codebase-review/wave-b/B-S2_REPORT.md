# B-S2 The sparse LU (P-17): report

Branch `claude/review-wave-a6-fluid-18ecaf`, merged from the review branch at 7f4b13fed. Status: the gate and the named tests pass (2026-11-13); the `build.gradle` move waits for the main thread's reading of the gate. Bitwise identical: the LU is EJML's, expression for expression, and the gate found no differing bit. No facts, state, protocol or shape change.

## Steps
1. **EJML 0.44's sources read** (`tmp/ejml-sources/`, from the Gradle cache): `LuUpLooking_DSCC`, `TriangularSolver_DSCC` (`solveColB`, `searchNzRowsInX` and its DFS, `solveL`, `solveU`), `LinearSolverLu_DSCC`, `ApplyFillReductionPermutation_DSCC`, `CommonOps_DSCC.permuteInv`, `DMatrixSparseCSC.sortIndices`.
   - `FillReducing.NONE` means no column permutation.
   - The four orders are written in `UpLookingLu`'s class comment:
     - **reach and elimination:** the DFS from A(:,k)'s rows ascending, into L's columns in storage order; the elimination in topological reach order;
     - **pivot:** strict `>` from `-Double.MAX_VALUE`, the first of equals wins, NaN never wins, no threshold; rejected if there is none or it is `<= 0`;
     - **storage:** U(:,k) is its reach rows with pivots, then the diagonal last; L(:,k) is the pivot row (1) first, then the rows without pivots, divided by the pivot; L rows are renumbered by `pinv` at the end;
     - **dense solve:** `x[pinv[k]] = b[k]`, then L forward and U backward.
   - Nothing found that cannot be reproduced bit for bit.
2. **`UpLookingLu`** (9addc8e96): final, plain arrays, no EJML type in its API. The header keeps EJML's Apache-2.0 notice.
3. **Parity gate** `UpLookingLuParityTest` (62ec68687). Raw bits of L, U (columns, rows, values), `pinv` and four solutions against EJML.
   - Step matrices: heat side, boiling junction, cooling tower. The package-private `SparseLuSolver.observer` hands them to the test and is null outside it.
   - Random patterns: n from 1 to 130, three densities, strong or tiny diagonal (forced pivot swaps).
   - Three singular matrices, rejected by both at the same column: an empty column, a column on rows already pivoted, an exact zero pivot.
   - Every case runs twice in one instance, then once with its middle column's first entry scaled by 1e6.
4. **Kept reach** (2e927f07d). Each column's reach is kept and reused while the pattern (column pointers and rows) and every earlier pivot repeat; from the first changed pivot, the later columns search again.
   - Proof, in the comment: by induction, the reach depends only on A(:,k)'s pattern, L's first k column patterns and the earlier pivots.
   - The gate asserts no search on a repeat, and n - k - 1 searches after a first changed pivot at column k.
5. **`Storage` on `UpLookingLu`** (f173f6986), behind the present API. `factor0` keeps its arrays and allocates nothing once it has the island's size. The row sort is an insertion sort (rows are unique, so this is EJML's order). Scaling, zero drop, permutation, refinement and its `Math.fma` are unchanged.
   - **Pending, on the main thread's word:** `build.gradle` (226 to 231). EJML moves to `testImplementation`.
   - **What the jar then loses:** the three jarJar'd EJML jars (`ejml-core` 269 kB, `ejml-ddense` 411 kB, `ejml-dsparse` 96 kB, 776 kB together) and the runtime classpath entry. Nothing else in `src/main` uses EJML.

## Tests
`compileJava compileTestJava` once, then one run with `--max-workers 4`, each class once:
- `UpLookingLuParityTest` 4/4: 13 step matrices (heat side, boiling junction, tower, each matrix run twice and once with a changed pivot), more than 100 random patterns and the three singular ones. Every L, U, pivot and solution bit is EJML's, and the singular ones are rejected at the same column.
- `science.fluid.SparseLuSolverTest` 6/6, `PassiveStepSolverTest` 10/10, `runtime.fluid.RetainedSolverTest` 2/2, `BlockJacobianEquivalenceTest` 7/7. 0 failed, 0 skipped.
- After the `build.gradle` move: `compileJava compileTestJava` (no main class needs EJML) and the parity test once more.

## Counters (`printsTheLuCounters`)
- Five retained solves of a heat side's island (5 unknowns): 1 `luFactorizations` (the Newton keeps its Jacobian), 10 columns factored (the Newton's and the reconstruction's LU), **0 reach searches**. Before: one search per column factored, so 10.
- `Storage.factor` on that island's step matrix after warm-up: **25 bytes a factorization**, the `Factorization` handle. Before (B1): `factor0` at 1.8 GB a minute on mixed100, through EJML's per-factorization arrays. The bench's mixed100 re-run gives the step-level figure.
- `luFactorizations` unchanged in kind: the parity holds the Newton's path.

## For the main thread's lane
- I expect no value to move in chain-100, the junction lines or the campaign: same L, U, pivots and solutions, so the same iterates.
- The bench measures the step's CPU share of the triangular solves and the reach, before and after.

## Note of the main thread (2026-11-13)

Merged into the review branch (e31c38fd8) after the thread's own run above. EJML is still an `implementation` dependency packaged in the jar: its move to `testImplementation` is WAVE_B_BRIEFS amendment 11, with P-17b. The bench after wave B shows the kept reach does not yet reach the engine's step (amendment 10).
