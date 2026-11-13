# Rust part 2a: the band LU and the JNI crate (report)

Branch `claude/review-rust-a1-math-5ced47`, 2026-11-13, after merging `refs/heads/claude/codebase-review-refactor-8a85ef`
(3c8c0dcd5). No Gradle, no file in `src/main/java`, no `build.gradle` change.

## Summary

1. **Band LU.** `icheme-math/src/banded.rs` ports `V3BandedMatrix` and the wave-a1 `V3BandedPivotedSolver`
   (`refs/heads/claude/review-wave-a1-column-b4eca7` at a5fc383e8; last solver change 0e17ff0ec, P8 and P9):
   storage, `dgbtrf` work rows, row and column scaling, the P8 extent and the `-0.0` full-width path, the P9 split
   loop, back substitution, backward error, the P8 counters. Java's `max`/`min`/`abs` are `java::`'s; `copy_from`
   turns `-0.0` into `+0.0`; `kl` and `ku` are the caller's. Java's exceptions are values: argument refusals are
   `BandError::InvalidInput`, the two `IllegalStateException`s are outcomes 6 and 7, buffers are reserved fallibly.
2. **Proof of the band LU.** `RustNativeTables.java` (test tree, `javac`/`java` of JDK 21.0.11) writes
   `banded.txt`: 1 570 cases, 604 kB. A row names its case (kind, seed, parameters); `tests/banded_tables.rs`
   rebuilds it with the twin of the Java generator, checks shape and input fingerprint, solves, and compares every
   output. Result: all 1 570 bit-equal, 22 965 solution values; outcomes 0 to 7 and refused input:
   1 142, 21, 38, 226, 98, 4, 1, 0, 40. Outcome 7 (lost back-substitution pivot) cannot occur: the diagonal it reads
   is a pivot already accepted above 1e-13 and never written again.
3. **The two Java versions agree.** The same generator compiled against the review branch's solver (before P8/P9)
   gives the same outcome, swaps, evidence and solution bits on all 1 570 cases; only the P8 counters are new.
   61 cases took the `-0.0` full-width path. No bit can differ between the versions on these inputs.
4. **Shapes.** `kl` and `ku` as `V3BlockJacobian.toBandedMatrix` computes them (56 to 70): the largest
   row - column and column - row over the non-zero entries of the lower, diagonal and upper blocks. Node blocks of
   `T + water + 2C` unknowns (`V3StageBlockLayout`); full blocks give `kl, ku` up to `b(n-1) + b(n) - 1`. The tables
   use tri-block systems with variable blocks of 3 to 12 (150 cases) and 41 by 5 and 42 nodes (the dry
   20-component column, U up to 1 722), 21 by 20, 129 by 4 and 8 (64 components, kl and ku up to 257).
5. **JNI crate.** `icheme-jni` builds `icheme_column.dll` (266 kB) with exactly four exports:
   `nativeAbi`, `nativeBandedSolve`, `nativeFormulation`, `nativeSelfTest`. Its imports are `KERNEL32.dll`,
   `ntdll.dll`, `api-ms-win-core-synch-l1-2-0.dll`: no VC runtime (`+crt-static` in `.cargo/config.toml`).
   Each export runs in `catch_unwind` (status 6), checks FTZ and DAZ (7), every length and null (3), reserves
   fallibly (5), copies by region and clears a pending exception after each copy (3). `unsafe` only here, every
   block with a `SAFETY` note (`undocumented_unsafe_blocks = deny`).
6. **Choices.** ABI = 1. The formulation token is compiled into the source, not passed at build time (a build-time
   token from Gradle would always match and catch nothing): `FORMULATION_REVISION;WET_ASSUMPTIONS_REVISION;
   HEAT_ASSUMPTIONS_REVISION`, compared as bytes; a test reads `V3ColumnCalculator.java` and fails when the Java
   constants move. Self-test: 256 slots (110 filled, 128..255 reserved for the kernel); `self_test.txt` from Java
   matches all 256.
7. **Proof without Java, and one scratch JVM check.** `cargo test --locked --offline`: 23 passed (debug and
   release); clippy `-D warnings` and fmt clean. Beyond the brief, a throwaway class in `tmp/rust-a2/jni-smoke/`
   loaded the DLL in JDK 21: ABI 1, the token, 256 of 256 self-test slots, 226 band solves bit-equal to
   `V3BandedPivotedSolver`, status 3 for a wrong band length, a null rhs, short counts, a negative kl, a NaN entry.
   Not exercised through JNI: statuses 5, 6, 7.
8. **Vendored.** `jni-sys` 0.3.0 from crates.io (MIT or Apache-2.0, no dependencies; 0.4.1 pulls a proc-macro
   crate), checksum `8eaf4bc02d17cbdd7ff4c7438cafcdf7fb9a4613313ad11b4f8fefe7d3fa0130` in `Cargo.lock`, in
   `src/main/rust/vendor/jni-sys-0.3.0` (77 kB).
9. **Tables shrunk.** Part 1's random rows are 5 000 per table, every special and boundary family kept: 6.07 MB
   (from 17.1 MB), plus `banded.txt` 0.60 MB. The fma table stays 1.3 MB (its 21^3 special cube). Regenerated with
   the same seed; all 15 tables bit-equal in both profiles (38 NaN rows differ only in payload, option A).
10. **Part 2b** is below, file by file. `LAYOUT.md` holds the ABI and the band call's layout.

## Files

| File | Lines | What |
|---|---|---|
| `icheme-math/src/banded.rs` | 694 | `V3BandedMatrix`, `V3BandedPivotedSolver` (wave-a1) |
| `icheme-math/tests/banded_tables.rs` | 387 | the case-generator twin and the table test, plus a workspace-reuse test |
| `icheme-math/tests/data/banded.txt` | 1 575 | 1 570 cases |
| `icheme-jni/src/{lib,env,layout,self_test}.rs` | 156, 101, 212, 139 | exports, `JNIEnv` calls, pure layout and packing, known answers |
| `icheme-jni/tests/data/self_test.txt` | 258 | Java's 256 known answers |
| `src/main/rust/LAYOUT.md` | 78 | ABI 1, status codes, the four calls |
| `src/main/rust/{Cargo.toml,.cargo/config.toml,README.md}` | 24, 16, 77 | second member, vendored source, static CRT, README |
| `src/test/java/.../column/v3/linalg/RustNativeTables.java` | 464 | the generator, `selfTestValues()` |
| `src/test/java/.../science/math/RustBitTables.java` | 843 | `RANDOM_ROWS = 5 000` |

## Part 2b, file by file (after wave-a1's column batch is merged)

| File | Change |
|---|---|
| `NativeColumnSolver.java` | `public final class`. `LIBRARY = "icheme_column"`, `ABI = 1`, `FORMULATION = V3ColumnCalculator.FORMULATION_REVISION + ";" + WET_ASSUMPTIONS_REVISION + ";" + HEAT_ASSUMPTIONS_REVISION`. Delete `VERSION` (line 22) and lines 29 to 88: `thermoBatchEnabled`, `version`, `smokeSolve`, `solve`, both `pengRobinsonBatch*`, `ResidualEvaluator`, `SolveResult` and their five natives; `available()` stays. Declare `private static native int nativeAbi()`, `String nativeFormulation()`, `int nativeSelfTest(double[] out)`, `int nativeBandedSolve(int size, int kl, int ku, double[] band, double[] rhs, double[] solution, double[] evidence, int[] counts)`. Move `RustNativeTables.selfTestValues()` here as `static double[] expectedSelfTest()` (the generator then calls it). |
| loader (`load`, `bundledLibrary`) | After `System.load`: (1) `nativeAbi() == ABI`; (2) `FORMULATION.equals(nativeFormulation())`; (3) `nativeSelfTest(out)` returns 0 and slots 0..127 have the raw bits of `expectedSelfTest()`; any failure logs one line with the reason and returns false. The game-folder copy is `System.mapLibraryName(LIBRARY + "-" + ABI)` (the jar entry stays `natives/<os>-<arch>/` + `mapLibraryName(LIBRARY)`). Log "Rust column library ABI 1 loaded from ...". |
| ceiling | `-Dicheme.native.column=off|batch|kernel|attempt|solve`; `public static boolean bandedSolveEnabled()` = loaded and the ceiling at least `kernel`. A returned status 6 sets a static flag that turns the library off for the session; 3, 5, 7 log once. The default ceiling is the main thread's call (`kernel` once `V3RustKernelBitsTest`'s band part passes). |
| `public static int bandedSolve(...)` | calls `nativeBandedSolve` with the eight arguments unchanged, applies the status rules above, returns the status. |
| `linalg/V3BandedMatrix.java` | `double[] storage() { return values; }` (package-private; the call region-copies it, no clone). |
| `linalg/V3BandedPivotedSolver.java` | In `solve(matrix, rhs, workspace)`, after `copiedFiniteRightHandSide` and the workspace null check: if `NativeColumnSolver.bandedSolveEnabled()`, call it with `new double[size]`, `new double[4]`, `new int[7]`; on status 0 add `counts[2]` to `fullWidthSolves`, `((long) counts[4] << 32) | (counts[3] & 0xffffffffL)` to `updatedEntries` and the same of `counts[6]`, `counts[5]` to `fullWidthEntries`, and return or throw per the outcome table of `LAYOUT.md`; on any other status fall through to the Java body. |
| `V3MeshResidualEvaluator.java` | delete `captureNativeThermoSnapshot` (82 to 139) and its two lines in `captureThermoSnapshot` (61, 62): `nativePrBatch` is not ported. |
| `NativeColumnSolverTest.java` | rewrite: loaded iff packaged or `icheme.native.path`; ABI, token, self-test; the band solve's every output bit against the Java body on random and column-shaped systems (`RustNativeTables.build` gives them); status 3 for a wrong length, a null array, a negative bandwidth. Delete the PR batch and C++ outer-pass tests (`separatingColumnPublishesTheSameProductsWithEitherOuterPass` compares the ceiling `off` and `kernel` instead, products equal). |
| `build.gradle` 330 to 333 | drop `nativeThermoBatch`; add `-PnativeColumn` to `icheme.native.column`. |
| `build.gradle` 336 to 425 | replace the CMake block: `cargo` from `-Pcargo`, the PATH, or `~/.cargo/bin`. `rustCheck` (Exec, workingDir `src/main/rust`): `cargo fmt --check`, `cargo clippy --locked --offline --all-targets -- -D warnings`, `cargo test --locked --offline`; inputs `src/main/rust/**`. `nativeBuild` (Exec, workingDir `src/main/rust`): `cargo build --release --locked --offline -p icheme-jni`; inputs `src/main/rust/**` except `**/tests/**`; output `build/native/cargo/release/` + `mapLibraryName('icheme_column')`. `nativeResources` (Sync) from `build/native/cargo/release` into `build/generated/nativeResources/natives/<platform>/`. No cargo: lifecycle "No cargo found: the jar carries no column library, and the column runs on its Java path." `nativeSmoke`: `icheme.native.path` = `build/native/cargo/release`; delete its `.exe` scan (cargo's test harnesses live in `build/native/cargo/*/deps/`; the cdylib build makes no executable). `icheme.native.packaged` = cargo found. |
| `build.gradle` 527 | delete; `nativeSmoke` and the test task that runs `V3RustKernelBitsTest` declare `inputs.file(<built library>)`. |
| `src/main/cpp` | delete (5 files, 742 lines); `src/main/rust/README.md` replaces its README. |
| `tools/agent-guard/guard.js` | add the rule below to `RULES`, and cases to `guard.test.js` (blocked: `cargo build -j 8`, `cargo build --jobs=16`; allowed: `cargo test`, `cargo test -j 4`, `cargo build --jobs 2`; checked against the regex in node here). |
| `AGENTS.md` "Native code" | "Run cargo from `src/main/rust`: its config sets 4 jobs, the target folder and the vendored sources; a cargo build or test counts as one of the two Gradle lanes." (owner's file: the main thread's call) |
| code guide `column-v3.md` 78, 251 | `NativeColumnSolver` row: the Rust library, the band solve under the ceiling; the "C++ column library" row becomes "Rust column library: `src/main/rust`, `LAYOUT.md`, `nativeBuild`". |

The agent-guard rule (pitfall 18 of the plan):

```js
  [/\bcargo\b.*(?:\s-j\s*(?:[5-9]|\d{2,})\b|--jobs[=\s]+(?:[5-9]|\d{2,})\b)/, 'a cargo run with more than 4 jobs',
    'run cargo from src/main/rust, whose .cargo/config.toml sets 4 jobs; a cargo build counts as a Gradle lane'],
```
