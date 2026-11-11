# merge-lane

Purpose: the one final lane before a merge to `main` (AGENTS.md, "Gradle, measurement and verification"). Batches: [`2026-11-02-test-suite-optimization`](../../documentation/2026-11-02-test-suite-optimization/README.md), [`2026-11-09-final-lane-selection`](../../documentation/2026-11-09-final-lane-selection/README.md). It replaces the copy of `lane.sh` that each batch made, with its fixed worktree paths.

## How to run

From the branch's worktree, with no dev client. At most one other Gradle run may share the machine; then judge the campaign and the cost gates by counters, and replay a wall-clock budget hold alone before you count it:

```bash
bash tools/merge-lane/lane.sh head
```

A step list after the side runs only those steps, for example `lane.sh head campaign`. `--all` runs every step whole, whatever changed. `base` runs `test slowGates` whole and the campaign on main, in a scratch worktree under the main checkout's `.claude/worktrees/`. An archived baseline of the same code serves: then run only `head`.

## What it runs

- `test`: compiles, asks `affected.mjs select` which test classes the changes can reach, and runs only those, task by task (`test`, `holdGates`, `columnGates`, `equipmentGates`) with `--tests` filters. A task with none is left out. `selection.txt` in the lane folder gives each choice and, on `WHY` lines, its reason.
- `regression`: chain-100 exact, when `FluidSolverRegressionTest` is selected.
- `gametest`: the fluid GameTests, when a non-client main source, a GameTest source, `data/`, `META-INF/` or the templates changed since main. The GameTest server loads the whole server side, so this rule is by path.
- `campaign`: when a path of `campaign-inputs.txt` differs between the branch and its merge base with `main`.

## The selection, and why it is safe

- A test's result can depend only on what its JVM loads and reads. Every test run of the lane records, per test class, the classes its JVM loaded (`record.init.gradle`: one JVM per test class, `-Xlog:class+load`). `affected.mjs save` turns the logs into a load set of source files for each class that passed and stores it in the load map with the commit it ran at.
- The load map is `tools/merge-lane/logs/load-map.json.gz` in the main checkout (git-ignored, shared by every worktree; `$LANE_MAP` overrides it). It keeps the three newest load sets per test class.
- `select` takes, per test class, its newest load set whose commit is an ancestor of HEAD, and lists the files changed between that commit and the working tree. The class runs when one of these holds:
  - it has no such load set (a new class, an empty map, or load sets recorded only on other branches);
  - its own source or a file of its load set changed;
  - a loaded file uses a compile-time constant whose initializer changed (javac copies the value into its users, which then never load the holder): it names `Holder.NAME`, imports it statically, or extends the holder; a constant built from a changed one counts as changed;
  - a loaded file reads a changed resource or source file: a string constant names the file's path or folder, the resource sits in the class's package, or `readers.txt` lists the reader (for paths built from pieces);
  - a build file changed (`settings.gradle`, `gradle/`, the wrapper, `tools/development.gradle`, `gradle.properties` beyond `mod_version`; not `build.gradle`, owner 2026-11-10: its compileOnly dependencies, JVM settings and gate lists do not change what a test computes, and the game and loader versions are in `gradle.properties`), or a test resource changed that no class names: then every class runs.
- If nothing a test class loaded or read changed since its load set was recorded, it runs the same code on the same data, and its last pass stands. A change that would make it load a new class must change a class it loaded.
- A failed class saves no load set (a failure can cut it short), so it runs again next time. Uncommitted changes to `src/` or the build files block the save: HEAD would not be what ran.
- Changes to `documentation/`, `tools/`, `research/`, `*.md` at the root and `.claude/` run nothing (the campaign list covers its own tool folders).
- Recording costs about 1 s per test class (a JVM each; the log names carry the time as well as the process id, since Windows reuses ids). The first lane after an empty map runs and records everything.
- The test tasks run side by side, each with 4 forks (`-PtestForks`), and at most 8 test JVMs at once (`--max-workers`, the machine's cores). `LANE_FORKS` and `LANE_WORKERS` override them.

## Entry points

- `lane.sh`: the steps. Logs, results, the selection, the load logs (`loads-test/`, `loads-regression/`), the printed lines (`printed.txt`, by `tools/floating-roof-tanks/final-lane/printed.sh`), the 33 junction lines (`junction.txt`, only when the junction tests ran) and the campaign reports land in `tmp/merge-lane/<side>/`, or `$LANE_OUT/<side>`.
- `affected.mjs`: `select [--explain <test class>]` prints the selection (`--since <A>..<B>` replays a past batch on today's load sets); `save <load logs> <results>...` updates the load map. It reads the compiled classes in `build/classes/java/` and the `slowGateClasses` list of `build.gradle`.
- `record.init.gradle`: `--init-script tools/merge-lane/record.init.gradle -PlaneRecord=<folder relative to the worktree>` records any test task.
- `readers.txt`: readers that string constants do not show, one per line: a path expression and the reading source file.
- `main-reach.pl`: the classes of `src/main` the game cannot reach, by name from the mod's entry points: `perl tools/merge-lane/main-reach.pl .`. Name a class after the root to cut a suspect link. It does not look at single methods. AGENTS.md, Tooling cleanup.
- `compare.mjs`: a head lane against a baseline lane, by folder: `node tools/merge-lane/compare.mjs <base> <head>`. Per campaign the clean count, refusals, Newton counters, new, fixed and moved holds by class, and the new budget-class (C, F) holds; the junction lines' integer counters and `MIXED_GAS_COST`, or a note that the head lane did not run them.
- `campaign-inputs.txt`: the campaign's inputs as path patterns, one per line. A test JVM loads 206 production sources for a run of the campaign, some only because JUnit reads every test class. The list keeps the packages the solver needs. It is wider than the loaded set on purpose: a rarely loaded class in these packages must still run the campaign.

## History

- 2026-11-02: written from `tools/ph-false-root/lane.sh` and `tools/heat-exchanger-design/merge-lane/lane.sh`. Inputs measured with `-Xlog:class+load` on 80 seeds, cold and bundled.
- 2026-11-03: `main-reach.pl` added for the rule that `src/main` holds only code the game reaches.
- 2026-11-09: `compare.mjs` added at the 0.23.0 final lane (batch 2026-11-04-open-thermo-data, MERGE_REVIEW.md).
- 2026-11-09: affected-only lane (batch 2026-11-09-final-lane-selection): `affected.mjs`, `record.init.gradle`, `readers.txt`; chain-100 and the GameTests by what they load. A bytecode-reference closure was tried first and dropped: every class reaches the `@Mod` class `IChemE`, which names the client classes, so a renderer change selected every hold gate.
- 2026-11-10: a `build.gradle` change no longer runs every class (owner: "changing build.gradle should not trigger a full test"); `select` notes it (batch 2026-11-10-custom-shader-system, at its final lane).
- 2026-11-11: the lane may overlap one other Gradle run (owner: "allow two running at the same time", "change testing rule as well"; `AGENTS.md` changed with it).
