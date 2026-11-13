# Rust part 2b: the Java side, the Gradle tasks, the C++ lane removed (report)

Branch `worktree-agent-a9b498429fe2f9b1b`, 2026-11-13. The worktree started at `main` (259d22a2c); it was
fast-forwarded to the review tip `claude/codebase-review-refactor-8a85ef` (a0b157325) before any edit. Commits
c0b3ad61c (Java loader, hook, tests), 210c94504 (build.gradle, `src/main/cpp` deleted, two tool lines), 7f966e7e1
(agent guard), and the documents commit that holds this file. Nothing was compiled or run (no Gradle, cargo or
JVM: a timed lane runs on the machine); only `node tools/agent-guard/guard.test.js` ran: "ok: 24 blocked, 23 allowed".

## Files

| File | Change |
|---|---|
| `science/column/v3/NativeColumnSolver.java` | public; `icheme_column`, `ABI = 1`, `FORMULATION`; handshake (ABI, token, slots 0..127 raw bits of `expectedSelfTest`, moved here); copy `icheme_column-1.dll`; ceiling `icheme.native.column=off|band` (default off: not loaded; other names: off + one log line); `bandedSolveEnabled`, `bandedSolve` (6 switches off for the session, volatile; 3/5/7/other log once); `ceilingForTest`, `bandSolves`, `loadReport`; `version()` kept as a one-line description (SimulatorBench calls it) |
| `linalg/V3BandedPivotedSolver.java` | the hook after the workspace check plus `librarySolve` (outcome table of LAYOUT.md, P8 counters); the Java body is untouched |
| `linalg/V3BandedMatrix.java` | package-private `storage()` |
| `V3MeshResidualEvaluator.java` | `captureNativeThermoSnapshot`, `nativePhase`, `validNativeDerivativeBatch`, `ThermoSnapshot.nativePhase` removed |
| `thermo/V3PengRobinsonThermo.java`, `V3PengRobinsonSession.java` | `constantClassicalInteractions` and the session's `constantMatrix` removed (only the C++ pass read them) |
| `test/.../NativeColumnSolverTest.java` | rewritten: 4 tests, each skips with no library (fails if `icheme.native.packaged` or `icheme.native.path` says one should load) |
| `test/.../linalg/RustNativeTables.java` | `Spec`, `specs()`, public `Case`; calls `NativeColumnSolver.expectedSelfTest()`; run comment: on the Gradle class output |
| `build.gradle` | cargo lookup, `nativeBuild`, `nativeResources`, `rustCheck` (= `rustFmtCheck`, `rustClippy`, `rustTest`, stamps in `build/native/checks`), `nativeSmoke` (column gate inputs, the built library its input, in `slowGates` when cargo is found), `columnGates` excludes `NativeColumnSolverTest`; `-PnativeColumn`; `-PnativeThermoBatch` and the every-Test native input gone |
| `src/main/cpp/**` | deleted (5 files) |
| `tools/agent-guard/{guard.js,guard.test.js,README.md}` | the cargo rule, with the hook's `cwd` and `cd` tracking |
| `tools/merge-lane/readers.txt` | `^src/main/rust/` -> `NativeColumnSolverTest.java` (was the cpp line) |
| `tools/codebase-review-bench/{run.ps1,README.md}` | the dead `-Dicheme.native.thermo.batch=false` dropped |
| `src/main/rust/{README.md,LAYOUT.md}`, code guide `column-v3.md`, `README.md`, `verification.md`, `FORMATS.md` (new "Native library" rows: ABI, token), `CHANGELOG.md` | documents |

Deleted tests (C++ only): `loadsThePackedLibraryByDefaultAndFallsBackOtherwise`, `nativePengRobinsonPassMatchesTheJavaKernel`, `separatingColumnPublishesTheSameProductsWithEitherOuterPass`. New: `theLibraryPassesItsHandshake`, `theLibrarySolvesEveryTableCaseWithTheJavaBits` (1 570 cases, three ways: Java at off, `bandedSolve` direct, the solver at band; every outcome but 7 reached), `theLibraryRefusesAMalformedCall` (15 status-3 calls, then a good one), `aColumnPublishesTheSameBitsWithTheBandSolveInTheLibrary` (the 4-tray Tia Juana column of the old test: streams by raw bits, ledger, hydraulics, evidence, audit, diagnostics text; asserts the library carried band solves). The ceiling is switched in one JVM by the package-private `NativeColumnSolver.ceilingForTest(Ceiling)`, restored in `finally`.

## Commands for the main thread, in order

1. `cd src/main/rust && cargo test --locked --offline --release`: optional; no Rust source changed (README and LAYOUT only), so part 2a's pass stands.
2. `./gradlew compileJava compileTestJava nativeBuild --max-workers 4`: the Java compiles (nothing was compiled here), the build script configures (configuration cache on), Gradle finds cargo and writes `build/native/cargo/release/icheme_column.dll`.
3. `./gradlew nativeSmoke --max-workers 4`: the 4 native tests on the built library: handshake, band bits, refusals, column off = band. The tests set the ceiling themselves, so `-PnativeColumn` is not needed here.
4. `./gradlew columnGates --tests '*V3BandedPivotedSolverTest' --tests '*V3PivotGrowthTest' --tests '*V3NewtonMatrixTest' --tests '*V3SimultaneousColumnSolverTest' --tests '*V3ColdStartTest' --max-workers 4`: the column classes nearest the change (the hook, the evaluator, the inside-out trial) at the default ceiling off. Must pass with the same references as the baseline lane (see decision 2).
5. The same command with `-PnativeColumn=band`: those classes with every band solve in the library; the same results.
6. `node tools/agent-guard/guard.test.js` (ran here: ok).

The final lane then takes `columnGates` (whole, affected by the evaluator and the solver) and `nativeSmoke`.

## Not verified without running

Compilation of every changed Java file; the build script under the configuration cache; that `cargo fmt --all --check` and clippy run as Gradle Exec tasks from `src/main/rust`; the JNI symbol binding at run time (names checked by eye against `icheme-jni/src/lib.rs`); the Rust evidence values for outcomes 1 and 2 (the test compares only outcome and counters there, as LAYOUT.md maps them to fixed zeros); that the 4-tray column is deterministic run to run in one JVM (no wall clock in `column/v3`: no `nanoTime`/`currentTimeMillis`; it uses `V3SolveControl.UNBOUNDED`).

## Decisions for the main thread

1. **nativeSmoke placement.** The brief puts the native tests "in the gate group they belong to". `NativeColumnSolverTest` sits in `science/column/v3`, so it was in `columnGates`, whose class path ignores `natives/**`; a Rust-only change would leave it up to date. Done: `columnGates` excludes it, `nativeSmoke` runs it on the column gate's inputs with the built library as its declared input, and `slowGates` depends on `nativeSmoke` where cargo is found. Option B: keep it in `columnGates` and give `columnGates` the library as an input (any Rust change reruns the whole column group).
2. **References may move by the C++ removal.** Where the baseline lane's jar carried the C++ library (CMake found), the inside-out trial ran its C++ pass (about 1e-13 relative on published products, `column-v3.md` before this batch). Those results now take the Java path's bits. The CHANGELOG line says so instead of a bare "no result changes". If a recorded column reference moves in the last digits, that is this cause; record old and new in the lane (AGENTS.md "Results that move").
3. **AGENTS.md "Native code"** (owner's file, not edited): the report's sentence "Run cargo from `src/main/rust`: its config sets 4 jobs, the target folder and the vendored sources; a cargo build or test counts as one of the two Gradle lanes."
4. **Guard: plain `cargo test` from the repository root is blocked** (the brief's rule; part 2a's list had it allowed). From `src/main/rust` (session folder or a `cd` in the command) it passes; elsewhere it needs `-j 1..4`.
5. **Batch README and `documentation/INDEX.md`**: no history line added here (the main thread's hot files).
6. **Tools that still name the C++ pass** (left as they are, per the brief): `tools/native-column-default/` (README; `probes/{InsideOut,Crude,Calculator,CalculatorDuty}ProbeTest.java` set `icheme.native.thermo.batch`, now without effect; `probes/Check.java` calls `thermoBatchEnabled` and looks for `icheme_column_solver.dll`: broken), `tools/column-operation/README.md` (`-PnativeThermoBatch=false`) and `probes/ColumnOperationC1BenchTest.java` line 373 (`thermoBatchEnabled()`: does not compile if copied in), `tools/codebase-review-bench/ColumnBench.java` line 100 (prints the property: harmless). Option: archive `tools/native-column-default/` as superseded in the merge-prep tooling cleanup.
