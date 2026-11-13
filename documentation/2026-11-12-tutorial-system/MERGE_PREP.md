# Merge preparation: tutorial system and still law

2026-11-13, on the owner's word, branch `claude/tutorial-system-chem-mod-950ca9` at cda7e7129 (last code commit
3063488da; the translation commit on top). Skill `merge-prep`. Documents and tools only; no Gradle run, nothing committed.

## What was done

- **CHANGELOG.md**, `[Unreleased]`: Added, the tutorial (`2026-11-12-tutorial-system`); Changed, the still law
  (`2026-11-12-still-heat-per-distillate`), the translations (cda7e7129), the tooling cleanup with the detached
  `/icheme-film` command (cace5dd6d), the code guide refresh. `[Unreleased]` was empty before.
- **Version:** proposed 0.30.0 (minor: new content and a model change, the version rule's "feature" case). The owner
  chooses; `gradle.properties` is unchanged (0.29.0).
- **Code guide:** new chapter `tutorial.md` (class map, flow, the ten criteria and their sites, data formats, caches,
  how to add a step, tour, page, film or card, gates, tools) and its row, routing row and overview line in `README.md`.
  Updated, each with a section and a snapshot line at 3063488da, 2026-11-13: `fluid-science.md` (the still law; a stale
  line on the deleted critical-temperature rule corrected), `fluid-runtime.md` (the waiting fire, the outlet floor, the
  criteria in `FluidNetwork`), `process-simulator.md` (the small crude unit, `ranOnBody`, `sheet_run`, `design_sent`, the
  tours, `StillBatch`), `machine-assemblies.md` (the assembly tours and criteria), `client-gui.md` (the `?` key on every
  screen, the templates' count), `equipment.md` (`stillParts`, the reference items), `verification.md` (the tests, two
  slow gate classes, `TutorialUnitGameTests`).
- **Code guide: no change** for column-v3, column-operation, cooling-towers, effects, equilibrium-engine, heat-exchangers,
  instrument-accessories, pipe-laying, process-control, rotating-machinery, thermo-materials, wired-control-power: the
  branch changed none of their code (screens of theirs that gained a tour are in client-gui).
- **Tooling cleanup:** `perl tools/merge-lane/main-reach.pl .`: 857 classes, 4 not reached: `IChemEJeiPlugin` (this
  branch; JEI loads it by `@JeiPlugin`, the game reaches it: stays), `DhBridge`, `DhGroups`, `IrisBridge` (main's
  effects batch; loaded by reflection from `FxCompat`: not this branch's). Nothing to move.
- Tool folders: `tools/tutorial-renders/` (entry points, the cards' path, history; the detached command stored as
  `film-command/FilmCommand.java.txt` and `attach.patch`, `git apply --check` passes on this tree),
  `tools/tutorial-recordings/` (entry points, history), `tools/tutorial-column/` (entry points, history, the `src/`
  sentence corrected); `tools/heat-gui-renders/` already had its line. `tools/INDEX.md` rows shortened.
- No probe, recorder, rig or `-P` switch left in `src/main`, `build.gradle` or the batch folders. The recorders in
  `src/test` (`StillRunRecorder`, `StillTraysRecorder`, `SmallUnitRecorder`) stay: their gates rerun them.
- `git ls-files -i -c --exclude-standard`: empty. Both batch folders hold only `.md` files, the largest 25.5 kB
  (`TUTORIAL_SYSTEM_PLAN.md`); no image or large file.
- Batch READMEs: tutorial code areas made current, documents list completed, history lines; the still README's status
  names the tutorial branch, its open item 4 closed.

## Left for the merge itself

- The owner's version choice into `gradle.properties`; `[Unreleased]` to `## [<version>] - <merge day>`.
- `documentation/INDEX.md`: both rows to Implemented with the merge date (left "In progress" here).
- The final lane, once, on the final code (Opus): `bash tools/merge-lane/lane.sh head`. It must include the affected
  classes (with `LanguageFilesTest`, and `UnitTextTest` in `equipmentGates`, after the translation commit), `holdGates`
  `SmallCrudeUnitTest` and `SmallUnitRecordingTest`, and the fluid GameTests (`TutorialUnitGameTests`' first run;
  `BatchStill.java` changed). This preparation adds no test run: documents and tools only.
- Code comments found stale, not changed (no Java edits here): `Films` names "the P0 command"; `StillTray` names
  `StillTrayFilmTest` (the class is `TowerFilmsTest`).
</content>
</invoke>
