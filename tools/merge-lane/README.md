# merge-lane

Purpose: the one final lane before a merge to `main` (AGENTS.md, "Gradle, measurement and verification"). Batches: [`2026-11-02-test-suite-optimization`](../../documentation/2026-11-02-test-suite-optimization/README.md), [`2026-11-09-final-lane-selection`](../../documentation/2026-11-09-final-lane-selection/README.md), [`2026-11-16-final-lane-cost`](../../documentation/2026-11-16-final-lane-cost/README.md). It replaces the copy of `lane.sh` that each batch made, with its fixed worktree paths.

## How to run

From the branch's worktree, with no dev client. At most one other Gradle run, of at most 4 workers, may share the machine:

```bash
bash tools/merge-lane/lane.sh head
```

- The lane measures no time and prints none (owner, 2026-11-16: "timing belongs to benchmark runs"). Judge the campaign and the cost gates by counters. Replay a wall-clock budget hold alone before you count it.
- A step list after the side runs only those steps, for example `lane.sh head campaign`.
- A second lane of a batch is the same command. It runs only what changed since the first: the test classes by their load sets, the GameTests and the campaign by their stamps.
- `--all` runs every step whole, every test (those of `left-out.txt` too) and the campaign on all 3 x 1000 seeds, whatever changed. `base` does the same on main, in a scratch worktree under the main checkout's `.claude/worktrees/`. Both run only on the owner's word: `LANE_FULL=owner bash tools/merge-lane/lane.sh head --all`. Without it the script stops, and `tools/agent-guard` blocks the command.
- `LANE_SEEDS=<seeds>` runs the campaign on those seeds whether it is due or not (a replay: `LANE_SEEDS=6,431`). More than 200 seeds need `LANE_FULL=owner`.
- `LANE_WORKERS` (8, the machine's cores), `LANE_FORKS` (8 per test task) and `LANE_GROUP` (8 test classes to a JVM) set the test step's width. With 10 or 12 workers, wall-deadline holds appeared in tests that have none.
- `LANE_SINCE=<A>..<B>` selects as a past batch did (`affected.mjs select --since`): a rehearsal of the lane. `LANE_SINCE=<commit>` takes that commit as the base of every step instead of `main` and the stamps (the second-lane form of the fouling batch; the stamps now do this without it).

## What it runs

- `test`: compiles, asks `affected.mjs select` which test classes the changes can reach, and runs only those, in `test`, `holdGates`, `columnGates`, `equipmentGates` and `nativeSmoke` (`NativeColumnSolverTest`, which `columnGates` leaves out). A task with no class is left out. `selection.txt` in the lane folder gives each choice and, on `WHY` lines, its reason; `picks.txt` is the filter file Gradle gets. The tests of `left-out.txt` do not run.
- `regression`: chain-100 exact, when `FluidSolverRegressionTest` is selected.
- `gametest`: the fluid GameTests, when a non-client main source, a GameTest source, `data/`, `META-INF/` or the templates changed. The GameTest server loads the whole server side, so this rule is by path.
- `campaign`: the watch seeds of `campaign-seeds.txt`, when a path of `campaign-inputs.txt` changed.
- The GameTests and the campaign ask what changed since their last run on the branch's line of commits (`affected.mjs stamp`, written when the step ran), or since the merge base with `main` when they have none. A stamp counts when its commit is an ancestor of HEAD.

## The campaign's watch seeds

- `campaign-seeds.txt` holds the seeds with a tendency to hold: every seed that held in at least one of the 15 archived 1000-seed campaigns at the game's time factor. 14 cold seeds and 13 bundled random seeds; the bundled defaults never held in 23 campaigns and do not run (owner, 2026-11-16: "remove all which have no tendency to fail at all and keep it below 200").
- A seed that holds in any run joins its line. The file stays under 200 seeds.
- `compare.mjs` compares a head of watch seeds with a baseline of 1000 on the head's seeds. The counters of a report total all its seeds, so they compare only between two runs of the same seeds.

## The tests left out

- `left-out.txt` lists test methods the lane never runs, as Gradle filters (`<test class>.<method>`), each with its record (owner, 2026-11-16: "still too long, remove the least useful tests"; "if it exceeds 8min your job is not done").
- The list of 2026-11-16: every test method of 1 s or more that ran in at least 6 of 19 archived final lanes and never failed in any. 363 methods in 187 classes: 8.9 % of the 4,088 test methods and 77 % of the test time. 28 methods failed at least once in those lanes; none of them is on the list. A class keeps at least one test.
- This is a judgement by record, not a proof: a left-out test no longer catches a regression in the lane. The tests stay in the sources. `--tests` runs them, and the full lane (`LANE_FULL=owner lane.sh head --all`) runs them all.
- Upkeep: a test on the list that fails in any run leaves the list. The lane is sized to end within 8 minutes with every step due (owner, 2026-11-16: "if an added test causes timing to increase above 8min, something less important needs to go"): a batch whose new tests would take it past that adds tests to the list in the same batch, the costliest that never failed in the archived lanes first, each with its record, and names them in its review. Judge the size from the test times in the JUnit results of the batch's own runs; a timed run of the lane is a benchmark run, not part of the lane.

## The selection, and why it is safe

- A test's result can depend only on what its JVM loads and reads. Every test run of the lane records, per test JVM, the classes it loaded (`record.init.gradle`, `-Xlog:class+load`). `affected.mjs save` turns the logs into a load set of source files for each class that passed and stores it in the load map with the commit it ran at.
- The test classes share JVMs, 8 to one (a JVM for every class cost a third of the test step). A JVM's loads go to every test class of the run that it loaded, so a class's load set also holds what the 7 others loaded. A larger load set selects the class more often, never less: it is a safe one.
- The load map is `tools/merge-lane/logs/load-map.json.gz` in the main checkout (git-ignored, shared by every worktree; `$LANE_MAP` overrides it). It keeps the three newest load sets per test class. `steps.json` beside it holds the stamps.
- `select` takes, per test class, its newest load set whose commit is an ancestor of HEAD, and lists the files changed between that commit and the working tree. The class runs when one of these holds:
  - it has no such load set (a new class, an empty map, or load sets recorded only on other branches);
  - its own source or a file of its load set changed;
  - a loaded file uses a compile-time constant whose value is written differently (javac copies the value into its users, which then never load the holder): it names `Type.NAME`, imports it statically, or extends the type; a constant built from a changed one counts as changed. The constants are the fields with a `ConstantValue` in the compiled classes, by the types that declare them;
  - a loaded file reads a changed resource or source file: a string constant names the file's path or folder, the resource sits in the class's package, or `readers.txt` lists the reader (for paths built from pieces);
  - a build file changed (`settings.gradle`, `gradle/`, the wrapper, `tools/development.gradle`; not `build.gradle`, owner 2026-11-10: its compileOnly dependencies, JVM settings and gate lists do not change what a test computes; not `gradle.properties`, owner 2026-11-16: `select` notes the change, and a batch that changes the game or loader version there runs the lane with `--all`), or a test resource changed that no class names: then every class runs.
- If nothing a test class loaded or read changed since its load set was recorded, it runs the same code on the same data, and its last pass stands. A change that would make it load a new class must change a class it loaded.
- A failed class saves no load set (a failure can cut it short), so it runs again next time. Uncommitted changes to `src/` or the build files block the save and the stamps: HEAD would not be what ran.
- Changes to `documentation/`, `tools/`, `research/`, `*.md` at the root and `.claude/` run nothing (the campaign list covers its own tool folders and `campaign-seeds.txt`).
- The first lane after an empty map runs and records everything.

### Loaded and never initialized

The lane also records which classes each test JVM initialized (`-Xlog:class+init`) and stores the files with one in the load set (`init`). A file that a JVM loaded and never initialized ran no code there: its classes were only named by another class's checks. `select` counts the test classes that are selected only through such files and prints the count on a `NOTE` line; they still run. Leaving them out is an owner decision, with the count of a few lanes in hand: a change to such a file's shape (its supertypes, its members' signatures) could still reach a test that reads the class by reflection.

## Entry points

- `lane.sh`: the steps. Logs, results, the selection, the filter file, the load logs (`loads-test/`, `loads-regression/`), the printed lines (`printed.txt`), the 33 junction lines (`junction.txt`, only when the junction tests ran) and the campaign reports land in `tmp/merge-lane/<side>/`, or `$LANE_OUT/<side>`. Failed tests are listed on `FAILED` lines.
- `affected.mjs`: `select [--explain <test class>]` prints the selection (`--since <A>..<B>` replays a past batch on today's load sets); `save <load logs> <results>...` updates the load map; `stamp <gametest|campaign> [<folder>]` records a step's run. It reads the compiled classes in `build/classes/java/` and the `slowGateClasses` list of `build.gradle`. Use `select` for a targeted run during a batch too: it names the classes for `--tests`.
- `record.init.gradle`: `-PlaneTests=<file>` gives each test task its filters (lines `<task> [!]<filter>`); `-PlaneRecord=<folder relative to the worktree>` records any test task, `-PlaneGroup` classes to a JVM.
- `campaign-seeds.txt`: the watch seeds, one line per campaign run.
- `left-out.txt`: the test methods the lane leaves out.
- `printed.mjs`: `node tools/merge-lane/printed.mjs <results> <out>`: every test's printed lines with the class name in front.
- `campaign-inputs.txt`: the campaign's inputs as path patterns, one per line. A test JVM loads 206 production sources for a run of the campaign, some only because JUnit reads every test class. The list keeps the packages the solver needs. It is wider than the loaded set on purpose: a rarely loaded class in these packages must still run the campaign.
- `readers.txt`: readers that string constants do not show, one per line: a path expression and the reading source file.
- `main-reach.pl`: the classes of `src/main` the game cannot reach, by name from the mod's entry points: `perl tools/merge-lane/main-reach.pl .`. Name a class after the root to cut a suspect link. It does not look at single methods. AGENTS.md, Tooling cleanup.
- `compare.mjs`: a head lane against a baseline lane, by folder: `node tools/merge-lane/compare.mjs <base> <head>`. Per campaign the clean count, refusals, new, fixed and moved holds by class, the new budget-class (C, F) holds and, between runs of the same seeds, the Newton counters; the test classes whose printed lines differ (the lines go to `<head>/printed-diff.txt`); the junction lines' integer counters and `MIXED_GAS_COST`, or a note that the head lane did not run them. A third folder, `node tools/merge-lane/compare.mjs <base> <head> <campaign base>`, is the campaign's baseline instead of the base lane's (`select` names the campaign's last reports on a `NOTE` line); each campaign prints both time factors (`factor=` on the harness's SUMMARY line) and warns when they differ or one is not recorded.

## History

- 2026-11-02: written from `tools/ph-false-root/lane.sh` and `tools/heat-exchanger-design/merge-lane/lane.sh`. Inputs measured with `-Xlog:class+load` on 80 seeds, cold and bundled.
- 2026-11-03: `main-reach.pl` added for the rule that `src/main` holds only code the game reaches.
- 2026-11-09: `compare.mjs` added at the 0.23.0 final lane (batch 2026-11-04-open-thermo-data, MERGE_REVIEW.md).
- 2026-11-09: affected-only lane (batch 2026-11-09-final-lane-selection): `affected.mjs`, `record.init.gradle`, `readers.txt`; chain-100 and the GameTests by what they load. A bytecode-reference closure was tried first and dropped: every class reaches the `@Mod` class `IChemE`, which names the client classes, so a renderer change selected every hold gate.
- 2026-11-10: a `build.gradle` change no longer runs every class (owner: "changing build.gradle should not trigger a full test"); `select` notes it (batch 2026-11-10-custom-shader-system, at its final lane).
- 2026-11-11: the lane may overlap one other Gradle run (owner: "allow two running at the same time", "change testing rule as well"; `AGENTS.md` changed with it). Time is recorded only in a performance optimization batch (owner: "no need to record time when not doing performance optimization").
- 2026-11-16: a `gradle.properties` change no longer runs every class (owner: "gradle.properties change should not trigger full test"); `select` notes it (batch 2026-11-15-pollution-system, at its final lane, where four compile-only dependency versions had selected every class).
- 2026-11-11 (batch 2026-11-11-valve-followups, item 5): the campaign runs at the game's time factor, 4 (the harnesses' default, `SimulationTime.DEFAULT_FACTOR`); `lane.sh` passes no factor. A `lane.sh base` on code before this item runs the campaign at factor 1.
- 2026-11-16 (batch 2026-11-16-fouling-corrosion, findings of its second final lane): `affected.mjs` files the native tests (`nativeTestClasses` of `build.gradle`) under `nativeSmoke`, and the lane runs that task where cargo is found; each task's filters come from a file (past about 8,000 characters of `--tests` filters the lane had run the `test` task whole: 519 classes for 288 selected); `LANE_SINCE=<commit>` gives a second lane its base (owner: "only rerun the affected parts").
- 2026-11-16 (batch 2026-11-16-final-lane-cost; owner: a lane under 8 minutes instead of 28, "harder enforcement of test only relevant part policy"): the campaign's watch seeds; `left-out.txt`; the GameTests and the campaign by their last run, not by `main`; test classes share JVMs; filters in a file (a long list made a task run whole: 233 classes of one lane); `nativeSmoke` in the lane (`NativeColumnSolverTest` was selected into `columnGates`, which leaves it out); constants from the class files (the earlier rule took the initialized locals of any file with an interface for constants); `compare.mjs` on the watch seeds and the printed lines; no time printed; `--all` and `base` on the owner's word. Tried and dropped: 10 and 12 workers (wall-deadline holds), and cutting long classes by test method (each part builds the class's fixtures again: `CrudeUnitSampleTest` 217 s whole, 606 s in four parts).
