# Merge preparation review: column failure modes

2026-11-10. Owner: "claude/custom-shader-system-caff9e will merge first, prepare". Branch `claude/distillation-failure-mode-control-db2da5`, 37 commits on main 0.24.0 (`79d0a6254`), then the commits below.

## The shader branch, merged in (`16829d22e`)

- `claude/custom-shader-system-caff9e` at `e07461dee` (113 commits, six batches, its merge prep for 0.25.0 done) is merged into this branch.
- `main` at 0.25.0 (`833942033`, 11 commits more: the shader branch's final-lane fixes) is merged in at `33d018c0f`. Two code guide snapshot lines resolved (this batch's line, main's newer snapshot as its earlier part); `PassiveStepSolver` (main's closed-device rule, this batch's heating branch) and the lang files merged by git; main changed no format number. The three source sets compile; `check.js` OK 3455 keys per file.
- 21 files changed on both sides. Git merged 11 by itself. Resolved by hand:
  - `FluidFacts`: both sides took schemas 17 and 18. The shader branch's 17 to 20 stand (ejector, rigid vessels, vacuum pump, prime movers), with its corpus images. This batch's two steps (utility sides, powered members) are one schema 21, as no build on `main` ever wrote them apart. Corpus `compat/fluid/schema-21/` (`45039eac9`), written on the merged code.
  - `EquipmentAlarm`, `BuiltInInputs`: the chimney's and the column's alarms both kept (saved by name; order free).
  - `documentation/INDEX.md`: every row of both sides.
- The two sides append to different records: the shader branch added no field to a column unit or its state, so no field order is contested.

## Formats after the merge

| Format | Shader branch | This batch | Merged |
|---|---|---|---|
| Fluid facts schema | 17 to 20 | (17, 18) → 21 | 21 |
| Solved state format | 43 to 46 | none; an older column state is dropped and solved again | 46 |
| Protocols | `fluid-23`, `equipment-23`, `flare-2`, `machine-4` | `assembly-6`, `control-4` | all six |
| Simulator results format | none | 3 | 3 |

## Checks made

- `compileJava compileTestJava compileFluidGameTestJava` on the merged tree: green.
- `FluidFactsCompatTest`: 18 of 19 pass before the image (the one failure asks for it), 19 of 19 with it; the 21 older images rebuild, the shader branch's four included.
- `perl tools/merge-lane/main-reach.pl .`: three unreachable classes, all the shader branch's optional Iris and Distant Horizons bridges; none of this batch.
- Not run: any other test on the merged tree. The final lane runs them.

## Checklist

- **CHANGELOG**: one Added line and three Changed lines under `[Unreleased]`, above `[0.25.0]`; the 0.25.0 section equals main's.
- **Version**: 0.26.0 proposed (a feature and model batch: minor). `gradle.properties` is not changed; the owner chooses.
- **Translations**: `0893d2339`. 52 new keys, one rewritten, one removed, in zh_cn, fr_fr, es_es, ja_jp; the five new alarm words stay in English. `check.js`: OK 3453 keys per file. `status-words.js` names none of this batch's keys. Widths outside the constructor and vessel screens are not gated; long labels were shortened by hand.
- **Tooling cleanup**: `tools/column-failure-modes/` (README, the dropped W3.4 patch, the full-size run's case lines, the merge scripts) and its `tools/INDEX.md` row. The test world stays in `src/fluidGameTest` (`runReviewWorldServer -PreviewBatch=review-failure-modes`). Nothing left `src/main` or `src/test`; no gate widened or narrowed; no test run needed for it.
- **Code guide**: updated by an Opus subagent (owner approved), checked against the code and committed: 13 files, snapshot `0893d2339`. `column-operation.md` holds the scheme; `process-control.md`, `wired-control-power.md`, `fluid-runtime.md`, `heat-exchangers.md`, `fluid-science.md`, `rotating-machinery.md`, `client-gui.md`, `machine-assemblies.md`, `verification.md` and the README carry their parts; `column-v3.md` and `process-simulator.md` one line each. Facts schema 21 throughout. Three statements are taken from the batch README and marked so (pump power at 1.4 times, the 88/88 GameTests before the merge, the glass sizes).
- **Ignored files**: `git ls-files -i -c --exclude-standard` is empty.
- **Final lane**: run once on `1d872d2f9`, passed; see [FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md) (the orchestrator checked its counts against the lane folder). The selection at `33d018c0f` (`affected.mjs select`, 107 files changed since main): `test` 231 of 374 classes, `holdGates` 12 of 12, `columnGates` 11 of 82, `equipmentGates` 26 of 28, chain-100, the fluid GameTests and the campaign (1000 seeds, 8 workers; this batch changed `runtime/`, `science/fluid/` and `science/column/operation/`). The baseline is the 0.25.0 lane.

## Open before the merge

1. `mod_version` (0.25.0 now) and the CHANGELOG heading, at the version the owner chooses.
2. The owner's reading of two lane notes: `MIXED_GAS_COST` Newton solves 297 to 313 (+5 %) and 8 of 33 junction lines with 1 to 3 more Newton solves; the lane cannot tell this batch's heating branch from main's closed-device rule, as the baseline predates both.
3. The owner's manual test. The world was saved before this merge. The merged code should read it (fields are read by presence), but it was not loaded on the merged code. A rebuild on the merged code takes 15 minutes.
4. Owner decisions carried from the batch README: the `NO_DRIVER` Start refusal, the version.
5. In the main checkout: the `research/INDEX.md` row of `renders-w7/` and the log archive of `tools/column-failure-modes/logs/`.
