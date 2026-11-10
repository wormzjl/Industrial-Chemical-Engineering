# Agent rules for Industrial Chemical Engineering (IChemE)

These rules apply to every agent working in this repository (Claude, Codex, subagents) on every task, unless the owner states otherwise for that task.

## Saves and data formats

- Saved fluid and equipment data holds facts and state. Facts are what the player built and put in: device registrations and settings, node contents, captured solids, ledger totals, the world clock, accessories, equipment shapes, paint and nozzle sizes. State is what the engine can compute again: solved intervals, junction seeds, certificates, cadence, caches, pending events.
- A code or data change never discards a fact. State formats change freely: a mismatch drops the state, and the engine rebuilds it on its schedule.
- Fact and shape fields are append-only. A new field has a default equal to the behaviour before the field existed. A field never changes meaning or unit. Enums are saved by name; a renamed or removed constant gets an alias.
- Material data are identified by declared revisions, never by hashes. A change to a material record's values bumps that record's own `revision`; a viscosity edit bumps the viscosity's own `revision`; a freezing-point edit bumps the freezing point's own `revision`. Bump a package's `revision` only when the package record changes: its identities list the revisions of the records it names (`MaterialCatalog.Package.scientificRevision` for the column, `MaterialCatalog.fluidRevision` for the fluid network), and name the method by its physics fields, not by its revision. A revision is a token of letters, digits, `_`, `.` and `-`, at most 64 characters.
- After a material or model change, contents keep their amounts and temperature. An engine job recomputes energy and pressure. The ledger books the energy difference and every removed component; the island's status line names them.
- After a placed equipment structure loads, rebuild it to the current layout if another layout placed its cells (`EquipmentRefresh`, `EquipmentStructure.refreshLater`). Keep nozzle pipe blocks in place first, then cells. Keep the origin, paint, nozzle sizes and the fluid identity. Never overwrite another block. Remove a structure whose kind no longer exists whole, with every one of its cells. A layout change updates the layout table (`EquipmentLayoutGoldenTest`); a moved nozzle pipe block needs the owner's approval.
- Every format validates what it reads and round-trips what it carries. A damaged facts section refuses the world and leaves its files untouched.
- At every facts-schema or shape-field change, add a corpus image under `src/test/resources/compat/`. The gates read every image. Do not test against player worlds.
- Other formats (wire protocols, the column calculator block): no compatibility work. Test a breaking change on a fresh world. Do not write migrations for them.

## Engine schedule and player-facing updates

- The simulation engine publishes process presentation snapshots, acknowledgements and status changes on its own schedule, about every 5 s of online time.
- Send GUI and process data to a client only on that client's request:
  - Opening a GUI replays the last published snapshot immediately and subscribes the client to scheduled updates. Closing the GUI ends the subscription.
  - A replay is a read of cached data. It does not run a solver, create process state, apply pending input or advance time. If no snapshot exists yet, wait for the first scheduled publication.
  - Do not send contents to clients with no active request.
- Player inputs and network changes are queued engine events. Their acknowledgements and results arrive on the engine schedule. Packet handlers, block-entity loads and menu opens never force a process update.
- Do not calculate process state on Minecraft's per-tick loop. Due simulation deadlines, dependency changes and worker completions drive the work. The tick hook only checks what is due, drains completions and flushes a presentation bucket.
- Simulated time is online ticks at a nominal 20 TPS. There is no wall-clock or offline advancement.
- The process simulator's run is a client tool outside this schedule. It runs on the client and reads and writes no process state. The server only stores the sheet and its results, and the block opens them as a replay.

## Physics and defects

- Reason about every issue from real-life physics first, with simplifications: what flows where, and the steady state, in numbers. Write the report in this order:
  1. The expected behaviour.
  2. What the code did instead, in plain words.
  3. A proposal for the owner to validate.
- Every fix improves the existing structure. Put it in the stage it belongs to, and remove the special cases it makes unnecessary. Do not add isolated patches.
- Present a fix that rests on an assumption the physics does not force as options for the owner.

### Thermo validity

- A component whose fluid data reaches its freezing (triple) point stays valid below that temperature, in mixtures and in supercooled or metastable states. A freezing point is never a lower validity bound. Seed windows, domain floors, fluid-only ranges and range clips use the package's own domain floor.
- Leaving a data range (low or high T or P, any property model) never holds an island. Properties extrapolate with continuous value and slope at the edge. A non-blocking notice on the island's status line reports the extrapolation.
- Numerical guards stay only where the mathematics fails (the numerical box, T ≤ 0, no equation-of-state root). They act inside the solvers as a rejected trial step and a backtrack, never as a hold.
- Holds stay only for capability limits, where the model cannot represent a state.
- Keep these as lightweight game-level continuations. Do not add new reference models.

### Small physics simplifications

- When you work on physics code, look for simplifications with a small game-level effect: a negligible term, a rule or mode that a constitutive statement replaces, or a special case that a coarser model absorbs.
- Propose each one to the owner with:
  - its effect in numbers at the network's usual states and at the package extremes;
  - what it deletes;
  - what it risks (new discontinuities, references that move).
- Implement it only after the owner approves it. Write a broad sweep as a review.
- A simplification never breaks conservation of mass, energy or components, and never overrides an owner rule.
- A code comment that records a measured reason for a term blocks removing that term, unless the simplification also removes the cause.

## Gradle, measurement and verification

- Run at most two Gradle invocations at a time on the machine, each in its own worktree, and only if neither measures time. A run that measures time (benchmarks, wall-time cost gates) runs alone. Never run a test suite while a dev client is running.
- When two runs overlap, judge cost by counters. Replay a wall-clock budget hold alone before you count it.
- Large solver campaigns use 8 to 10 worker threads and never overlap another campaign of that size. A run on the second Gradle lane uses at most 4 worker threads.
- `./gradlew test` is the quick suite. `./gradlew test slowGates` (or `check`) runs everything. The final lane runs, once per batch, only the test classes that the batch's changes can reach: those whose recorded load set holds a change (`tools/merge-lane/affected.mjs`). `lane.sh head --all` runs everything; use it only when the owner asks.
- `columnGates` and `equipmentGates` hold the column and equipment tests, which `test` leaves out. Each sees only what its tests load, so it runs only when one of those files changed. When a group fails with a class or resource missing, add it to the group's list in `build.gradle`.
- Use the least measurement that decides the task:
  - Compare forms on the 200-seed quick check (all distributions). Compare at most three forms per task, then choose one.
  - Run one full campaign (1000 seeds, all distributions, 8 workers) for the chosen form only. Do not replay worse seeds alone. Replay alone only new budget-class holds (classes C and F not in the baseline), batched in one run.
  - List worse worlds by class with the campaign's classifier. Minimize and trace at most the two smallest new ones.
  - Read references once, in one final lane (`tools/merge-lane/lane.sh`: the affected test classes, with the 33 junction lines, the printed lines and `MIXED_GAS_COST` when their tests are among them; chain-100 exact and the fluid GameTests when what they load changed; the campaign when one of its inputs changed since `main`). A reference whose test the lane skips stands from the baseline lane. Do not run a second final lane. Do not copy the lane into a batch's tool folder.
  - To prove a gate test fails with its rule disabled, run that one test, not the suite.
  - Take counters from the campaign. Do not make separate per-seed Newton runs.
  - Split a hold class by mechanism only when the task targets that class, on the final code, with the existing classifier script.
- Reports are at most 50 lines.

### No repeated tests

- Never run a test again if neither the test nor anything it loads changed since it last passed. That pass stands.
- Before every test run, list the files changed since the last pass and the tests that can load them. Run only those tests, with `--tests`. If there are none, do not run.
  - Documents, `tools/`, comments, `AGENTS.md`, `CHANGELOG.md`: no test run.
  - A test removed, or test code left without a user removed: `compileTestJava` only. Prove the removal from the test names in the stored results.
  - A test added or edited: that test class.
  - Production code: the tests of the changed classes and the gates that name the rule.
- Run a whole task (`test`, `slowGates`, `check`) only through the final lane, once per batch, when the code is final. Do not run one to confirm a fix, between commits, or after a cleanup or an archive.
- Gradle reruns a whole task when one file of that task changes. Narrow the run with `--tests` instead of letting it.
- Read stored results first: `build/test-results`, the lane's output, the batch review. If they answer the question, do not run.
- Do not force a result again: no `--rerun-tasks`, `--no-build-cache`, `cleanTest` or deleted `build/`. An up-to-date or restored task counts as run.
- The only exception is a rule in this file that asks for a replay (a wall-clock budget hold, a gate proved with its rule disabled). Use `--rerun` on that one filtered test task.

### GUI and in-game checks

- Before any GUI work, read `.claude/skills/gui-creation/SKILL.md` and the reference it names for the job. It holds the GUI rules and build recipes. An agent with no skill loader reads it as a plain file.
- Do not start a dev-client check on your own. Judge GUI work by the gates, the compile and the software renders of `tools/heat-gui-renders/`. Where nothing else can judge (rendering, lighting, chunk sync), offer a check and let the owner start it.
- A check the owner asks for runs through the langyo/minecraft-mod-mcp bridge. Use the `runMcpClient` lane: copy the pinned jar from `tools/minecraft-mcp-bridge/` into `<worktree>/run/mcp-client-mods/`, and put `.mcp.json` in the worktree root.
- Never send real desktop keyboard or mouse input (`robot.ps1`, SendKeys) unless the owner is watching. If a check needs typed values and only desktop input can supply them, stop and report.
- Make at most one in-game check per task. Judge drawing-only changes by gates, sweeps and software renders.

## New equipment

- Before you build, change or review an equipment model or a sound, read `.claude/skills/models-and-sfx/SKILL.md`. It holds the model and sound rules, the owner's taste, and the tools.
- Every new `EquipmentShape.Kind`, `ConstructorColumn.Equipment` and `TopologyCompiler.Kind` constant joins the process simulator. Add its case to `FlowsheetCatalog`: MODELLED, or DRAWING_ONLY while no model exists.
- `FlowsheetPaletteCoverageTest` checks that each has a tile, a port rule and a lang line. One tile may hold several kinds.
- Use the tracked workflow in [`tools/equipment-drawings/README.md`](tools/equipment-drawings/README.md): `./tools/equipment-drawings/update.ps1 -Check`, then `./tools/equipment-drawings/update.ps1 -Only <affected IDs or kinds>` (or its `update.py` equivalent). Keep the exporter, renderer, dependency list and run instructions readily available in `tools/`; never make drawing updates depend only on ignored scratch files, session-specific class paths or another worktree.

## Translations

- During a batch, change only `src/main/resources/assets/icheme/lang/en_us.json`. Do not edit the other lang files between commits. `LanguageFilesTest` and `UnitTextTest` fail until the translation commit; do not run them before it.
- Before the batch merges, make one translation commit: follow the `translation-commit` skill (`.claude/skills/translation-commit/SKILL.md`).

## Skills and hooks

- Project skills live once in `.claude/skills/<name>/`. `.agents/skills/<name>` is a tracked symlink to the same folder, so Codex loads them too. A new skill gets both. An agent with no skill loader reads `SKILL.md` as a plain file.
- `tools/agent-guard/guard.js` blocks the git and Gradle commands this file forbids, for Claude Code (`.claude/settings.json`) and Codex (`.codex/hooks.json`). A blocked command is not retried in another form.
- Do not add a `CLAUDE.md`, `.claude/CLAUDE.md` or `CLAUDE.local.md` at or above the repository root. Claude Code then stops reading this file.

## Git and worktrees

- Work only in your own worktree. Never modify another agent's worktree.
- Never use bare `git stash` or `git stash pop`. Set work aside with a WIP commit, or with a tagged stash entry that you restore with `git stash apply`.
- Never stage git-ignored files (`git add -f`), for example logs, campaign reports or gate output. Before a merge, `git ls-files -i -c --exclude-standard` must be empty.

## Documentation, research and tools

- `documentation/` and `tools/` are tracked. Write and commit them in your own worktree, on the task's branch. They reach `main` with the merge. Never edit them in the main checkout: it holds `main`.
- `research/` is local and git-ignored. Its canonical copy is in the main checkout, `D:/Minecraft/Modding/1.21/CreateChemE/research/`. Write research material there.
- The folders:
  - `documentation/`: one folder per batch of work, `YYYY-MM-DD-topic/` (the date the batch started), plus `reference/` and `repository/`. `documentation/INDEX.md` lists every batch with its status; `documentation/README.md` explains the layout.
  - `research/`: datasets, literature, study harnesses, journals and the large files of `documentation/`, grouped by batch, indexed in `research/INDEX.md`.
  - `tools/`: offline scripts and harnesses, one folder per tool, indexed in `tools/INDEX.md`. `tools/development.gradle` stays at that path.
- Do not add top-level folders for documents or scripts. Leave `examples/` (tracked, used by the build and tests) where it is.
- Write each review or audit to `documentation/<batch>/<TOPIC>_REVIEW.md` and each plan to `documentation/<batch>/<TOPIC>_PLAN.md`.
- After every task, in the same session:
  - Put the task's documents in its batch folder. Create a new dated folder, with a `README.md`, for a new batch.
  - Update the batch's row in `documentation/INDEX.md`. The row holds only the folder, the purpose in a few words and the status with its date: Implemented (date it reached `main`), In progress since, Planned, Concluded (research only), or Abandoned / Superseded.
  - Write everything else about the batch in its `README.md`: the status, one history line per task (commits, gates, decisions), the documents, the code areas and the open items.
  - Update `tools/INDEX.md`, or the main checkout's `research/INDEX.md`, if they changed.
  - Commit the documents and tool folders on the task's branch.
- Work that never merges (research only, abandoned) brings its documents and tool folders to `main` in a documentation-only merge.
- Index files change row by row. When a merge conflicts in `documentation/INDEX.md` or `tools/INDEX.md`, keep every row of both sides. Patch the main checkout's `research/INDEX.md` in place; never copy it whole.
- Keep large files out of `documentation/`. It holds text documents, each under 1 MB. Screenshots and other images, PDFs, world region files and `*-logs/` folders are git-ignored there. Store them in the main checkout at the same path under `research/` (`documentation/<batch>/shots/a.png` goes to `research/<batch>/shots/a.png`) and link them there. Treat any other file over 1 MB the same way, and add a `.gitignore` line for a new large file type.
- Logs stay git-ignored (`logs/`, `*.log`). Store them compressed in the main checkout, `tools/<tool-folder>/logs/<name>-logs-<date>.tar.gz`. These archives stay local; never stage them.

## Code guide

- The code guide is at `documentation/reference/code-guide/`. `README.md` gives what the mod calculates and the code map. There is one chapter per subsystem.
- Read `README.md` first in an unfamiliar area. Open only the chapter the task needs.

## Merging to main

- Every merge to `main` includes, in the same merge: a `CHANGELOG.md` entry, a proposed `mod_version` bump that the owner chooses, the translation commit, a code guide update and the tooling cleanup. Follow the `merge-prep` skill (`.claude/skills/merge-prep/SKILL.md`) for each of them.
- Probes, rigs and scripts a batch made for measurement leave `src/main` and go to `tools/<tool-folder>/` before the merge (`merge-prep`, "Tooling cleanup").
- A documentation-only merge changes only `documentation/`, `tools/`, `AGENTS.md`, `.gitignore`, the project skills or the agent hook files. It needs none of these.
