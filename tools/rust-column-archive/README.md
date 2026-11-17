# rust-column-archive

Purpose: the Rust column library `icheme_column`, archived. No build, test or lane reads this folder.

Batch: `documentation/2026-11-17-rust-solver-archive/` (archive). The library was built in batch
`documentation/2026-11-13-codebase-review/` (stage a of `RUST_COLUMN_PORT_PLAN.md`).

## What the library was

A port of the V3 column solver's band LU to Rust, called through JNI, with the Java path as the reference and the
fallback. Every ported function gave Java's bits exactly; a difference was a port defect, not a tolerance.

- `icheme-math` (no dependency, no `unsafe`): `DeterministicMath` (fdlibm 5.3 transliterated, `DeterministicPow` and
  its tables, a software `Math.fma`), the Java helpers it needs, and `banded` (`V3BandedMatrix`,
  `V3BandedPivotedSolver` operation for operation).
- `icheme-jni`: the `cdylib` `icheme_column` (`icheme_column.dll`, `libicheme_column.so`,
  `libicheme_column.dylib`) with four exports, `nativeAbi`, `nativeFormulation`, `nativeSelfTest` and
  `nativeBandedSolve`, each behind `catch_unwind`, a floating-point environment check and length checks. The layout,
  ABI 1 and the status codes are in [rust/LAYOUT.md](rust/LAYOUT.md); the build and tests in
  [rust/README.md](rust/README.md). One dependency, `jni-sys` 0.3.0, vendored in `rust/vendor/`.
- Its ceiling: `-Dicheme.native.column=off|band` (Gradle `-PnativeColumn=`), default `off`, where the library was
  not even loaded. At `band` every `V3BandedPivotedSolver` solve went to `nativeBandedSolve`; a status other than 0 ran
  that solve on Java, a panic turned the library off for the session. `-Dicheme.native.path` (Gradle
  `-PnativeLibraryPath=`) loaded another build.

The owner archived it on 2026-11-17: "ditch and archive rust solver". The column runs on the Java body of
`V3BandedPivotedSolver` alone, which was already the reference and the default path.

## What lies where

| Path | Was at | Contents |
|---|---|---|
| `rust/` | `src/main/rust/` | The Cargo workspace: `icheme-math`, `icheme-jni`, `vendor/`, `Cargo.lock`, the toolchain pin (Rust 1.99.1), `.cargo/config.toml` (4 jobs, vendored crates, static C runtime), `LAYOUT.md`, `README.md` |
| `java/NativeColumnSolver.java` | `src/main/java/com/wormzjl/icheme/science/column/v3/` | The loader, the handshake (ABI, formulation token, self-test) and the ceiling |
| `java/NativeColumnSolverTest.java` | `src/test/java/com/wormzjl/icheme/science/column/v3/` | The handshake, the band solve bit for bit on the 1 570 cases of `banded.txt`, a column with the ceiling off and at band |
| `java/RustNativeTables.java` | `src/test/java/com/wormzjl/icheme/science/column/v3/linalg/` | Writes the band LU table and the self-test vector the Rust tests read |
| `java/RustBitTables.java` | `src/test/java/com/wormzjl/icheme/science/math/` | Writes the bit tables of the mathematics the Rust tests read |
| `native.gradle` | `build.gradle` | The removed blocks, each headed by a comment naming where it stood; not applied by any build |
| `native-rules.md` | `documentation/reference/rules/native.md` | The rules of native work |

## How to restore

1. Move the files back with `git mv`: `rust/` to `src/main/rust/`, and each file of `java/` to the folder in its
   "Was at" cell above.
2. Put the blocks of `native.gradle` back into `build.gradle` at the places their `// ----` comments name: the two
   system properties inside `tasks.withType(Test).configureEach`; cargo, `rustCheck`, the tables, `nativeBuild` and
   `nativeResources` after that block; the `natives/**` normalization after the `fluidSolverRegression` task;
   `'natives/**'` back in the column gate group's resource exclusions; `nativeTestClasses`, the `columnGates`
   exclusion and `nativeSmoke` before `slowGates`; `if (nativeCargo != null) dependsOn nativeSmoke` in `slowGates`.
3. Give `V3BandedPivotedSolver` (`science/column/v3/linalg`) back its native branch and `librarySolve` (removed in
   commit `b6a8a4993`; `git show b6a8a4993 -- '*V3BandedPivotedSolver.java'`).
4. `.gitignore`: the line `/src/main/rust/icheme-math/tests/data/*.txt` with its comment.
5. `documentation/reference/FORMATS.md`: the "Native library" section with its rows "Native column ABI" and "Native
   formulation token"; `FormatsTableTest`'s `DESCRIBED` set gets "Native formulation token" back.
6. `tools/merge-lane/`: in `affected.mjs` the `nativeTestClasses` reader, `nativeSmoke` in `taskOf` and in the task
   list; in `lane.sh` `have_cargo` and the `nativeSmoke` lines of the `test` step; in `readers.txt` the line
   `^src/main/rust/ src/test/java/com/wormzjl/icheme/science/column/v3/NativeColumnSolverTest.java`; the README.
7. `AGENTS.md`: the section "Native code" (before "Merging to main") and `nativeSmoke` in the list of filtered test
   tasks; `native-rules.md` back to `documentation/reference/rules/native.md`; section 8 of
   `documentation/reference/COLLABORATION.md` and the column lane's "the Rust column crates"; the code guide's lines
   in `column-v3-solver.md`, `column-v3.md`, `verification.md`, `verification-tests.md` and `README.md`.

   Commit `b6a8a4993` and this batch's later commits hold every removed line (`git show <commit> -- <path>`).
8. Before any use: the Java side has moved on since the archive. Bring the port to the current Java body (the anchor
   tool, the parity tables, `rustCheck`, `nativeSmoke`) before the ceiling leaves `off`.

## Not archived

`tools/agent-guard/` keeps its cargo rule (a cargo build outside `src/main/rust` or with more than 4 jobs) and
`nativeSmoke` in its list of filtered test tasks. They are hook files, left as they were; with no `src/main/rust` and no
`nativeSmoke` task both are inert.

## History

- 2026-11-17: archived (batch `2026-11-17-rust-solver-archive`).
