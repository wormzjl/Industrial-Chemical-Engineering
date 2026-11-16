# Verification: Gradle tasks, gate groups and the final lane

Up: [code guide](README.md). Which test proves which rule, the GameTests and the compatibility corpus: [verification-tests.md](verification-tests.md). The exact regression, the junction lines, the hold-fuzz campaigns, the classifier and the determinism checks: [verification-campaigns.md](verification-campaigns.md). The rules that govern every run are AGENTS.md, "Gradle, measurement and verification" (lane limits, no repeated tests, the least measurement); the merge checklist is the `merge-prep` skill ([SKILL.md](../../../.claude/skills/merge-prep/SKILL.md)). This guide names no lane result: results are in each batch's reports.

## Read this when

- You are about to change code and need the Gradle task, the `--tests` filter or the gate group that proves the change.
- A report says "final lane", "lane", "gates", "slow gates", "hold gates", "column gates" or "equipment gates" and you need to know what runs.
- A test class is missing from a gate group (`NoClassDefFoundError` or a missing resource), or `test --tests` finds nothing.
- You prepare a merge and must run or read the final lane (`tools/merge-lane/lane.sh`).
- You need a dev run: a client, the MCP client, a GameTest server, the review world, the paced benchmark.

## What it does

The mod builds with ModDevGradle on a Java 21 toolchain, `mod_version` in [gradle.properties](../../../gradle.properties) as its one version number. Four source sets: `main`, `test` (JUnit 5, with the Minecraft class path so codecs and NBT are tested for real), `fluidGameTest` (server GameTests, review worlds and the paced benchmark, loaded only by their own runs) and `mcpCompat` (the dev client's bridge mod). The everyday `test` task leaves out three things, each with a task of its own: the slow gate classes (`holdGates`), the column and equipment tests (`columnGates`, `equipmentGates`, each on a narrowed copy of what it loads), and `fluid/benchmark/**` (`fluidSolverRegression`). `slowGates` runs the three groups side by side, and `check` depends on it. The **final lane** (`tools/merge-lane/lane.sh head`) runs once per batch on final code: only the test classes the batch's changes can reach (by their recorded load sets), chain-100 exact and the fluid GameTests when what they load changed, and the 1000-seed campaign when one of its inputs changed since `main`.

## Class map

The lane's tool, [`tools/merge-lane/`](../../../tools/merge-lane/). Lines at the snapshot.

| File | Role | Lines |
|---|---|---|
| `lane.sh` | The lane: `lane.sh <base or head> [--all] [steps]`, steps `test regression gametest campaign`; output in `tmp/merge-lane/<side>/` (or `$LANE_OUT`); `$LANE_RUN` a fresh GameTest run id; `LANE_FORKS`, `LANE_WORKERS` | 83 |
| `affected.mjs` | `select [--explain <class>] [--since A..B]` prints the selection (`TASK`, `STEP`, `WHY`, `NOTE` lines); `save <load logs> <results>` updates the load map | 317 |
| `record.init.gradle` | `--init-script … -PlaneRecord=<folder>`: one JVM per test class with `-Xlog:class+load`, so each class's load set is recorded | 15 |
| `readers.txt` | Readers no string constant shows: a path pattern and the reading source file | 4 |
| `campaign-inputs.txt` | The campaign's inputs as path patterns; a change to one since the merge base runs the campaign | 11 |
| `compare.mjs` | `node tools/merge-lane/compare.mjs <base> <head> [<campaign base>]`: campaign holds by class, counters, refusals, new budget-class holds, the junction lines' counters and `MIXED_GAS_COST` | 89 |
| `main-reach.pl` | `perl tools/merge-lane/main-reach.pl .`: the classes of `src/main` the game cannot reach from the mod's entry points | 46 |
| `README.md` | How to run, what each step runs, why the selection is safe | 56 |

The load map is `tools/merge-lane/logs/load-map.json.gz` in the main checkout (git-ignored, shared by every worktree; `$LANE_MAP` overrides it); it keeps the three newest load sets per test class. The tools the lane calls: the hold-fuzz harnesses in `tools/unified-thermo-port-forms/`, the classifier `tools/phase-ports-probes/hold-fuzz/classify.sh`, the printed-line extractor `tools/floating-roof-tanks/final-lane/printed.sh` ([verification-campaigns.md](verification-campaigns.md)).

## Main flow

`bash tools/merge-lane/lane.sh head`, from the branch's worktree, no dev client running:

1. **test**: compiles (`testClasses compileFluidGameTestJava`), asks `affected.mjs select` which classes can be affected, and runs them task by task (`test`, `holdGates`, `columnGates`, `equipmentGates`) with `--tests` filters, side by side with `-PtestForks` and `--max-workers`, recording load sets; copies the JUnit XML to `results/`, sums the counts, writes `printed.txt` and, when the junction tests ran, `junction.txt`; saves the load sets of the classes that passed.
2. **regression**: `fluidSolverRegression -PfluidRegressionMode=exact` when `FluidSolverRegressionTest` is selected.
3. **gametest**: `runFluidGameTestServer` on a fresh run id when a non-client main source, a GameTest source, `data/`, `META-INF/` or the templates changed since `main` (by path: the server loads the whole server side).
4. **campaign**: cold random, then bundled random and defaults, seeds 1 to 1000 on 8 threads with counters, each classified, when a path of `campaign-inputs.txt` differs from the merge base.
5. Read it: `selection.txt` says why each class ran; `compare.mjs` against the baseline lane (an archived lane of the same code on `main` serves; `lane.sh base` makes one in a scratch worktree).

`--all` runs every step whole; a step list runs only those steps.

## Contracts

- **Selection** (`affected.mjs`, its README): a test class runs when it has no load set whose commit is an ancestor of HEAD, when its source or a file of its load set changed, when a loaded file uses a compile-time constant whose initializer changed, when a loaded file reads a changed resource (by a string constant, its package, or `readers.txt`), or when a build file other than `build.gradle` and `gradle.properties` changed (`settings.gradle`, `gradle/`, the wrapper, `tools/development.gradle`) or a test resource no class names changed: then every class runs. A failed class saves no load set; uncommitted changes to `src/` or the build files block the save. Changes to `documentation/`, `tools/`, `research/`, root `*.md` and `.claude/` run nothing.
- **Gate groups** (`gateGroup` in `build.gradle`): `columnGates` runs `science.column.v3.*`, `equipmentGates` `science.equipment.*`, each on a copy of only the classes and resources its tests load (`build/gate-inputs/<name>`), so a change anywhere else leaves the task up to date. A class or resource left out of a group's list fails loudly; add it to the list. Both run with a 1 GiB heap; `columnGates` leaves `NativeColumnSolverTest` to `nativeSmoke`.
- **Slow gate classes** (`slowGateClasses` in `build.gradle`): classes of 3 s or more, which `test` excludes and `holdGates` runs; a missing class fails `holdGates`. A class with `@Nested` groups is listed with `$*` too.
- **Scheduler verification**: `test`, `holdGates`, `fluidRuntimeTest` and the fluid GameTest server set `icheme.fluid.scheduler.verify`, which re-derives each island's schedule and fails on drift.
- **The lane rules** (AGENTS.md): at most two Gradle invocations at once, each in its own worktree; a timing run alone; no test suite while a dev client runs; one final lane per batch, on final code; no second final lane; a run on the second lane uses at most 4 workers.
- **Never force a rerun** (`--rerun-tasks`, `--no-build-cache`, `cleanTest`, a deleted `build/`): an up-to-date or restored task counts as run. The one exception is a rule that asks for a replay, with `--rerun` on that filtered task.

## Laws and constants

| Task | What it runs | Use |
|---|---|---|
| `test` | `src/test` but `**/fluid/benchmark/**`, `science/column/v3/**`, `science/equipment/**` and the slow gate classes; depends on `compileFluidGameTestJava` and on `jar` (`-Dfluid.modJar`, read by `PackagedSparseLuTest`) | `--tests` for what a change reaches |
| `holdGates` | The slow gate classes | `--tests` on the ones a change reaches |
| `columnGates`, `equipmentGates` | The column and equipment tests on their narrowed inputs | `columnGates --tests …` |
| `slowGates` | `holdGates`, `columnGates`, `equipmentGates` (and `nativeSmoke` where cargo is found) side by side; `check` depends on it | The lane, or `lane.sh head --all` on the owner's word |
| `fluidScienceTest`, `fluidRuntimeTest` | `science.fluid.*`; `runtime.fluid.*` and `runtime.Fluid*Test`; fail when nothing matches | A focused fluid run |
| `fluidSolverRegression` | `FluidSolverRegressionTest` (chain-100), one fork, never up to date; `-PfluidRegressionMode=declared\|relative\|exact`, `-PfluidRegressionCapture`, `-PfluidRegressionReferences`, `-PfluidRegressionTraceCutoff` | Any solver change ([verification-campaigns.md](verification-campaigns.md)) |
| `v3HollandBenchmark` | `HollandExample32BenchmarkTest`, report `build/reports/benchmarks/v3-holland-example-3-2.json` (`-Pv3HollandReport`) | Column accuracy claims |
| `nativeSmoke`, `rustCheck` (`rustFmtCheck`, `rustClippy`, `rustTest`), `nativeBuild`, `nativeResources` | The Rust column library's JNI tests on the built library; fmt, clippy and the Rust tests; the library build and its place in the jar (`natives/<os>-<arch>/`) | `-Pcargo`; `-PnativeColumn` sets the ceiling for test JVMs, `-PnativeLibraryPath` the library ([column-v3.md](column-v3.md)) |
| `runFluidGameTestServer` | Namespaces `icheme_fluid_test` and `icheme_dense_test` in `run/fluid-gametest[-<fluidGameTestRunId>]`; `-PfluidGameTestNamespaces=icheme_dense_test` runs one namespace alone (a test belongs to its template's namespace); `-PfluidGameTestDeliberateFailure` proves a failure is reported | A runtime, block, equipment-load or GUI-server change; a fresh run id |
| `runColumnGameTestServer` | Namespace `icheme_column_test` (`ProcessSimulatorGameTests`) in `run/column-gametest-<columnGameTestRunId>` | The simulator's block |
| `runReviewWorldServer` | Namespace `icheme_review` in `run/review-world`: review plants left standing; `-PreviewBatch` runs one batch (`ReviewBatch`) | A hand review; not a gate |
| `fluidServerBenchmark` | `prepareFluidBenchmark`, `runFluidBenchmarkServer`: `FluidServerBenchmark` in `run/fluid-benchmark/<fluidBenchmarkRunId>`, refusing an existing world; reports under `build/reports/fluid/M9/<id>/`. `-PfluidBenchmarkProfile` names the fixture (`mixed100`; `mixed400`, four times its ladders, loads the workers fully), `-PfluidBenchmarkWorkers` the worker count (2; 0 is a world's automatic sizing) | Paced cost; runs alone; 60 s warm-up and 60 s measurement |
| `runMcpClient` | The dev client with `mcpCompat` in `run/mcp-client`; `prepareMcpControlMod` copies the pinned bridge jar from `run/mcp-client-mods/`; `-PmcpClientWidth`, `-PmcpClientHeight` | A check the owner asks for ([client-kit.md](client-kit.md)) |
| `runClient`, `runServer` | Ordinary dev runs | Manual play |
| `verifyMaterialIndex` | `materials-index.json` lists each bundled material file once; `processResources` depends on it | Automatic |
| `generateModMetadata` | Expands `neoforge.mods.toml` from the templates | Automatic |

Other switches: `-PtestForks` (forks per test task), `-PwithoutCreate` (the optional mods out of the dev and test runtimes), `-PwithIris`, `-PwithDh` (optional render mods in the dev runs). `tools/development.gradle` holds the offline research tasks (neural data, V3 probes); a fresh clone builds without it.

## Formats

The lane writes `tmp/merge-lane/<side>/`: `selection.txt`, `*.gradle.txt` logs, `results/` (JUnit XML), `printed.txt`, `junction.txt`, `loads-*/`, campaign reports with `.classes.txt`. Logs leave the tree as compressed archives in the main checkout's tool folders (AGENTS.md). Saved and wire formats: [FORMATS.md](../FORMATS.md).

## Tests and gates

Which check decides what, in the least measurement (AGENTS.md):

| Change | Minimum checks |
|---|---|
| Documents, `tools/`, comments | No test run |
| A test removed | `compileTestJava`; the names in the stored results prove the removal |
| A test added or edited | That class, `--tests` |
| Production code | The changed classes' tests and the gates that name the rule, `--tests`, in their task (`test`, a gate group or `holdGates`) |
| Fluid solver forms | The 200-seed quick check per form, at most three forms; one campaign for the chosen one ([verification-campaigns.md](verification-campaigns.md)) |
| A trajectory-identical refactor | chain-100 exact, the junction lines and printed lines unchanged |
| A saved fact, shape field or layout | The compat gates of [verification-tests.md](verification-tests.md) and a corpus image; the fluid GameTests when equipment loading changes |
| GUI | The builders' gates and software renders; a dev-client check only on the owner's request |
| Translations | `LanguageFilesTest` and `UnitTextTest`, after the translation commit |
| A cost claim | A timing run alone |
| A merge | The final lane once, and the `merge-prep` checklist |

## Traps

- A green `test` says nothing about chain-100, the column tests or the equipment tests: they are other tasks.
- `test --tests` on a class of a gate group finds nothing, and with a second `--tests` that matches it silently runs less: name the group (`equipmentGates --tests …`).
- `fluidScienceTest` and `fluidRuntimeTest` do not reach `science.thermo.*`, `science.material.*` or `science.column.*`.
- A `build.gradle` change runs no test by itself in the lane (owner rule in the tool's README); a gate list change shows only when its group runs.
- The scripts pass `--no-configuration-cache` for their init scripts; `gradle.properties` turns the configuration cache, build cache and parallel execution on.
- Under memory load the GameTest server can fail to start; rerun on a new run id. GameTest worlds persist between runs of one id: use a fresh id.
- `lane.sh` warns, and does not stop, when other test or client JVMs run.

**Snapshot:** `cdb2833e1`, 2026-11-15.
