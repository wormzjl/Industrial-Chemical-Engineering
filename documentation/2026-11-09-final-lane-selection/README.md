# Final lane: affected tests only

Status: **In progress since 2026-11-09**. Branch `claude/pre-merge-final-lane-optimization-f1e5d1`, cut from `main` `6181a0f79` (0.23.2). Tools and `AGENTS.md` only: a documentation-only merge.

## Scope

Owner, 2026-11-09: "Make the pre-merge final lane only test affected components as well, right now it's taking way too much time even on trial tasks."

## Expected behaviour

A final lane proves that the batch's changes break nothing. A test whose code and data the batch did not touch runs the same instructions on the same inputs as in its last pass, so it can only repeat that pass. The lane should run the tests that load or read something the batch changed, and nothing else.

## What the lane did

`lane.sh head` ran `test slowGates` whole on every batch. Gradle skipped only the two gate groups (`columnGates`, `equipmentGates`), whose classpaths hold only what their tests load; `test` and `holdGates` see the whole mod, so any change to `src/main` reran all of them. The LOD rendering lane (0.23.2) changed client renderers, `ClientConfig` and `InstrumentGeometry`, and still ran 2983 tests in 654 s: `UtilityRegulationTest` 175 s, `FlowsheetColumnTest` 171 s, `ColumnSizingSampleTest` 95 s, `ColumnSolveTest` 84 s, `ProcessUnitTest` 59 s, `SimulatorRunTest` 55 s.

## Change

- Every test run of the lane records the classes each test class's JVM loads (`tools/merge-lane/record.init.gradle`: one JVM per test class, `-Xlog:class+load`). `affected.mjs save` keeps the load set of each class that passed, with its commit, in a load map shared by the worktrees (main checkout, `tools/merge-lane/logs/load-map.json.gz`, git-ignored).
- `affected.mjs select` runs a test class when its load set (or its own source) holds a file changed since the load set's commit, when a loaded class names a changed constant holder or reads a changed resource, when it has no load set on this line of commits, or when a build file changed. The rules and the safety argument are in `tools/merge-lane/README.md`.
- chain-100 runs when `FluidSolverRegressionTest` is selected; the fluid GameTests when a non-client main source, a GameTest source or server data changed since main. The campaign rule is unchanged.
- `compare.mjs` reports the junction lines as standing from the baseline when the head lane did not run them.
- `AGENTS.md`: the final lane runs the affected tests; `--all` only when the owner asks; a reference whose test the lane skips stands from the baseline.
- The test tasks run side by side with 4 forks each and at most 8 test JVMs at once (`-PtestForks=4 --max-workers=8`; owner: "try run tests in parallel whenever possible").
- Tried first and dropped: a static closure over bytecode references. Every class reaches the `@Mod` class `IChemE`, which names the client classes, so the LOD change selected 240 of 331 quick-suite classes and every hold gate. A first constants rule (any file naming a changed holder) was as wide; it now needs the constant's initializer to change and the user to name `Holder.NAME`.

## History

- 2026-11-09: selector, recorder and lane written. Bootstrap: `lane.sh head test regression` on `6181a0f79`, one fork, recorded: 2983 tests, 0 failures, 770 s (the unrecorded 0.23.2 lane: 654 s), chain-100 exact identical. Windows reused process ids, so 15 load sets were first lost to log rotation; the log names now carry the time, and the 451 load sets are in the map.
- 2026-11-09: replays on today's load sets (`affected.mjs select --since A..B`): LOD rendering (0.23.2) 5 classes, 28 s of test time (was 2983 tests, 654 s); main gate fixes (0.23.1) 67 classes, 722 test-s, equipment code that the sizing gates load; the race fix (0.23.3) 1 class; refinery (0.23.0) everything (`build.gradle` changed).

## Documents

- This README.

## Code areas

- `tools/merge-lane/` (`lane.sh`, `affected.mjs`, `record.init.gradle`, `readers.txt`, `compare.mjs`, `README.md`), `AGENTS.md`.

## Open items

- The parallel full run (4 forks, 8 workers, recorded) had not finished when the merge was prepared: its wall time and whether wall-clock budget tests hold under parallel load are open. The first real lane after the merge shows both.
- No final lane for this batch: it changes only `tools/`, `documentation/` and `AGENTS.md`, which select no test.
