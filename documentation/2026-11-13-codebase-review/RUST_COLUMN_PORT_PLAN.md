# Rust port of the column solver: build plan (package S5)

Owner decision (2026-11-13): the column solver is ported to Rust, extending what the C++ lane started; the Java path stays as the fallback; results need not be bit-identical with the Java path, but the difference must be small and justified. This plan is written from the code at base `259d22a2c`. Findings it refers to (D1, P1, S2 and so on) are in [COLUMN_REVIEW.md](COLUMN_REVIEW.md). "Calculator" is `V3ColumnCalculator`, "solver" is `V3SimultaneousColumnSolver`, "evaluator" is `V3MeshResidualEvaluator`. Nothing in this plan was built or run; every line count of Rust is an estimate from the Java line count.

Three choices shape everything below:

1. **The Rust code is a transliteration, function by function, in the Java order of operations, on a math layer that returns the Java bits.** Then a difference between the paths is a port defect or a named, deliberate deviation, never noise. Function-level gates are exact; only the whole-solve gate uses a tolerance.
2. **No native state between calls and no callbacks.** Every call receives flat arrays and returns flat arrays. Nothing is opened, cached or closed on the native side.
3. **The Java audit is the publication gate on both paths.** A state the Rust solve returns is audited by `V3AcceptanceAuditor` in Java before `V3ColumnResult` is built. A Rust defect can cost a fallback solve; it cannot publish a column the Java audit refuses.

## 1. What exists today (read at the cited lines)

- The C++ library exports a dense finite-difference Newton with a residual callback (`icheme_column_solver.cpp` 163 to 229), a PR batch (231 to 256) and a PR batch with one-sided temperature slopes (258 to 315). Only the last has a production caller: `V3MeshResidualEvaluator.captureNativeThermoSnapshot` (82 to 139), reached from `solveWithInsideOut` (solver 223 to 290), which the calculator selects only for the first grid of a column with no draw, steam or stage heat and at least 4 trays (calculator 1319 to 1323), when the seed residual is above 0.05 (solver 26, 247), for at most two passes (252). On a 4-tray grid that is 6 nodes and 24 phase evaluations per pass.
- The pass is PR78 on a constant interaction matrix only; any other kernel takes the Java snapshot (`V3PengRobinsonSession` 104 to 123). It calls `std::cbrt`, `std::acos`, `std::cos` (cpp 77, 85 to 88) and `std::log` (147, 150), and allocates eight `std::vector` per phase call (121).
- The JNI adapter copies every array in and out with `Get/SetDoubleArrayRegion` (`icheme_column_solver_jni.cpp` 110 to 117, 200). The packed result is `1 + nodes * 2 * (2 C + 3)` doubles: status, then per node the liquid and the vapour block `[ln phi (C), H, Z, dln phi/dT (C), dH/dT]` (evaluator 119 to 125, 146 to 158, 162).
- `NativeColumnSolver` loads from `icheme.native.path`, else from the jar's `natives/<os>-<arch>/`, else from `java.library.path`; it copies a jar library to `<game folder>/IChemE/natives/` (132 to 159) and refuses a library whose `nativeVersion()` is not `icheme-column-solver-cpp17` (22, 118 to 119).
- `build.gradle` 367 to 399: `nativeConfigure`, `nativeBuild` (CMake), `nativeResources` (into `natives/<host platform>/`), joined to the main resources when CMake is found (398); 401 to 425 `nativeSmoke`; 527 makes `src/main/cpp` an input of every `Test` task.
- Elementary functions in Java go through `DeterministicMath`: `StrictMath` (fdlibm 5.3 bits) for `log`, `exp`, `expm1`, `log1p`, `log10`, `cbrt`, `cos`, `sin`, `acos`, and `DeterministicPow` for `pow` (`DeterministicMath` 30 to 95). The column and the kernel call: `log`, `exp`, `expm1`, `cbrt`, `cos`, `acos`, `pow`, `Math.sqrt`, `Math.fma` (5 expressions, `PengRobinsonKernel` 788, 1590, 1610, 1625), `Math.ulp` (1613, 1632), `Math.scalb` (solver 808). `log1p` and `log10` are called only by the neural features and by a label (calculator 2626), which stay in Java.
- The solve's cancellation is a pull: `V3SolveControl.checkpoint()` (one method) is called at 73 source lines in 14 files, and the runtime's token throws from it on a deadline or a cancel (`BoundedCpuSolveService` 139, 165, 560 to 562). The learned route counts these calls as its budget (calculator 269 to 277).

## 2. Flat layouts

Sizes: `N = stageCount + 2` nodes (node 0 the condenser, `N - 1` the reboiler; `V3ColumnTopology` 60 to 70; at most 66). `Cp` public components (at most 64, `V3ComponentBasis` 10). `C` active components: those with a positive feed, in public order, with `pub[c]` the public index (`V3ActiveComponentBasis` 33 to 57). All arrays are row-major by node. Doubles are IEEE binary64 in native byte order; ints are 32-bit.

### 2.1 State (`V3DryMeshState`)

| Array | Length | Unit | Java source |
|---|---|---|---|
| `liquid` | `N * C`, index `n * C + c` | mol/s; exactly 0 where the phase or the point is absent | `liquidComponentFlows` |
| `vapor` | `N * C` | mol/s | `vaporComponentFlows` |
| `temperature` | `N` | K | `temperaturesKelvin` |
| `free_water` | `N` | mol/s; 0 on a dry tray and on both end nodes | `freeWaterFlowsMolPerSecond` |

One state is `2 N C + 2 N` doubles (42 nodes, 20 components: 1 764). The workspace holds a fixed number of states (base, trial, best, seed) and never allocates one per trial.

### 2.2 Support and unknown layout (`V3TruncationSupport`, `V3DegreeOfFreedomLedger`, `V3StageBlockLayout`)

Inputs of the layout: `mask[N * C]` bytes (bit 1 liquid, bit 2 vapour: `V3TruncationSupport` 48 to 49; all 3 for the identity support), `cond_liquid[C]` bytes (the condenser's per-component liquid rule, `V3CondenserComponentPhases.hasLiquid`), `wet[N]` bytes (0 dry, 1 wet with an unknown, 2 wet parametric: `V3WetTraySet`), the branch and `first_liquid_tray` (`V3ColumnTopology` 76 to 85).

- Liquid unknown of (n, c): the node has a liquid phase, and (n is not 0 or `cond_liquid[c]`), and `mask & 1` (`V3TruncationSupport` 266 to 270). Vapour unknown: the node has a vapour phase and `mask & 2` (273 to 275).
- Unknown order, the ledger's (192 to 215): for each node, for each component, liquid then vapour where present; then the temperature if n is not 0; then the free water if `wet[n] == 1`.
- Equation order (217 to 245): for each node, for each component with `mask != 0`, the material row, then the equilibrium row where both unknowns exist; then the energy row if n is not 0; then the saturation row if `wet[n] == 1`.

| Table | Length | Contents |
|---|---|---|
| `start` | `N + 1` | prefix sums of the block sizes; unknown count `U = start[N]` |
| `col_l`, `col_v` | `N * C` each | column index or -1 |
| `col_t`, `col_fw` | `N` each | column index or -1 |
| `row_mat`, `row_vle` | `N * C` each | row index or -1 |
| `row_energy`, `row_sat` | `N` each | row index or -1 |
| `unk_kind`, `unk_node`, `unk_comp` | `U` each | kind 0 liquid, 1 vapour, 2 temperature, 3 free water |
| `eq_kind`, `eq_node`, `eq_comp` | `U` each | kind 0 material, 1 equilibrium, 2 energy, 3 saturation |

Coordinates `x[U]`: `ln(flow / flow_scale[c])` for a component flow, the temperature in K, `ln(free_water / free_water_scale)` (`V3DryMeshCoordinateMap` 117 to 145). Residual: `physical[U]` and `scale[U]` (`V3MeshResidual`).

Structural rank: the ledger's maximum matching (343 to 376) is ported on these int tables, iteratively (no recursion), because a rank-deficient reduced ledger makes the calculator fall back to the identity support (calculator 2763 to 2770) and that verdict must be the same.

### 2.3 Matrices

- Band matrix (`V3BandedMatrix` 69 to 71): `values[size * (kl + ku + 1)]`, entry (row, col) at `row * (kl + ku + 1) + col - row + kl`.
- Factorization workspace (`V3BandedPivotedSolver.BandRows` 290 to 323): width `2 kl + ku + 1`, entry at `row * width + kl - row + col`; `column_divisors[size]`, `column_scales[size]`.
- The block Jacobian is assembled straight into the band. `kl` and `ku` are computed as Java computes them for that matrix: from the non-zero entries for the block Jacobian (`V3BlockJacobian` 56 to 70), from the structure for the finite-difference Jacobian (solver 731 to 739).
- Workspace size at 42 nodes and 20 components (dry): `U = 1 721`, block 41, band width about 165, factor width about 245: about 0.7 million doubles (5.6 MB) for the band and the factor. At the contract maximum (66 nodes, 64 components, wet trays): `U` up to 8 580, factor width about 775, about 53 MB for the factor alone. Large buffers are reserved with `try_reserve_exact`; a failure returns `OUT_OF_MEMORY` and the request runs on the Java path.

### 2.4 Problem block (one frozen attempt: `V3ColumnProblem` as resolved)

`iproblem`: `N`, `C`, `Cp`, feed tray, condenser branch (ordinal of `V3CondenserPhaseBranch`), first liquid tray, water condenser regime (0 none, 1 free water, 2 all vapour: `V3ColumnProblem` 308 to 315), flags (side draws, steam, pumparounds), then `pub[C]`, `mask[N * C]`, `cond_liquid[C]`, `wet[N]`, then the attempt budget: maximum iterations, finite-difference scale (0 fine, 1 coarse), frozen-Jacobian steps, local-block attempts, `RungBudget` (damping steps, gradient flag, verification steps, stall window), `Extension` (block, cap, window).

`dproblem`: `pressure[N]` Pa, `side_draw[N]` mol/s, `steam_feed[N]` mol/s, `steam_enthalpy[N]` W, `stage_heat[N]` W, `water_profile[N]` mol/s (the authored upward steam; `V3ColumnProblem` 49), `parametric_free_water[N]`, `feed[C]`, `flow_scale[C]`, then scalars: total feed, feed molar enthalpy, reflux ratio, reboiler duty, condenser temperature, water slip coefficient, free-water scale, closure tolerance, stall factor, stall residual floor, extension factor, then the seed state (2.1).

The total feed is computed in Java and passed: Java sums it with `DoubleStream.sum()` (`V3ActiveComponentBasis` 64), which is a compensated sum and does not equal a plain loop in the last bit.

### 2.5 Package block (thermo; the exact pair layout waits for S1 item 4 and S2 item 3)

`ipkg`: `Cp`, pair count, flags (excess pairs, Mathias-Copeman, temperature-dependent pairs), then the pair index arrays. `dpkg`: `Tc[Cp]`, `Pc[Cp]`, `omega[Cp]`, molar mass `[Cp]`, standard liquid density `[Cp]`, ideal-gas `cp[6 * Cp]` (the six coefficients about 298.15 K of `V3PropertyComponent`), the package's temperature and pressure range (the caloric clamp, `V3PengRobinsonSession` 205 to 207), the arrays of the kernel's `Pairs` record (`PengRobinsonKernel` 150 to 152: constant, slope, model, alpha, intercept, energy slope, low, high, cell arrays), `mathias_copeman[3 * Cp]`, then the water block: every number of `MaterialCatalog.Water` the column reads (molar mass, triple point, critical temperature and pressure, maximum temperature, boiling temperature, vaporization enthalpy, Watson exponent, the saturation and Shomate lists, the datum vaporization enthalpy; `MaterialCatalog` 181 to 184). Constants that are literals in Java source (IF97 Region 1 tables, `WaterRegion1` 23 to 27; the steam virial, `SteamVirial` 28 to 36; the numerical box) are literals in Rust, and the self-test compares their effect.

The block is built inside the pinned catalog scope (calculator 239, 807, 828) because the package and the water record come from thread context there.

### 2.6 Request block (the whole solve: `V3ColumnInput` and the entry)

`irequest`: ABI, entry (0 classical, 1 strategy, 2 learned first, 3 learned only), strategy (0 drift, 1 aggressive), stage count, feed stage, `Cp`, draw count (at most 4), steam feed count (at most 2), pumparound count (at most 4), seed count, warm-seed-usable flag, learned options (maximum iterations, budget polls, polls already spent by the Java candidate generator, wet start, the seven `Correction` numbers' int parts), then per draw its tray, per steam feed its stage, per pumparound its return tray, draw tray and split kind, then per seed its branch and `wet[N]`.

`drequest`: `feed[Cp]`, feed temperature, top pressure, stage pressure drop, column diameter, condenser temperature, reflux ratio, reboiler duty, liquid-supply screen ratio, closure, the totals Java sums with a stream (total feed, total draw, total steam, total cooling and heating duty: calculator 903 to 904, 1937 to 1938, 3219; `V3PumparoundSpec` 75 to 85), per draw its rate, per steam feed its rate and temperature, per pumparound its duty, then per seed `liquid[N * Cp]`, `vapor[N * Cp]`, `temperature[N]`, `free_water[N]` on the public axis (the shape of `V3NeuralSeed`, lines 13 to 15).

The warm-seed compatibility test compares two inputs and reads no state (calculator 1197 to 1203, 1231 to 1262); Java evaluates it and passes the flag and the retargeted seed.

### 2.7 Result block

`iout`: outcome (0 success, else 1 + ordinal of `V3SolverFailureCode`), failure detail code, condenser branch, `wet[N]`, `mask[N * C]`, Newton iterations, polls, attempts, the attempt code (enum of S2), the hydraulic-correction flag, a solve path as tokens (at most 32: kind and int argument), events (at most 32, the limit of `V3SolverDiagnostics` 19: code and two ints each).

`dout`: the published state on the active axis (2.1), the published `pressure[N]`, the feed molar enthalpy, the convergence evidence (backward error, log-flow change, temperature change, temperature step ratio, closure: `V3ConvergenceEvidence` 14 to 20), maximum scaled residual, final step norm, the hydraulic residual mismatch, then four doubles per event and one per path token.

Java builds every string (events, summaries, the solve path) from the codes and numbers with its own formatting, so no text differs between the paths.

### 2.8 Control buffer (cancel and budget)

A direct `ByteBuffer` of 32 bytes, native order, owned by the runtime's token:

| Bytes | Field | Writer |
|---|---|---|
| 0 to 3 | cancel flag (0 or 1) | the thread that cancels; Rust reads it with a relaxed atomic load at every poll site |
| 8 to 15 | remaining nanoseconds at entry, -1 for none | Java, before the call; Rust compares against its own monotonic clock from the entry |
| 16 to 23 | polls done | Rust, at return |
| 24 to 31 | reserved | |

`V3SolveControl` gains one default method, `java.nio.ByteBuffer nativeSignal()` returning null. A control with no signal (tests, `UNBOUNDED`) runs the native call without a deadline, bounded by the poll budget and the iteration caps. The runtime's cancellation token implements it (`BoundedCpuSolveService`, package S6's file). The learned budget is a poll counter inside Rust at the same sites as Java (section 6).

## 3. The calls

Java declarations in `NativeColumnSolver`; the C symbols are `Java_com_wormzjl_icheme_science_column_v3_NativeColumnSolver_<name>` with `(JNIEnv*, jclass, ...)`.

```java
private static native int    nativeAbi();                 // must equal NativeColumnSolver.ABI
private static native String nativeFormulation();         // must equal the Java formulation token
private static native int    nativeSelfTest(double[] out);// known-answer values, compared one by one in Java
// stage (a)
private static native int nativePrBatch(double[] pkg, int components, int nodes, double[] states, double[] out);
// stage (b)
private static native int nativeBandedSolve(int size, int kl, int ku, double[] band, double[] rhs,
                                            double[] solution, double[] evidence, int[] counts);
private static native int nativeProperties(int[] ipkg, double[] dpkg, int count, int[] phase, double[] tpx, double[] out);
private static native int nativeDerivatives(int[] ipkg, double[] dpkg, int count, int[] phase, double[] tpx, double[] out);
// stage (c)
private static native int nativeAttempt(int[] ipkg, double[] dpkg, int[] iproblem, double[] dproblem,
                                        java.nio.ByteBuffer control, int[] iout, double[] dout);
// stage (d): the single call that carries a whole solve
private static native int nativeSolve(int[] ipkg, double[] dpkg, int[] irequest, double[] drequest,
                                      java.nio.ByteBuffer control, int[] iout, double[] dout);
```

Return value of every call (the transport status):

| Code | Name | Java's action |
|---|---|---|
| 0 | `OK` | read the result block (a success or a typed failure) |
| 1 | `CANCELLED` | call `control.checkpoint()`, which throws; if it does not, throw `CancellationException` |
| 2 | `DEADLINE` | the same |
| 3 | `BAD_LAYOUT` | a length or an index in a block is wrong: log once, run this request on the Java path |
| 4 | `UNSUPPORTED_PACKAGE` | the kernel form is not ported yet: Java path |
| 5 | `OUT_OF_MEMORY` | Java path |
| 6 | `PANIC` | a Rust panic was caught at the boundary: Java path, and the library is off for the rest of the session |
| 7 | `FP_ENVIRONMENT` | the thread flushes subnormals to zero (`f64::MIN_POSITIVE / 2 == 0` at entry): Java path |

Marshalling rules: arrays are copied in with `Get<Type>ArrayRegion` and out with `Set<Type>ArrayRegion`, as the C++ adapter does. No `GetPrimitiveArrayCritical`: a solve runs for seconds and would hold the garbage collector. Every length is checked against the header before any index is used. Every export wraps its body in `catch_unwind`.

Attempt codes (the enum of S2, the solver's strings today): `CONVERGED`, `STATE_DOMAIN`, `MAX_ITERATIONS`, `STALLED`, `STALLED_SUPPORT_FLOOR`, `JACOBIAN_BAND_STRUCTURE`, `LINEAR_SINGULAR`, `LINEAR_ILL_CONDITIONED`, `LINEAR_BACKWARD_ERROR_EXCEEDED`, `LINE_SEARCH_EXHAUSTED`, `INTERNAL_INVARIANT` (solver 389 to 608; `V3BandedPivotedSolver` 273 to 277). The published failure codes are `V3SolverFailureCode`'s eight values, mapped as `failureCode` maps them (calculator 3449 to 3459).

## 4. Crates

Workspace at `src/main/rust/` (beside `src/main/cpp`, which it replaces; no new top-level folder). `math`, `thermo` and `column` carry `#![forbid(unsafe_code)]`; only `jni` has `unsafe`.

| Crate | Holds | Depends on |
|---|---|---|
| `icheme-math` | `fdlibm/` (`log`, `exp`, `expm1`, `cbrt`, `cos` with `rem_pio2`, `acos`), `pow` (`DeterministicPow` and its `FdlibmPow` fallback), `fma` (software, correctly rounded), `java` (the semantics of `Math.max`, `min`, `abs`, `round`, `floor`, `clamp`, `ulp`, `scalb`, and the compensated stream sum), `banded` (`V3BandedMatrix`, `V3BandedPivotedSolver`); later `sparse` (the fluid's LU) | nothing |
| `icheme-thermo` | `kernel` (`PengRobinsonKernel`: pairs, temperature preparation, evaluate, derivatives, root selection, Wilson K), `excess` (`HuronVidalExcess`), `pairs` (`PairInteractions`), `ideal_gas`, `session` (`V3PengRobinsonSession`, `V3PengRobinsonThermo`, the workspace), `flash` (`V3FeedFlash`), `water` (`V3WaterProperties`, the liquid part of `WaterRegion1`, `SteamVirial`) | `icheme-math` |
| `icheme-column` | `layout`, `state`, `support`, `problem`, `residual`, `jacobian`, `newton`, `ladder`, `ramp`, `initializer`, `wet`, `audit`, `hydraulics`, `control` | `icheme-math`, `icheme-thermo` |
| `icheme-jni` | the `cdylib` `icheme_column`; the exports of section 3, marshalling, the panic boundary, the self-test | the three above, `jni-sys` (declarations only) |

`jni-sys` is pinned by `Cargo.lock` and vendored with `cargo vendor` into `src/main/rust/vendor/`, so a build needs no network (`--locked --offline`). A hand-written JNI function table is ruled out: one wrong slot index corrupts the JVM.

Every Rust function that has a Java twin carries an anchor comment, `// java: V3MeshResidualEvaluator.energyResidual`, and keeps the Java name in snake case. `tools/column-parity/anchors.mjs` lists the Java methods a commit range changed and the Rust anchors that name them.

## 5. Stages

Each stage ends in a state that can ship: the library is used up to a ceiling (`-Dicheme.native.column=off|batch|kernel|attempt|solve`; the default is the highest stage whose gate passed), and the Java path below the ceiling is untouched.

### Stage 0: Java, before any Rust

These remove rules the port would otherwise copy, and they pin the fallback. Each is a small batch with its own gates (COLUMN_REVIEW has the proofs).

| Step | Finding | Effect on the port |
|---|---|---|
| Switch the C++ pass off by default | D1 | the Java path is deterministic again while the port is built |
| Enums for attempt codes, audit families, rung kinds | S2 | the result block has codes to carry |
| Take the cutoff, the closure API, the export hooks and the probes out of the port's scope | S4 | about 750 Java lines are not ported |
| Hydraulic budget in polls | D2 | no wall clock inside the solve; fixtures are reproducible |
| Decide the learned budget's unit | D6 | either the port copies the 73 checkpoint lines, or it counts work units |
| Decide the analytic Jacobian, the inside-out trial, the verification cascade | P1, L1, L2 | up to about 1 100 Java lines are not ported, and the port's Newton loop is the simple one |
| Record the parity fixtures from the Java path | section 7 | the reference exists before the first Rust line |

Done when: the fixtures are recorded and `V3RustParityTest` passes its Java half (the Java path reproduces every recorded result bit for bit, twice in one run).

### Stage (a): the toolchain and the PR batch in Rust; the C++ lane removed

Rust (about 1 500 lines):

| File | Ports | Java or C lines |
|---|---|---|
| `icheme-math/src/fdlibm/{log,exp,cbrt,cos,acos}.rs` | fdlibm 5.3 `e_log.c`, `e_exp.c`, `s_cbrt.c`, `s_cos.c`, `k_cos.c`, `k_sin.c`, `e_rem_pio2.c`, `k_rem_pio2.c`, `e_acos.c` | about 900 C |
| `icheme-math/src/java.rs`, `fma.rs` | the `Math` semantics the kernel uses; software `fma` | new, about 200 |
| `icheme-thermo/src/kernel.rs` (first part) | the constant-matrix branch of `PengRobinsonKernel.evaluate` (from 413) and `selectRoot` (from 1503), with the PR78 constants (288 to 291, 1678 to 1680) | about 600 Java; S1's review fixes the cut |
| `icheme-jni/src/lib.rs` | `nativeAbi`, `nativeFormulation`, `nativeSelfTest`, `nativePrBatch` with the packed layout of section 1 | replaces 517 C++ |

Java and build:

- `NativeColumnSolver` (164 lines): library name `icheme_column`; the handshake of section 8; delete `solve`, `smokeSolve`, `pengRobinsonBatch`, `ResidualEvaluator`, `SolveResult` and their natives (39 to 56, 67 to 85). `captureNativeThermoSnapshot` (evaluator 82 to 163) keeps its shape.
- Delete `src/main/cpp` (742 lines). Replace `build.gradle` 336 to 425 and 527 with the tasks of section 8. `src/main/rust/README.md` replaces the C++ one. Update row "C++ column library" of `documentation/reference/code-guide/column-v3.md` (line 252).

Gate: `NativeColumnSolverTest` rewritten. `nativePengRobinsonPassMatchesTheJavaKernel` compares raw bits (today 1e-9, lines 116 to 118). `separatingColumnPublishesTheSameProductsWithEitherOuterPass` asserts equal published products, not 1e-9 (167). `cargo test` checks each fdlibm function against a table of Java `StrictMath` bits (the table is generated by a test-tree Java main and tracked under `src/main/rust/icheme-math/tests/data/`).

Done when: a jar built on this machine carries `natives/windows-x86_64/icheme_column.dll`; a V3 solve gives the same bits with the library on and off; no CMake or C++ compiler is needed; a machine with no cargo builds a jar with no library and says so.

Expected failures: a wrong branch in `rem_pio2` shows as one-ulp differences in `cos` for arguments above pi/4 (the cubic's trigonometric roots use them); `cbrt` of a negative argument; root selection differing where three roots nearly coincide (the kernel's `Math.ulp` tests at 1613 and 1632 need the Java `ulp`).

If L1 deletes the inside-out trial, the batch has no caller: stage (a) then ships the toolchain, the handshake and the self-test, and `nativePrBatch` stays as the test's export until stage (b).

### Stage (b): linear algebra and thermo

Rust (about 3 400 lines):

| File | Ports | Java lines |
|---|---|---|
| `icheme-math/src/banded.rs` | `linalg/V3BandedMatrix` (80), `linalg/V3BandedPivotedSolver` (395) | 475 |
| `icheme-math/src/{pow,fdlibm/expm1}.rs` | `DeterministicPow` (259), `FdlibmPow` (322), `s_expm1.c` | 581 Java, 150 C |
| `icheme-thermo/src/{kernel,excess,pairs}.rs` | the rest of `PengRobinsonKernel` the column calls (`prepareTemperature` 420, `evaluateDerivatives` 774, `wilsonK` 401, the workspace 1698, `Evaluation` 1997, `Derivatives` 2032), `HuronVidalExcess` (366), `PairInteractions` (277) | about 1 500 of 2 077, plus 643 |
| `icheme-thermo/src/{session,ideal_gas}.rs` | `V3PengRobinsonSession` (256), `V3PengRobinsonThermo` (274, without the truncated flash), `V3ThermoWorkspace` (56), `V3FugacityResult` (60), `V3FugacityDerivatives` (111), `V3PropertyComponent` (60) | about 780 |
| `icheme-thermo/src/flash.rs` | `V3FeedFlash` (156), `V3FlashResult` (113) | 269 |
| `icheme-thermo/src/water.rs` | `V3WaterProperties` (286), `WaterRegion1.evaluateLiquid` (part of 175), `SteamVirial` (110) | about 500 |

Exports and Java hooks:

- `nativeBandedSolve`: `V3BandedPivotedSolver.solve` (30) delegates when the ceiling allows. `evidence` returns backward error, minimum and maximum pivot, pivot growth; `counts` the failure code and the pivot swaps (the fields of `Result`, 239 to 271).
- `nativeProperties`: one call for a whole residual evaluation. `tpx` holds, per requested phase, temperature, pressure and the normalized public composition that `normalizedPublicPhaseComposition` builds (evaluator 602 to 624); `phase` holds 0 liquid or 1 vapour. `out` holds per phase a status (0, 1 domain, 2 root failure), `ln phi [Cp]`, the compressibility, the molar enthalpy (ideal plus residual, `V3PengRobinsonThermo` 245 to 250), the physical root count and the root separation. Java throws the `V3ThermoException` of the first failing phase in the Java evaluation order, which is vapour before liquid for each node (evaluator 584 to 597). Hook: `nodeProperties` (573 to 579).
- `nativeDerivatives`: the same for `fugacityDerivatives` (`V3PengRobinsonThermo` 142 to 162): adds `dln phi/dT [Cp]`, `dln phi/dn [Cp * Cp]`, `dH/dT`, the partial molar enthalpies `[Cp]`. Hook: the assembler's per-node loop (`V3BlockJacobianAssembler` 121 to 124).
- `nativeSupports(package)`: true for the kernel forms ported so far; `constantClassicalInteractions() == null` is the present form of this test (`V3PengRobinsonSession` 104 to 123).

Gate: `V3RustKernelBitsTest` (in `columnGates`). Both paths run in the test process, so no stored reference is needed: for each bundled package and each test package (a derived-pairs package with an excess pair and one with a Mathias-Copeman alpha among them), at the node states of the parity fixtures' accepted profiles and at those states perturbed, every output double has the Java bits; the water functions on a grid of 273.16 to 647 K and 1 kPa to 5 MPa; the banded solve on the Newton matrices of five fixture solves (solution bits, failure code, pivot swaps). Then every parity fixture gives the recorded Java result bit for bit with the ceiling at `kernel`.

Done when: the gate passes on this machine, and B1's column scenarios are measured again with the ceiling at `kernel` (a performance task, on its own).

Expected failures: the kernel's temperature-keyed cache (the assembler's comment, 22 to 26, says it holds pure functions of the temperature; port it as that or drop it); the order of pairs in the excess sum; `Math.max` with a NaN operand; the composition loop skipping truncated components (evaluator 612 to 618).

### Stage (c): one rung's residual, Jacobian and Newton

Rust (about 3 000 lines; about 2 300 if P1 and L1 landed):

| File | Ports | Java lines |
|---|---|---|
| `icheme-column/src/layout.rs` | the enumeration of `V3DegreeOfFreedomLedger` (192 to 259), `V3StageBlockLayout` (110) | about 180 |
| `state.rs` | `V3DryMeshState` (187), `V3DryMeshCoordinateMap` (151) | 338 |
| `problem.rs` | the state functions of `V3ColumnProblem` (182 to 350: free water, tray water vapour, condenser split, withdrawal fraction), `V3SideDrawSpec.withdrawal` | about 210 |
| `residual.rs` | `V3MeshResidualEvaluator` (862; 530 without the native and frozen-snapshot parts), `V3MeshResidual` (104) | 630 to 970 |
| `jacobian.rs` | `V3BlockJacobianAssembler` (699), `V3BlockJacobian` (146); `V3FiniteDifferenceJacobian` (382) and `V3NormalEquations` (169) as Java has them at that time | 845 to 1 400 |
| `newton.rs` | `V3SimultaneousColumnSolver` (1 216), `V3ConvergenceEvidence` (95), the reachability of `V3TruncationSupport` (722 to 778) | about 1 370 |
| `control.rs` | the control buffer, the poll counter | new, about 80 |

Export and hook: `nativeAttempt` replaces the `switch` in `solveSingleProblem` (calculator 1668 to 1683) when the ceiling allows and the thermo model is the registered PR adapter. Java marshals the prepared problem (2.4) and the seed, and rebuilds `Attempt.Converged` or `Attempt.Failure` with its `Evidence` (solver 1183 to 1215). The ladder, the support refresh, the wet-set logic and the audit stay in Java. Test models that are not the PR adapter (`V3ThermoWorkspace(int)`, the manufactured flash) always run in Java.

Gate: `V3RustAttemptParityTest`. A test hook runs every attempt of every parity fixture on both paths from the same prepared problem and seed: the attempt code, the iteration count and the poll count are equal; the state is inside the tolerance of section 7 and the number of bit-equal attempts is printed (expected: all, while the port is a transliteration). Then every fixture end to end with the ceiling at `attempt`.

Done when: the gate passes; the cold `medium_crude` and literature presets publish the recorded outcome, solve path and state inside tolerance; B1's column scenarios are measured again.

Expected failures: an iteration count that differs by one (find the first diverging iteration with the Newton trace: Java has `NewtonTrace`, solver 918 to 949, and the Rust attempt returns the same per-iteration residuals under a debug flag; `tools/column-parity/trace-diff` compares them); a rejected trial that Java signals with an exception class the port maps to the wrong code (solver 385, 489, 512, 672, 818, 867, 879); the held-point lift applied to a copy instead of the candidate (solver 1134 to 1172 writes into the candidate array).

### Stage (d): the whole solve in one call

Rust (about 6 000 lines):

| File | Ports | Java lines |
|---|---|---|
| `ladder.rs`, `ramp.rs` | the numerical part of `V3ColumnCalculator`: branch choice, stage and pressure continuation, the attempt loop with refreshes, the feature ramp, heat bounds, the floor lift, the hydraulic correction (3 637 less labels, reporting and learned-entry glue) | about 2 400 |
| `initializer.rs` | `V3ColumnInitializer` (1 108) | 1 108 |
| `support.rs` | `V3TruncationSupport` (817 less the cutoff), `V3StageEquilibriumRatios` (136), the matching (ledger 343 to 376) | about 900 |
| `wet.rs` | `V3WetTraySet` (303), `V3FreeWaterContinuation` (461), `V3EnergyShiftPredictor` (330) | 1 094 |
| `problem.rs` (rest) | `V3ColumnProblemResolver` (243), `V3OperatingDomainValidator` (129), `V3ColumnTopology` (119), `V3ActiveComponentBasis` (65), `V3CondenserComponentPhases` (42), `V3CondenserPhaseTransition` (119), `V3PumparoundSpec` and `V3SteamFeedSpec` (133), `V3HeatFeasibility` (143), `V3LiquidSupplyScreen` (176) | 1 169 |
| `audit.rs` | `V3AcceptanceAuditor` (1 055) for the ladder's own rung decisions, `V3ColumnDutyLedger` (117) | 1 172 |
| `hydraulics.rs` | `V3TrayHydraulics` (414) | 414 |

Export and hook: `nativeSolve`. `V3ColumnCalculator.calculateInCatalog` (both forms, 243 and 833) builds the request, calls, and on `OK`:

1. resolves the problem from the input, the returned branch and pressure profile, and applies the returned support and wet set (`V3ColumnProblemResolver.resolve`, `withTruncation`);
2. audits the returned state with the Java `V3AcceptanceAuditor` at the request's closure;
3. on an accepted audit, builds the result exactly as `publishAccepted` does today (calculator 426 to 437): digest, duty ledger, streams, inspection, warm seed, diagnostics from the returned codes;
4. on a refused audit of a state Rust accepted: counts `nativeAuditDisagreements`, logs once, and runs the Java path for this request.

The learned route: Java generates the candidates (`model.candidates`, calculator 288), passes them as seeds with the polls already spent, and Rust runs `correctNeuralSeed` per candidate and the classical backup, in the order of calculator 290 to 353.

Stays in Java for good: the input contract (`V3ColumnInput`, the spec records, `V3ColumnTemplate`, `V3InputDigest`, the formulation labels of calculator 2578 to 2664), the neural seed (`V3AnchorTransformerInitializer`, `V3NeuralRegistry`, the feature classes, `V3NeuralSeed`), the results for display (`V3ColumnResult`, `V3ColumnStreamProperties`, `V3ColumnInspection`, `V3ColumnDisplayResult`, `V3TrayHydraulicsSummary`, `V3ColumnOutcome`, `V3SolverDiagnostics`, `V3AcceptanceAudit` and every string), the catalog pinning, and the whole Java solver as the fallback and as the publication audit.

Gate: `V3RustParityTest` in full (section 7) with the ceiling at `solve`.

Done when: every fixture has the recorded outcome class, branch, wet set and support, and its values inside tolerance; `nativeAuditDisagreements` is zero over the fixtures and over the column tests of `columnGates` run with the ceiling at `solve`; the running column (`ColumnSolve`) and the simulator's column (`ColumnUnit`) solve through the one call; B1's scenarios are measured again and the result is written into the batch's review.

Expected failures: a route that differs because a poll site is missing (the poll count at return is the first thing to compare); a heat-rung subdivision keyed by a double (`HeatSubdivisions`, calculator 2481 to 2494, uses `Double` keys: port as bit patterns); an exception that Java turns into a failure code by its class and by whether admission had passed (calculator 1173 to 1193); the request-only gates summing draws and steam with stream sums (2.6).

### Size

| Stage | Rust lines (estimate) | Java twin lines | Java lines changed |
|---|---|---|---|
| (a) | 1 500 | 600 Java, 900 C (fdlibm) | about 150, and 742 C++ deleted |
| (b) | 3 400 | about 4 750 | about 120 |
| (c) | 2 300 to 3 000 | 3 600 to 4 500 | about 150 |
| (d) | 6 000 | about 8 250 | about 300 |
| JNI, control, self-test | 600 | | |
| total | about 13 800 to 14 500 | about 17 000 to 18 000 | |

## 6. Determinism

- **No fused multiply-add by contraction.** rustc emits no `contract` flag, so `a * b + c` stays two operations on every target, arm64 included. The build sets no `target-cpu` and no `+fma`. The Java kernel does call `Math.fma` at five expressions (`PengRobinsonKernel` 788, 1590, 1610, 1625); those, and only those, call `icheme_math::fma`, a software fused multiply-add. `f64::mul_add` is banned: without the `fma` target feature it calls the platform's C library. So the rule of REVIEW_PLAN section 2 reads "no contraction, and an explicit correctly rounded fma where Java has one", not "no FMA".
- **No fast-math.** No `core::intrinsics` fast operations, no `-ffast-math` equivalent, no reordering of sums: every loop keeps the Java order.
- **No platform libm.** `f64::{ln, exp, exp_m1, powf, powi, cbrt, sin, cos, acos, tan, atan, sinh, mul_add, hypot}` are banned by `clippy.toml` (`disallowed-methods`, `-D warnings` in `rustCheck`) and by a source test in the Java tree in the style of `DeterministicMathSourceTest`. `f64::sqrt` is allowed: it is the correctly rounded hardware instruction on both architectures, as `Math.sqrt` is.
- **The port of `DeterministicMath` it needs**: `log`, `exp`, `expm1`, `cbrt`, `cos`, `acos` transliterated from the fdlibm 5.3 C sources (the Sun notice kept; not from the JDK's Java port, which has another licence), and `pow` transliterated from `DeterministicPow` with its five tables (26 to 201) and `FdlibmPow` behind it. `sin`, `asin`, `atan`, `atan2`, `tanh`, `log1p`, `log10` are not needed by the column and are added when the fluid step needs them.
- **Java semantics, not Rust defaults**: `Math.max` and `min` return NaN for a NaN operand and order -0.0 below +0.0; `Math.round` rounds half up; `Math.clamp`; `Math.ulp`; `Math.scalb`; `(int)` casts (Rust's `as` saturates the same way since 1.45). `DoubleStream.sum()` is compensated: the totals are passed from Java (2.4, 2.6), and `java::stream_sum` exists for the sites inside the ladder (`V3ColumnInitializer` 641, 773).
- **One thread per solve.** The crates start no thread and hold no mutable static, no `thread_local`, no global cache. Every export is re-entrant; several Java workers may each run a solve.
- **The floating-point environment** is checked at entry (status 7): a thread on which another native library set flush-to-zero does not run the Rust path.
- **The wall clock** enters only through the deadline of the control buffer, which ends a solve and never changes a result. The hydraulic budget is polls (D2).
- **Poll sites.** While the learned budget counts polls (D6), every `control.checkpoint()` of the ported Java has a `ctl.poll()` at the same place, and the count is returned. Equal counts on a fixture show equal control flow.
- **Release profile**: `opt-level = 3`, `codegen-units = 1`, `panic = "unwind"`, `debug-assertions = false`, `overflow-checks = false` with explicit `wrapping_*` where Java wraps. One binary per target; no CPU dispatch.

## 7. Parity with the Java path

### Tolerance per quantity

Two levels, because they answer different questions.

**Level 1, a function: exact bits.** The kernel, water, the flash, the band LU, one residual evaluation, one Jacobian assembly. Same IEEE operations in the same order on the same elementary-function bits: a difference is a port defect. A tolerance here would hide a wrong constant in the tenth digit, which is what today's 1e-9 bound of `NativeColumnSolverTest` 116 to 118 allows.

**Level 2, a whole solve: the solver's own tolerances, times two.** Two accepted states of one problem each lie within one certified Newton step of the root, so they differ by at most two steps. With `tau` the closure (1e-8 strict, 1e-3 on the fast first solve: calculator 65 to 67; `V3ConvergenceEvidence` 21 to 25, 62 to 67):

| Quantity | Gate | From |
|---|---|---|
| Outcome class (success, or which failure code) | equal | REVIEW_PLAN section 2, "Results", rule 3 |
| Condenser branch, wet-tray set, support mask | equal; a fixture where a named deviation moves one is listed with its reason | they select the equations |
| Node temperature | `abs(dT) <= 2 * (1e-6 K + 1e-9 * T)`, about 3e-6 K at 600 K | the temperature step gate, which the closure does not loosen (solver 907 to 908; `V3ConvergenceEvidence` 23, 93) |
| Component flow of a retained phase, free water | `abs(d ln n) <= 2 * tau`: 2e-8 strict, 2e-3 fast | the log-flow step gate (`V3ConvergenceEvidence` 92) |
| Flow of a removed phase | `abs(dn) <= 2 * 1e-10 * flow_scale[c]` | one floor per sink edge with the audit's slack of 2 (`V3TruncationSupport` 34, 46) |
| Product rates and compositions (distillate, bottoms, draws) | as the flows, for components above 1e-6 mole fraction | the threshold `NativeColumnSolverTest` 163 uses |
| Condenser duty | `abs(dQ) <= 2 * max(1 W, 1e-6 * largest term, N * tau * S_E)`, `S_E = max(1, F_total * 1e5 W)` | the audit's own whole-column limit (`V3AcceptanceAuditor` 183 to 184; evaluator 675 to 677) |
| Reboiler duty, stage heats, draw rates, steam | equal | they are inputs |
| Iterations, polls, solve path | equal while the port is a transliteration; reported, not gated, once a deviation is adopted | fingerprint of the control flow |

In numbers, for a 300 mol/s feed and 32 nodes: strict, the duty gate is 19 W on duties of tens of megawatts; fast, it is 1.9 MW, because a closure of 1e-3 lets each tray's energy row be out by 30 kW. The fast bound is wide because the fast closure is wide; the lever is the closure (finding D3), not the port.

Beside each gate the test prints the largest observed difference. Expected: zero while the port is a transliteration; of the order of 1e-10 relative if the port adopts the analytic Jacobian while Java keeps the finite-difference one. A watch line (not a gate) fails the review of a batch when an observed difference exceeds 1e-9 relative with no deviation named for it.

### Fixtures

`src/test/resources/column/v3/parity/<name>.json`, one file per case, each under 1 MB: the entry and its options, the input (the fields of `V3ColumnInput`), the seed where the entry has one, and the Java result: outcome, failure code, branch, wet mask, support mask, state (hex doubles), published pressures, duties, convergence evidence, iterations, polls, path tokens.

| Class | Cases | Count |
|---|---|---|
| A. cold, classical, strict | the `V3TestInputs` dry inputs (binary, cdu17), Holland example 3-2, the manufactured two-component four-tray case, the literature CDU (dry and wet), the methane CDU, each bundled preset, a pot (0 trays), a zero-reflux dry top, a total condenser at 1.1 and 1.5 bar, a vacuum column that enters the pressure ladder, three pumparounds with sump steam (TJL19), the 40 MW return-tray case | 16 |
| B. cold, AGGRESSIVE (the running column's first solve) | the bundled presets and the literature CDU; one of 40 trays (the 15, 30, N grid) | 6 |
| C. warm | feed +2 %, feed +20 %, reboiler duty -10 %, a seed whose warm pass fails | 4 |
| D. learned | the bundled model on two presets, one input the model declines (a fourth draw), one budget exhaustion | 4 |
| E. typed failures | draws at or above the feed, the liquid-supply screen, the static cooling bound, a pressure outside the package, a draw-wall non-convergence, a path-dependent heat bound, an audit failure, an initialization failure | 8 |

Recorded by `tools/column-parity/` (a tracked tool with a `README.md` and a row in `tools/INDEX.md`): a test-tree main run by the Gradle task `columnParityRecord`, with the ceiling `off`. Fixtures are re-recorded only by a batch that changes the Java physics, and the batch states the old and new values.

### Gates

| Test (in `columnGates`) | Checks | Stage |
|---|---|---|
| `V3RustParityTest`, Java half | the Java path reproduces every fixture bit for bit | 0 |
| `NativeColumnSolverTest` | handshake, self-test, the PR batch bits | (a) |
| `V3RustKernelBitsTest` | level 1 for thermo and linear algebra | (b) |
| `V3RustAttemptParityTest` | every attempt of every fixture | (c) |
| `V3RustParityTest`, Rust half | level 2 for every fixture | (d) |

A test that needs the library and finds none is skipped with a printed reason, as `NativeColumnSolverTest` does today through `icheme.native.packaged` (`build.gradle` 400). The final lane of a batch that touches the Rust sources or their Java twins builds the library first, so the skip cannot hide a failure there.

## 8. Packaging

### Targets

| Jar folder | Rust target | Library file | Built by |
|---|---|---|---|
| `natives/windows-x86_64/` | `x86_64-pc-windows-msvc` (installed here) | `icheme_column.dll` | `cargo build` on this machine; static C runtime (`-C target-feature=+crt-static`, as the C++ lane linked statically: `CMakeLists.txt` 7 to 8) |
| `natives/linux-x86_64/` | `x86_64-unknown-linux-gnu`, glibc 2.17 baseline | `libicheme_column.so` | `cargo zigbuild --target x86_64-unknown-linux-gnu.2.17`, or a Linux runner |
| `natives/linux-aarch64/` | `aarch64-unknown-linux-gnu`, glibc 2.17 | `libicheme_column.so` | `cargo zigbuild`, or an arm64 runner |
| `natives/macos-x86_64/` | `x86_64-apple-darwin` | `libicheme_column.dylib` | `cargo zigbuild`, or a macOS runner |
| `natives/macos-aarch64/` | `aarch64-apple-darwin` | `libicheme_column.dylib` | `cargo zigbuild`, or a macOS runner; the library must carry at least an ad-hoc signature, which the linker writes for arm64 |

Any other platform (Windows on arm64, a 32-bit JVM, musl) has no library and runs the Java path. The folder names are the ones `NativeColumnSolver.platform()` gives today (91 to 102).

Cross targets need installs the owner approves: four `rustup target add`, and `zig` with `cargo-zigbuild`; B1 reports what is present. The alternative is a hosted build matrix with five runners. A development build makes the host library only; a release build needs all five (`nativeBuildAll`), otherwise the release notes name the platforms that run the Java path.

### Gradle

| Task | Does |
|---|---|
| `rustCheck` | `cargo fmt --check`; `cargo clippy --locked --offline -- -D warnings`; `cargo test --locked --offline` (the fdlibm bit tables, the band LU) |
| `nativeBuild` | `cargo build --release --locked --offline -p icheme-jni --target-dir build/native/cargo`; inputs `src/main/rust` without `vendor/`; output the host library |
| `nativeBuildAll` | the same for the five targets |
| `nativeResources` | copies the libraries that exist into `build/generated/nativeResources/natives/<os>-<arch>/` |
| `nativeSmoke` | loads the built library and runs `NativeColumnSolverTest` and `V3RustKernelBitsTest` |
| `columnParityRecord` | records the fixtures from the Java path |

`build/` is already ignored, and each worktree has its own cargo target folder there. Cargo is found from `-Pcargo`, the PATH, or `~/.cargo/bin`; with none, the jar carries no library and the build says so (the present behaviour for CMake, `build.gradle` 398 to 399). The line that makes the native sources an input of every `Test` task (527) goes: only the tests of section 7 declare the built library as an input. A cargo build runs inside its Gradle invocation and counts as that lane; on the second lane it runs with `-j 4`.

### Loader and handshake (`NativeColumnSolver`)

1. The library name is `icheme_column`, and the copy in the game folder is `icheme_column-<ABI>` (today one name, 147 to 158), so an old C++ library or an older ABI is never picked up and a running game's locked file never blocks a new version.
2. `nativeAbi() == ABI`. The ABI number rises with any change of a block layout or a signature.
3. `nativeFormulation()` equals the Java token built from `FORMULATION_REVISION` and the wet and heat revisions (calculator 30 to 52). A library compiled for other equations is refused, so a stale library can never run beside new Java physics.
4. `nativeSelfTest(out)`: the library fills 256 known-answer values (each elementary function at fixed arguments, `fma`, `pow`, one kernel evaluation and one derivative evaluation on a fixed three-component mixture, one band solve). Java computes the same 256 values with its own code and compares the raw bits one by one. A library that does not give the Java bits on this processor and operating system is refused. This is how "the Rust path is deterministic on every platform" is enforced at run time on targets the build machine cannot execute.
5. Any refusal logs one line with the reason and the Java path runs, as today (120 to 126).

Switches: `-Dicheme.native.column=<ceiling>` and `-Dicheme.native.path=<file or folder>`; Gradle `-PnativeColumn=`, `-PnativeLibraryPath=`. `icheme.native.thermo.batch` goes with the C++ lane.

A world may change path between sessions (a library added, removed, or refused): the column's accepted profiles, split tables and seeds are state, and are recomputed; no fact depends on the path.

## 9. The cost of two implementations

After stage (d) about 17 000 Java lines have a Rust twin of about 14 000 lines. Every physics change then has to land in both. The rules that keep them together:

1. **Java first.** A change is made, gated and reviewed on the Java path. Java is the reference and the publication audit.
2. **Same batch, or a lower ceiling.** The batch ports the change to Rust and passes the gates of section 7, or it lowers the default ceiling below the changed layer in the same commit. A stale Rust layer is never left enabled; the formulation token (8, step 3) enforces it at load.
3. **Fixtures move with the physics.** A change of an accepted state re-records the fixtures and states the largest shift per quantity, under the "Results" rule of REVIEW_PLAN section 2.
4. **Anchors.** `tools/column-parity/anchors.mjs` lists, for the batch's commit range, each changed Java method and the Rust function that names it. Merge preparation runs it; a listed function with no Rust change needs a sentence in the batch review.
5. **One layout document.** The block layouts of section 2 live in `src/main/rust/LAYOUT.md`; the Java marshalling code and the Rust parsing code cite its section numbers, and the ABI number is in its first line.

What a change costs: its Java diff, about the same size again in Rust, a fixture re-record, and one more gate group in the lane. A change that only touches what stays in Java (input contract, labels, display, the neural start) costs nothing extra. Every rule deleted before the port (stage 0) is paid for once instead of twice from then on, which is why the simplifications of COLUMN_REVIEW come first.

The fluid step follows the same pattern and reuses `icheme-math` and `icheme-thermo`; its sparse LU joins `icheme-math` beside the band solver (COLUMN_REVIEW S6).

## 10. Pitfalls

1. **Stream sums** are compensated (`DoubleStream.sum()`): 12 numerical sites in the column, among them `V3ActiveComponentBasis` 64. A plain loop differs in the last bit, and that bit is in every energy row scale.
2. **`Math.max` and `min`** differ from `f64::max` and `min` on NaN and on signed zero. The band solver's pivot search and growth scan use them (`V3BandedPivotedSolver` 59 to 66, 91 to 95).
3. **A negative zero** entering the band must become a positive zero (`V3BandedPivotedSolver` 315 to 318).
4. **Exceptions are control flow in Java.** A rejected line-search trial is an `IllegalArgumentException` or a `V3ThermoException` (solver 818); a state outside the log domain is one (evaluator 604 to 620); the class of the exception and where it is caught decide the failure code (calculator 1173 to 1193; solver 512 to 523, 867 to 884). The port uses a `Reject` enum with the same distinctions and a table of where each is caught.
5. **Evaluation order of properties**: vapour before liquid in a node (evaluator 584 to 597); the first failure wins.
6. **`kl` and `ku`** of the block Jacobian come from its non-zero entries (`V3BlockJacobian` 56 to 70). Use the same rule; do not assume a wider band is harmless.
7. **The held-point lift mutates the candidate** before it is decoded (solver 811 to 812, 1166 to 1167).
8. **Recursion**: the matching's `augment` (ledger 363 to 376) recurses up to the number of equations; make it iterative, the call runs on a Java worker's stack.
9. **Memory**: Rust aborts the process on a failed allocation. Reserve the large buffers fallibly and return status 5.
10. **Panics** must not cross the boundary: `catch_unwind` in every export, `panic = "unwind"` in the profile.
11. **No critical arrays** and no JNI call while computing; check `ExceptionCheck` after each region copy.
12. **Double keys**: `HeatSubdivisions` keys a map by `Double` (calculator 2482). Port as raw bits.
13. **Strings**: Rust formats nothing a player or a test reads. `Double.toString` and Rust's float formatting differ.
14. **The catalog is thread context** (`MaterialRuntime.pinned`, `V3WaterProperties` 38 to 39). Build the package block inside that scope and pass it; the Rust side has no catalog.
15. **A global switch in the kernel**: `PengRobinsonKernel.fillOutsideSupportWithNaN` (387) is a mutable static that changes outputs. The Rust kernel takes it as a field of the package block.
16. **Targets that cannot be run here.** The self-test covers the math and one kernel and one linear solve, not the ladder. A target ships as verified only after the parity gate ran on real hardware once; until then the owner chooses between shipping it guarded by the self-test and the Java audit, or leaving it on the Java path.
17. **Licences**: fdlibm's notice travels with the transliterated files; `jni-sys` is MIT or Apache-2.0; nothing else is linked.
18. **Agent rules**: `tools/agent-guard/guard.js` knows Gradle and git, not cargo. A cargo build started outside Gradle is a third build on the machine; the guard needs a line for it, and AGENTS.md a sentence.

## 11. Decisions the owner has to make before stage (a)

1. Stage 0 as written: which of D1, D2, D3, D6, S4 and which of P1, L1, L2 land before the port.
2. The Java audit as the publication gate on the Rust path (choice 3 at the top). It costs one residual evaluation and the audit's flashes per solve, and it means the Rust audit is used only for rung decisions.
3. Cross targets: local `cargo-zigbuild` (installs), a hosted build matrix, or Windows only at first with the other platforms on the Java path.
4. Unverified targets (pitfall 16): ship guarded, or hold back.
5. Whether the contract maximum (64 trays with 64 components, a 53 MB workspace) stays.
