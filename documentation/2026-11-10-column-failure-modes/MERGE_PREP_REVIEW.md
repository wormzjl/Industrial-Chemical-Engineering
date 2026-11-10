# Merge preparation review: column failure modes

2026-11-10. Owner: "claude/custom-shader-system-caff9e will merge first, prepare". Branch `claude/distillation-failure-mode-control-db2da5`, 37 commits on main 0.24.0 (`79d0a6254`), then the commits below.

## The shader branch, merged in (`16829d22e`)

- `claude/custom-shader-system-caff9e` at `e07461dee` (113 commits, six batches, its merge prep for 0.25.0 done) is merged into this branch. If it gains commits before it reaches `main`, merge it again; then merge `main`.
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

- **CHANGELOG**: one Added line and two Changed lines under `[Unreleased]`, below the shader branch's. When 0.25.0 lands, they move into a new `[Unreleased]`.
- **Version**: 0.26.0 proposed (a feature and model batch: minor). `gradle.properties` is not changed; the owner chooses.
- **Translations**: `0893d2339`. 52 new keys, one rewritten, one removed, in zh_cn, fr_fr, es_es, ja_jp; the five new alarm words stay in English. `check.js`: OK 3453 keys per file. `status-words.js` names none of this batch's keys. Widths outside the constructor and vessel screens are not gated; long labels were shortened by hand.
- **Tooling cleanup**: `tools/column-failure-modes/` (README, the dropped W3.4 patch, the full-size run's case lines, the merge scripts) and its `tools/INDEX.md` row. The test world stays in `src/fluidGameTest` (`runReviewWorldServer -PreviewBatch=review-failure-modes`). Nothing left `src/main` or `src/test`; no gate widened or narrowed; no test run needed for it.
- **Code guide**: not done. Chapters this batch's code touches: `column-operation.md`, `process-control.md`, `fluid-runtime.md`, `heat-exchangers.md`, `rotating-machinery.md`, `fluid-science.md`, `client-gui.md`, `verification.md` and the README. `fluid-runtime.md` and `verification.md` name schema 20 as the newest.
- **Ignored files**: `git ls-files -i -c --exclude-standard` is empty.
- **Final lane**: not run. It runs once, alone on the machine, after the shader branch is on `main` and `main` is merged in: then the lane's selection is this batch's changes only and the shader branch's lane is its baseline. This batch changed `runtime/`, `science/fluid/` and `science/column/operation/`, all in `campaign-inputs.txt`, so the lane runs the campaign (1000 seeds, 8 workers) and the fluid GameTests.

## Open before the merge

1. The code guide update.
2. The shader branch on `main`; `main` merged into this branch (CHANGELOG headings, `mod_version`).
3. The final lane, on Opus.
4. The owner's manual test. The world was saved before this merge; the merged code reads it. A rebuild on the merged code takes 15 minutes.
5. Owner decisions carried from the batch README: the `NO_DRIVER` Start refusal, the version.
6. In the main checkout: the `research/INDEX.md` row of `renders-w7/` and the log archive of `tools/column-failure-modes/logs/`.
