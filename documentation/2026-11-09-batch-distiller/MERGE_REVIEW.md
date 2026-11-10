# Merge review: 0.24.0

Branch `claude/furnace-fuel-power-requirements-f7a46e`, three batches: `2026-11-09-furnace-fuel-power` (burners, fan
power, equipment alarms), `2026-11-09-flare-stack` and `2026-11-09-batch-distiller`. Main 0.23.6 merged in
(`3da3fa78b`). The owner, 2026-11-10: "merge when these are fixed", version 0.24.0 (a minor bump: new equipment).

## Merge preparation

- Main merge `3da3fa78b`: main's facts schema 15 is the build facts, so the stills became schema 16, written after them;
  main's `schema-15` image kept, a new `schema-16` image (`851f3a5c2`); stale comments `7be61a4b9`.
- `UnitTextTest`: the flare's datasheet duty line measured 417 of 411 px; shortened (`19364adba`).
- Translations `52415da92`: 236 new and 3 changed keys in four languages; `check.js` OK, status and alarm words in
  English, every new unit line within `UnitTextTest`'s widths; zh_cn's 7 capitalisation-only changes need no new text.
- Tooling cleanup `ebaad1be0`: the B3 comparison (`BubblePointColumn`, `BatchColumnComparisonTest`: its sanity case
  `aNaphthaPotRectifies` and the comparison) to `tools/batch-still-column/`. Other tool folders: `heating-values/`,
  `still-sprites/`, edits to `heat-gui-renders/`, `equipment-drawings/`, `pump-sprites/`. `main-reach.pl`: 698 of 698.
- Code guide `e7d2889af`: ten chapters and the README at `7be61a4b9`.
- Batch READMEs `995c681b3`; CHANGELOG `38e695dd9`; `mod_version` 0.24.0 and the `[0.24.0]` heading: the commit that adds
  this file. `git ls-files -i -c --exclude-standard`: empty.

## Final lane

`bash tools/merge-lane/lane.sh head` at `38e695dd9`, alone on the machine (the dev client closed first). `build.gradle`
changed (`Flare*` joined `equipmentGates`), so every task ran whole: 3062 tests (`test` 341, `holdGates` 12,
`columnGates` 82, `equipmentGates` 25 classes), 1 failure; `LanguageFilesTest` and `UnitTextTest` passed; the test tree
compiled without the B3 harness.
- Baseline (in brackets): the 0.23.5 lane. chain-100 exact. Junction lines 33 of 33 identical, allocation bytes aside;
  `MIXED_GAS_COST` 300 Newton solves, 164.2 MB (164.2 MB). Fluid GameTests (run id `lane-1110-024`): 87 of 87.
- Campaign, 1000 seeds at 8 workers: cold random 992 (992), bundled random 990 (990), bundled defaults 1000 (1000);
  hold classes identical; Newton solves cold 302.9k (296.6k), bundled 182.7k (184.7k), defaults identical.
- The failure: `NameplateTest`, the 500 L electric still without a condenser had no plate. Expected: a named structure
  carries its plate on a face flat within 0.08 m across 0.5 m, clear for 0.5 m. Found: its pot and drum skirt are one
  0.86 m cylinder, which bows 0.080 m across the plate, the tried positions miss its axis since the four-wide layout
  (0.5 m off the box middle), and the terminal box was 0.36 m wide. Owner chose A: the terminal box 0.52 m wide; the
  plate sits on its outward face at 0.4 m, the sockets stay in their cells (`68bc05675`). After it: `equipmentGates`
  `NameplateTest`, `EquipmentLayoutGoldenTest`, `EquipmentServicesTest`, `EquipmentShapeCompatTest`, `ProcessUnitTest`,
  `ShapeRasterizerTest`, `CrossflowEquipmentTest`, `ConduitGeometryTest` and `test` `StillDoorTest`, `StillRuntimeTest`
  (every class that builds a still): 115 pass. No GameTest builds a still. No second lane.

## Open items carried to main

- Batch distiller: the W7 owner questions, the pot volume recheck (feedback item 4), the engine options not built, the
  still-models style mapping; an in-game check only on the owner's request ([README.md](README.md), open items).
- Flare stack: the owner's review of the as-built deviations; the delayed coker's off-centre nozzles
  ([../2026-11-09-flare-stack/README.md](../2026-11-09-flare-stack/README.md)).
- Fired heaters: O3 (the burners' void page), O4 (render approval), O5 (the simulator's heater report shows no fuel or
  fan power) ([../2026-11-09-furnace-fuel-power/README.md](../2026-11-09-furnace-fuel-power/README.md)).

## Merge

- Merged to `main` as 0.24.0 on 2026-11-10 (owner: "merge when these are fixed"). Not pushed.
- Lane outputs: the main checkout, `tools/merge-lane/logs/batch-distiller-final-lane-logs-2026-11-10.tar.gz` (local); this
  lane is the next merge's baseline.
