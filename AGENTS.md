# Agent rules for Industrial Chemical Engineering (IChemE)

For every agent (Claude, Codex, subagents) on every task, unless the owner states otherwise for that task.

## Saves and data formats

- Saved fluid and equipment data holds facts and state. Facts are what the player built and put in: device registrations and settings, node contents, captured solids, ledger totals, the world clock, accessories, equipment shapes, paint and nozzle sizes. State is what the engine can compute again: solved intervals, junction seeds, certificates, cadence, caches, pending events.
- Never discard a fact. State formats change freely: a mismatch drops the state, and the engine rebuilds it on its schedule.
- Fact and shape fields are append-only. A new field defaults to the behaviour before it existed. A field never changes meaning or unit. Enums are saved by name; a renamed or removed constant gets an alias.
- An identity is a declared revision: a token of letters, digits, `_`, `.` and `-`, at most 64 characters. Nothing saved or cached is keyed by a hash of its content.
- A value change bumps the `revision` of the record that holds it: the material record, its viscosity or its freezing point. A package's `revision` changes only with the package record. Its identities list the revisions of the records it names (`MaterialCatalog.Package.scientificRevision` for the column, `MaterialCatalog.fluidRevision` for the fluid network) and name the method by its physics fields.
- After a material or model change, contents keep their amounts and temperature. An engine job recomputes energy and pressure. The ledger books the energy difference and every removed component; the island's status line names them.
- After a placed equipment structure loads, rebuild it to the current layout if another layout placed its cells (`EquipmentRefresh`, `EquipmentStructure.refreshLater`): nozzle pipe blocks first, then cells. Keep the origin, paint, nozzle sizes and fluid identity. Never overwrite another block. Remove a structure whose kind no longer exists whole, with all its cells. A layout change updates `EquipmentLayoutGoldenTest`; a moved nozzle pipe block needs the owner's approval.
- Every format validates what it reads and round-trips what it carries. A damaged facts section refuses the world and leaves its files untouched.
- Every facts-schema or shape-field change adds a corpus image under `src/test/resources/compat/`. The gates read every image. Do not test against player worlds.
- Other formats (wire protocols, the column calculator block): no compatibility work and no migrations. Test a breaking change on a fresh world.

## Engine schedule and player-facing updates

- The engine publishes presentation snapshots, acknowledgements and status changes on its own schedule, about every 5 s of online time.
- A client gets GUI and process data only on its own request. Opening a GUI replays the last published snapshot at once and subscribes the client to scheduled updates; closing it ends the subscription.
- A replay reads cached data. It does not run a solver, create process state, apply pending input or advance time. With no snapshot yet, wait for the first scheduled publication.
- Player inputs and network changes are queued engine events, acknowledged on the engine schedule. Packet handlers, block-entity loads and menu opens never force a process update.
- Do not calculate process state on Minecraft's tick loop. Due deadlines, dependency changes and worker completions drive the work. The tick hook only checks what is due, drains completions and flushes a presentation bucket.
- Simulated time is online ticks at a nominal 20 TPS, times the time factor (`fluid.timeScale`, default 4). No wall-clock or offline advancement.
- The process simulator's run is a client tool outside this schedule: it reads and writes no process state. The server only stores the sheet and its results, and the block opens them as a replay.

## Physics and defects

- Reason about every issue from real-life physics first, with simplifications: what flows where, and the steady state, in numbers. Report in this order: the expected behaviour; what the code did instead, in plain words; a proposal for the owner to validate.
- Every fix improves the existing structure: it goes in the stage it belongs to and removes the special cases it makes unnecessary. No isolated patches.
- A fix that rests on an assumption the physics does not force goes to the owner as options.
- Conservation of mass, energy and components stays exact in every change.

### Thermo validity

- A freezing (triple) point is never a lower validity bound. A component stays valid below it, in mixtures and in supercooled or metastable states. Seed windows, domain floors, fluid-only ranges and range clips use the package's own domain floor.
- Leaving a data range (low or high T or P, any property model) never holds an island. Properties extrapolate with continuous value and slope at the edge, and a non-blocking notice on the island's status line reports it.
- Numerical guards stay only where the mathematics fails (the numerical box, T ≤ 0, no equation-of-state root). They act inside the solvers as a rejected trial step and a backtrack, never as a hold.
- Holds stay only for capability limits, where the model cannot represent a state.
- Keep these as lightweight game-level continuations. Add no reference models.

### Small physics simplifications

- In physics code, look for simplifications with a small game-level effect: a negligible term, a rule or mode that a constitutive statement replaces, a special case that a coarser model absorbs.
- Propose each one to the owner with its effect in numbers (at the network's usual states and at the package extremes), what it deletes and what it risks (new discontinuities, references that move). Implement it only after approval. Write a broad sweep as a review.
- A simplification never overrides an owner rule. A code comment that records a measured reason for a term blocks removing it, unless the simplification also removes the cause.

### Results that move

- A change that only moves code keeps every reference exact.
- A change that moves a reference states, in its review, the old and new values and the bound it was expected to keep, taken from the solver's own tolerances. The final lane re-records it once, with both values in its output.

## Gradle, measurement and verification

- At most two Gradle invocations run at a time on the machine, each in its own worktree; the final lane may be one of them. Never run a test suite while a dev client is running.
- Worker threads: a large solver campaign 8 to 10, never beside another of that size; a run on the second Gradle lane at most 4; the final lane's test step 8 (`LANE_WORKERS`).
- Record time only in a performance optimization task; a timed run runs alone. In every other task judge cost by counters, in wall-time cost gates and campaigns too, and report no times. Replay a wall-clock budget hold alone before you count it.
- The test tasks are `test` (the quick suite), `holdGates`, `columnGates`, `equipmentGates` and `nativeSmoke`. Outside the final lane, every run names its test classes with `--tests`. `check`, `build` and `slowGates` are the lane's alone; `assemble` builds the jar.
- `columnGates` and `equipmentGates` hold the column and equipment tests, which `test` leaves out, and see only what their tests load. When one fails with a class or resource missing, add it to the group's list in `build.gradle`.
- The final lane is `bash tools/merge-lane/lane.sh head`; `tools/merge-lane/README.md` holds its steps and options.
  - It runs once per batch, when the code is final, on the test classes the batch's changes can reach (`tools/merge-lane/affected.mjs`).
  - References are read there, once: the 33 junction lines, the printed lines and `MIXED_GAS_COST` when their tests are selected; chain-100 exact, the fluid GameTests and the campaign when what they load changed. A reference whose test the lane skips stands from the baseline lane.
  - Do not run a second lane. Where the owner asks for one, it is the same command and runs only what changed since the first. Do not copy the lane into a batch's tool folder.
  - The full suite (`lane.sh head --all`, `lane.sh base`) runs only when the owner asks, with `LANE_FULL=owner`. Never propose one; none runs on a schedule, before a merge or after one.
  - The lane measures and prints no time, and ends within 8 minutes with every step due. A batch whose new tests would take it past that moves less important tests to `tools/merge-lane/left-out.txt` in the same batch: the costliest that never failed in the archived lanes first, each with its record, named in the batch's review. A listed test that fails in any run leaves the list. Listed tests stay in the sources; a `--tests` run of their class runs them.
- Use the least measurement that decides the task:
  - Compare at most three forms, on the 200-seed quick check (all distributions), then choose one.
  - Run one campaign, for the chosen form only: the watch seeds of `tools/merge-lane/campaign-seeds.txt` (`lane.sh head campaign`, 8 workers). A seed that holds in any run joins the list; the list stays under 200 seeds. The 1000 seeds of each distribution run only when the owner asks (`LANE_FULL=owner LANE_SEEDS=1-1000`).
  - Do not replay worse seeds alone. Replay alone only new budget-class holds (classes C and F not in the baseline), batched in one run.
  - List worse worlds by class with the campaign's classifier. Minimize and trace at most the two smallest new ones. Split a hold class by mechanism only when the task targets that class, on the final code, with the existing classifier script.
  - A fuzz seed stops at its first hold (both hold-fuzz harnesses). A held seed's one replay alone, in the lane, is its measurement. Do not replay it again to confirm a fix, bisect it or trace it: diagnose from the lane's trace, the code and git history. Its next run is the next final lane's.
  - Take counters from the campaign. No separate per-seed Newton runs.
  - Never run a test with its fix or rule disabled to prove the fix. The failure seen before the fix is the proof.
- A report's summary is at most 50 lines. A findings table may follow it.

### No repeated tests

- A pass stands until the test or something it loads changes. Read stored results first (`build/test-results`, the lane's output, the batch review); if they answer the question, do not run.
- Before every test run, list the files changed since the last pass and run only the tests that can load them. If there are none, do not run.
  - Documents, `tools/`, comments, `AGENTS.md`, `CHANGELOG.md`: no test run.
  - A test removed, or test code left without a user removed: `compileTestJava` only. Prove the removal from the test names in the stored results.
  - A test added or edited: that test class.
  - Production code: the tests of the changed classes and the gates that name the rule.
- Take the test classes from `node tools/merge-lane/affected.mjs select` (after `testClasses`), not from a hand list. A `--tests` filter names test classes, never a package tree (`*`, `*Test`, `com.wormzjl.icheme.<package>.*`).
- Do not run a whole task to confirm a fix, between commits, or after a cleanup or an archive. Gradle reruns a whole task when one of its files changes, so narrow every run with `--tests`.
- Do not force a result again: no `--rerun-tasks`, `--no-build-cache`, `cleanTest` or deleted `build/`. An up-to-date or restored task counts as run. The one exception is a replay this file asks for (a wall-clock budget hold): `--rerun` on that one filtered test task.
- `tools/agent-guard` blocks the commands this section forbids; its README lists them.

### GUI and in-game checks

- Before any GUI work, read the `gui-creation` skill and the reference it names for the job.
- Do not start a dev-client check on your own. Judge GUI work by the gates, the compile and the software renders of `tools/heat-gui-renders/`. Where nothing else can judge (rendering, lighting, chunk sync), offer a check and let the owner start it.
- A check the owner asks for runs through the langyo/minecraft-mod-mcp bridge on the `runMcpClient` lane: copy the pinned jar from `tools/minecraft-mcp-bridge/` into `<worktree>/run/mcp-client-mods/`, and put `.mcp.json` in the worktree root.
- Never send real desktop keyboard or mouse input (`robot.ps1`, SendKeys) unless the owner is watching. If a check needs typed values that only desktop input can supply, stop and report.
- At most one in-game check per task. Judge drawing-only changes by gates, sweeps and software renders.

## New equipment

- Before you build, change or review an equipment model or a sound, read the `models-and-sfx` skill.
- Every new `EquipmentShape.Kind`, `ConstructorColumn.Equipment` and `TopologyCompiler.Kind` constant gets a case in `FlowsheetCatalog`: MODELLED, or DRAWING_ONLY while no model exists. `FlowsheetPaletteCoverageTest` checks that each has a tile, a port rule and a lang line; one tile may hold several kinds.
- Update drawings by `tools/equipment-drawings/README.md`: `./tools/equipment-drawings/update.ps1 -Check`, then `-Only <affected IDs or kinds>` (or `update.py`). Drawing updates never depend on ignored scratch files, session-specific class paths or another worktree: the exporter, renderer, dependency list and run instructions stay in `tools/`.

## Translations

- During a batch, change only `src/main/resources/assets/icheme/lang/en_us.json`. `LanguageFilesTest` and `UnitTextTest` fail until the translation commit; do not run them before it.
- Before the batch merges, make one translation commit with the `translation-commit` skill.

## Skills and hooks

- A project skill lives in `.claude/skills/<name>/`, with `.agents/skills/<name>` a tracked symlink to it for Codex. A new skill gets both. An agent with no skill loader reads `.claude/skills/<name>/SKILL.md` as a plain file.
- `tools/agent-guard/guard.js` blocks the git and Gradle commands this file forbids (`.claude/settings.json`, `.codex/hooks.json`). Do not retry a blocked command in another form.
- Do not add a `CLAUDE.md`, `.claude/CLAUDE.md` or `CLAUDE.local.md` at or above the repository root: Claude Code then stops reading this file.

## Git and worktrees

- Work only in your own worktree. Never modify another agent's worktree.
- Never use bare `git stash` or `git stash pop`. Set work aside with a WIP commit, or with a tagged stash entry restored with `git stash apply`.
- Never stage git-ignored files (`git add -f`): logs, campaign reports, gate output. Before a merge, `git ls-files -i -c --exclude-standard` is empty.

## Documentation, research and tools

- `documentation/` and `tools/` are tracked: write and commit them in your own worktree, on the task's branch, never in the main checkout. `research/` is git-ignored; its one copy is the main checkout's `D:/Minecraft/Modding/1.21/CreateChemE/research/`, and research material is written there.
- The folders (`documentation/README.md` gives the layout):
  - `documentation/`: one folder per batch, `YYYY-MM-DD-topic/` (the date the batch started), plus `reference/` and `repository/`; indexed in `documentation/INDEX.md`.
  - `research/`: datasets, literature, study harnesses, journals and the large files of `documentation/`, by batch; indexed in `research/INDEX.md`.
  - `tools/`: offline scripts and harnesses, one folder per tool; indexed in `tools/INDEX.md`. `tools/development.gradle` stays at that path.
- Add no top-level folder for documents or scripts. Leave `examples/` where it is.
- A review or audit is `documentation/<batch>/<TOPIC>_REVIEW.md`; a plan is `documentation/<batch>/<TOPIC>_PLAN.md`.
- After every task, in the same session:
  - Put its documents in its batch folder; a new batch gets a new dated folder with a `README.md`.
  - Update the batch's row in `documentation/INDEX.md`. A row holds only the folder, the purpose in a few words and the status with its date: Implemented (date it reached `main`), In progress since, Planned, Concluded (research only), or Abandoned / Superseded.
  - Write the rest in the batch's `README.md`: the status, one history line per task (commits, gates, decisions), the documents, the code areas and the open items.
  - Update `tools/INDEX.md`, or the main checkout's `research/INDEX.md`, if they changed.
  - Commit the documents and tool folders on the task's branch.
- Work that never merges (research only, abandoned) brings its documents and tool folders to `main` in a documentation-only merge.
- Index files change row by row. In a merge conflict in `documentation/INDEX.md` or `tools/INDEX.md`, keep every row of both sides. Patch the main checkout's `research/INDEX.md` in place; never copy it whole.
- Every file in `documentation/` and `tools/` is under 1 MB: text documents, scripts and small inputs. Images, PDFs, world region files and `*-logs/` folders are git-ignored there. Store them, and any other file over 1 MB, in the main checkout at the same path under `research/` (`documentation/<batch>/shots/a.png` goes to `research/<batch>/shots/a.png`) and link them there. A new large file type gets a `.gitignore` line.
- Logs stay git-ignored (`logs/`, `*.log`). Store them compressed in the main checkout, `tools/<tool-folder>/logs/<name>-logs-<date>.tar.gz`; never stage these archives.

## Code guide

- The code guide is `documentation/reference/code-guide/`: `README.md` (what the mod calculates, the code map) and one chapter per subsystem. In an unfamiliar area read `README.md` first, then only the chapter the task needs.
- `README.md` stays under 150 lines. The guide holds no history: that goes to `CHANGELOG.md` and the batch's README.
- `documentation/reference/FORMATS.md` lists every saved and wire format with the constant that declares it. A batch that changes a format changes its row in the same commit.

## Parallel batches

- Before a batch starts beside another, read `documentation/reference/COLLABORATION.md`: lanes and hot files, how a format or schema number is reserved, the brief template, how a moved result is reported.
- Two batches never change the same file at the same time. A hot file has one owner batch at a time.

## Native code

- The Java path is the reference and the fallback. A native path gives the same results as Java where its gate says so, and its ceiling states which solves it takes.
- A Java method with a Rust twin changes in both in the same batch, or the batch lowers the native ceiling below it.
- Run `cargo` from `src/main/rust` (its config sets 4 jobs, the target folder and the vendored sources). A cargo build or test uses at most 4 jobs, counts as one of the two Gradle lanes and never runs during a timed run. The merge lane builds the native library through Gradle.

## Merging to main

- Every merge to `main` includes, in the same merge: a `CHANGELOG.md` entry, a proposed `mod_version` bump that the owner chooses, the translation commit, a code guide update and the tooling cleanup. Follow the `merge-prep` skill for each.
- Probes, rigs and scripts a batch made for measurement leave `src/main` for `tools/<tool-folder>/` before the merge.
- A documentation-only merge changes only `documentation/`, `tools/`, `AGENTS.md`, `.gitignore`, the project skills or the agent hook files. It needs none of these.
