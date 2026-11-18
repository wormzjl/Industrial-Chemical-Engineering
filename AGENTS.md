# Agent rules for Industrial Chemical Engineering (IChemE)

For every agent (Claude, Codex, subagents) on every task, unless the owner states otherwise for that task.

This file holds the core of each subject. The full rules are in `documentation/reference/rules/<name>.md`, with the same force. Where a section names a rules file, read it before you start work of that kind, and name it in the brief of a delegated task.

## Saves and data formats

- Saved data holds facts (what the player built and put in) and state (what the engine can compute again). Never discard a fact. A state mismatch drops the state, and the engine rebuilds it.
- Fact and shape fields are append-only. A new field defaults to the behaviour before it existed. A field never changes meaning or unit. Enums are saved by name.
- An identity is a declared revision, never a hash of content. A value change bumps the `revision` of the record that holds it.
- Wire protocols and the column calculator block get no compatibility work and no migrations.
- Read `rules/saves.md` before you change a saved format, a facts or shape field, a material record or package, or an equipment layout.

## Engine schedule and player-facing updates

- The engine publishes snapshots, acknowledgements and status changes on its own schedule, about every 5 s of online time. A client gets data only on its own request; opening a GUI replays the last snapshot, and a replay only reads cached data.
- Player inputs and network changes are queued engine events. Packet handlers, block-entity loads and menu opens never force a process update.
- Do not calculate process state on Minecraft's tick loop. No wall-clock or offline advancement.
- Read `rules/engine-schedule.md` before you write code that sends process data to a client, handles a player input or runs on the tick.

## Physics and defects

- Reason about every issue from real-life physics first, with simplifications: what flows where, and the steady state, in numbers. Report in this order: the expected behaviour; what the code did instead, in plain words; a proposal for the owner to validate.
- Every fix improves the existing structure: it goes in the stage it belongs to and removes the special cases it makes unnecessary. No isolated patches.
- A fix that rests on an assumption the physics does not force goes to the owner as options.
- Conservation of mass, energy and components stays exact in every change.
- Thermo validity: a freezing point is never a lower validity bound. Leaving a data range never holds an island; properties extrapolate, with a notice. A numerical guard is a rejected trial step, never a hold. Holds are only for capability limits.
- Small physics simplifications: propose each to the owner with its effect in numbers; implement it only after approval.
- Results that move: a change that only moves code keeps every reference exact. A moved reference is stated in the review with its old and new values and its bound.
- Read `rules/physics.md` before work on physics, solver or property code.

## Gradle, measurement and verification

- At most two Gradle invocations run at a time on the machine, each in its own worktree; the second uses at most 4 worker threads. Never run a test suite while a dev client is running.
- Every test run names its test classes with `--tests` (tasks `test`, `holdGates`, `columnGates`, `equipmentGates`). Take the classes from `node tools/merge-lane/affected.mjs select` (after `testClasses`), never a hand list or a package tree. `check`, `build`, `slowGates` and whole tasks are the final lane's alone.
- No repeated tests. A pass stands until the test or something it loads changes. Read stored results first (`build/test-results`, the lane's output, the batch review). Before a run, list the files changed since the last pass and run only the tests that can load them:
  - Documents, `tools/`, comments, `AGENTS.md`, `CHANGELOG.md`: no test run.
  - A test removed, or test code left without a user removed: `compileTestJava` only.
  - A test added or edited: that test class.
  - Production code: the tests of the changed classes and the gates that name the rule.
- Do not force a result again: no `--rerun-tasks`, `--no-build-cache`, `cleanTest` or deleted `build/`.
- Never run a test with its fix or rule disabled to prove the fix. A held seed is replayed once, alone, in the lane.
- The final lane (`bash tools/merge-lane/lane.sh head`) runs once per batch, when the code is final. No second lane, no full suite and no campaign of more than 200 seeds unless the owner asks; never propose one.
- Record and report time only in a performance optimization task. Elsewhere judge cost by counters.
- A report's summary is at most 50 lines. A findings table may follow it.
- Read `rules/measurement.md` before a campaign, a final lane, a comparison of forms, a hold diagnosis, a timed run, or a change to the lane or its test lists.

### GUI and in-game checks

- Before any GUI work, read the `gui-creation` skill and the reference it names for the job.
- Do not start a dev-client check on your own. Judge GUI work by the gates, the compile and the software renders of `tools/heat-gui-renders/`. Where nothing else can judge, offer a check and let the owner start it. At most one in-game check per task.
- Never send real desktop keyboard or mouse input unless the owner is watching.
- Read `rules/in-game-checks.md` and the `minecraft-mcp` skill before a check the owner asks for.

## New equipment

- Before you build, change or review an equipment model or a sound, read the `models-and-sfx` skill.
- Read `rules/equipment.md` before you add an equipment kind or update equipment drawings.

## Translations

- During a batch, change only `src/main/resources/assets/icheme/lang/en_us.json`. `LanguageFilesTest` and `UnitTextTest` fail until the translation commit; do not run them before it.
- Before the batch merges, make one translation commit with the `translation-commit` skill.

## Skills and hooks

- A project skill lives in `.claude/skills/<name>/`, with `.agents/skills/<name>` a tracked symlink to it for Codex. A new skill gets both. An agent with no skill loader reads `.claude/skills/<name>/SKILL.md` as a plain file.
- `tools/agent-guard/guard.js` blocks the git and Gradle commands these rules forbid; its README lists them. Do not retry a blocked command in another form.
- Do not add a `CLAUDE.md`, `.claude/CLAUDE.md` or `CLAUDE.local.md` at or above the repository root: Claude Code then stops reading this file.

## Git and worktrees

- Work only in your own worktree. Never modify another agent's worktree.
- Never use bare `git stash` or `git stash pop`. Set work aside with a WIP commit, or with a tagged stash entry restored with `git stash apply`.
- Never stage git-ignored files (`git add -f`): logs, campaign reports, gate output. Before a merge, `git ls-files -i -c --exclude-standard` is empty.

## Documentation, research and tools

- `documentation/` and `tools/` are tracked: write and commit them in your own worktree, on the task's branch, never in the main checkout. `research/` is git-ignored and exists only in the main checkout, `D:/Minecraft/Modding/1.21/CreateChemE/research/`.
- After every task, in the same session:
  - Put its documents in its batch folder, `documentation/YYYY-MM-DD-topic/`: a review is `<TOPIC>_REVIEW.md`, a plan is `<TOPIC>_PLAN.md`.
  - Update the batch's row in `documentation/INDEX.md` (folder, purpose in a few words, status with its date) and write the rest in the batch's `README.md`, with one history line per task.
  - Update `tools/INDEX.md`, or the main checkout's `research/INDEX.md`, if they changed.
  - Commit the documents and tool folders on the task's branch.
- No image, PDF, log or file over 1 MB goes into `documentation/` or `tools/`.
- Read `rules/documentation.md` before you open a batch folder, add a tool folder or a top-level folder, store a large file or a log, close a batch that never merges, or resolve an index conflict.

## Code guide

- The code guide is `documentation/reference/code-guide/`: `README.md` (what the mod calculates, the code map) and one chapter per subsystem. In an unfamiliar area read `README.md` first, then only the chapter the task needs.
- `README.md` stays under 150 lines. The guide holds no history: that goes to `CHANGELOG.md` and the batch's README.
- `documentation/reference/FORMATS.md` lists every saved and wire format with the constant that declares it. A batch that changes a format changes its row in the same commit.

## Parallel batches

- Before a batch starts beside another, read `documentation/reference/COLLABORATION.md`: lanes and hot files, how a format or schema number is reserved, the brief template, how a moved result is reported.
- Two batches never change the same file at the same time. A hot file has one owner batch at a time.

## Merging to main

- Every merge to `main` includes, in the same merge: a `CHANGELOG.md` entry, a proposed `mod_version` bump that the owner chooses, the translation commit, a code guide update and the tooling cleanup. Follow the `merge-prep` skill for each.
- Probes, rigs and scripts a batch made for measurement leave `src/main` for `tools/<tool-folder>/` before the merge.
- A documentation-only merge changes only `documentation/`, `tools/`, `AGENTS.md`, `.gitignore`, the project skills or the agent hook files. It needs none of these.
