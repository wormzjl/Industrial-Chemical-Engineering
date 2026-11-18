# Rust column library archived (2026-11-17)

**Status:** In progress since 2026-11-17, on branch `claude/rust-archive-reflux-water-762f04` (cut from
`claude/column-testing-sizing-26b5b9`). Class: TOOLING. Lane: column.

The owner (2026-11-17): "ditch and archive rust solver". The Rust column library `icheme_column` (a band LU through
JNI, built in batch `2026-11-13-codebase-review`, stage a, ceiling `-Dicheme.native.column` default `off`) leaves the
build. The column runs on the Java body of `V3BandedPivotedSolver` alone, which was already the reference and the
default path, so no result moves.

## What was removed

- `src/main/rust/` (the crates `icheme-math` and `icheme-jni`, the vendored `jni-sys`) and the classes
  `NativeColumnSolver`, `NativeColumnSolverTest`, `RustNativeTables`, `RustBitTables`: moved to
  [`tools/rust-column-archive/`](../../tools/rust-column-archive/README.md), whose README says how to restore them.
- `build.gradle`: cargo discovery, `rustCheck` (`rustFmtCheck`, `rustClippy`, `rustTest`), `rustBitTables`,
  `rustBandTable`, `rustTables`, `nativeBuild`, `nativeResources`, `nativeSmoke`, the `natives/**` normalization and
  the two system properties; saved in the archive's `native.gradle`.
- `V3BandedPivotedSolver`: its native branch and `librarySolve`.
- `rules/native.md` (now the archive's `native-rules.md`), the `AGENTS.md` section "Native code" and `nativeSmoke` in
  its test tasks, `COLLABORATION.md` section 8 and the column lane's Rust crates, the "Native library" rows of
  `FORMATS.md` and their `FormatsTableTest` exception, the `.gitignore` line of the Rust tables, the native task in
  `tools/merge-lane/` (`affected.mjs`, `lane.sh`, `readers.txt`), the code guide's lines, and the library's mentions
  in `tools/codebase-review-bench/`, `tools/simulator-bench/` and `tools/column-operation/probes/`.

## History

- 2026-11-17: `b6a8a4993` (work in progress): the files move to the archive, the build, the lane tools, the reference
  documents and the code guide stop naming the library. `compileJava` and `compileTestJava` pass.
- 2026-11-17: the archive's README, this folder and its index rows; the last live mentions in `tools/` and the code
  guide's README (`6bd2cdc83`). `node tools/agent-guard/guard.test.js`: ok, 38 blocked, 35 allowed.
- 2026-11-17: the tests the archive can reach, `affected.mjs select --since 24e9867b7..HEAD` run as the lane's test step builds
  them (`-PlaneTests`, `left-out.txt` honoured, `--max-workers=4`): `test` 36 classes, `holdGates` 5, `columnGates` 68,
  `equipmentGates` 1; 706 tests, 0 failed, 0 skipped. No reference moved. Not run: the GameTest step (due on
  `NativeColumnSolver`'s removal; the final lane's), the solver regression and the campaign (nothing they load changed).

## Open items

- `tools/agent-guard/` keeps its cargo rule and `nativeSmoke` in its filter list (hook files, left as they were); both
  are inert. Remove them in a change of the guard if the owner wants.
- History documents (batch folders, `CHANGELOG.md`, `code-guide/HISTORY.md`, the history lines of tool READMEs) keep
  their mentions of the library as written.
