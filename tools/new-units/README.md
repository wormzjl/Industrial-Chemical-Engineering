# new-units

Purpose: the lane workflow, gates without Gradle, counts, renders and in-game check of the "new units" step: fourteen
unit kinds drawn by eight parallel design lanes (2026-10-18).
Batch: `documentation/2026-10-15-custom-equipment-rendering/` (`NEW_UNITS_PLAN.md`, `NEW_UNITS_SURVEY_*.md`,
`NEW_UNITS_REVIEW.md`). None of this was ever tracked (`tools/` is git-ignored); nothing was removed from a tracked path.

## Contents
- `KIT.md`: the brief every lane worked from: the unit frame (y 0 = grade, front at -z), nozzle and pipe-spacing rules,
  collision solids, decks, ladders, the stair primitive, moving parts, determinism, budgets (60k quads aim, 80k ceiling).
- `check.sh <lane> <KIND[,KIND]> <source files...>`: a lane's gate without Gradle. Compiles the lane's designs into
  `build/tmp/units/<lane>/out` against the last Gradle build, runs `ProcessUnitTest`, `ShapeRasterizerTest`,
  `LineGeometryTest` and `DeterministicMathSourceTest` through `TestRunner`, then `UnitCounts` for the lane's kinds.
  Run from a worktree root after `./gradlew compileJava compileTestJava` built the scaffold once. Several lanes can
  share one worktree this way while no Gradle run is allowed.
- `render.sh <lane> <KIND> <size> <length> <support> <name> [views]`: software render (`UnitRender`) of one unit to
  `build/tmp/units/<lane>/render/<name>-<view>.png`; views front, back, left, right, top, iso, iso2, iso3, low.
- `UnitCounts.java`: per kind at its smallest, middle and largest size and second size: box, shell and interior cells,
  chunk quads, moving parts, nozzles, layout time. `TestRunner.java`: a JUnit 5 runner without the platform launcher
  (the API jars from the Gradle cache).
- `lang-merge.js <en_us.json> <entries.json>...` and `lang/*.json`: each lane's keys, merged into the lang file line by
  line (an `@after` map anchors new keys; a flat key goes after the last key of its group). Order used: `scaffold`,
  then the eight lanes.
- `INTEGRATION.md`: the lanes' hand-backs: nozzle-face maps (now strict in `ProcessUnitTest`), kit gaps, owner choices,
  counts.
- The kit consolidation (2026-10-18, `KIT_CONSOLIDATION_PLAN.md`, `KIT_CONSOLIDATION_REVIEW.md`): `KIT_API.md` (the kit's
  index after it), `CONSOLIDATION_LANES.md` (the eight migration lanes' results, kit gaps and owner choices),
  `INTEGRATION_BRIEF.md` and `INTEGRATION_REPORT.md` (the single-writer step that folded the lanes' copies into the kit),
  `RenderAll.java` (min, mid and max size of each kind, `RenderAll <out dir> <views> [KIND...]`).
- `probes/`: `SceneProbe` (default second size, support and box of the in-game scene's picks), `LangProbe` (every key a
  unit's constructor rows, held line, datasheet and roles ask for, and its `%s` count), `MoveProbe` / `MoveDiff` (the
  moving-part area check that found the rod-end disc on a block face), `TextFit` (constructor and datasheet text widths
  in the vanilla font, the estimate `UnitTextTest` gates), `LineCount` (most datasheet lines per kind), `Sweep` (every size, length and support of the named kinds through the walk
  and climb, pipesApart and free pipe block checks, with each shape's quads; `sweep.sh <name> <kinds...>`, its class path
  in the script) and `Dump` (every nozzle and moving part of the dumped shapes, to diff before and after a change). Package
  `com.wormzjl.createcheme.science.equipment`; from a worktree root after a build:
  `javac -cp build/classes/java/main -d build/tmp/probe/out <file>`, then
  `java -cp "build/tmp/probe/out;build/classes/java/main;build/resources/main" com.wormzjl.createcheme.science.equipment.<Name>`.
- `in-game/`: the bridge-lane check on a fresh world (`tools/custom-equipment-rendering-poc/new-world.sh`, vanilla
  `runMcpClient` lane, `MSYS_NO_PATHCONV=1`): `scene-a.txt` (eight smaller kinds at z 0), `scene-b.txt` (six large
  kinds at z 70), `scene-constructor.txt`, `view-*.txt` and `aim-*.txt`, helpers `view.js`, `shoot.sh`,
  `UnitProbe.java` (boxes and moving-part pivots), `Diff.java` (pixel change between captures), `lang-check.js`; the
  recheck after the fixes is `r-*` (scene, views, aims, shots). Shots in `shots/`; batch copies are shots 126–137.
  The kit consolidation's check is `k-*` (`KIT_IN_GAME_REPORT.md`): `k-scene-c.txt` and `k-scene-c2.txt` after scenes
  A and B, `k-reshoot.sh` (an old `view-<name>.txt` to `shots/k-<name>.png`), `k-shoot.sh` (`k-view-*.txt`),
  `k-boxes.txt`, probes `KSceneProbe`, `KBoxProbe`, `KNozzleProbe` (package as above) and `KDiffMap` (old, new and
  changed pixels in one picture; default package).
- `logs/`: `new-units-gates-2026-10-18.tar.gz` (the four Gradle gate runs, the end-rail gate proof, the counts after
  the lanes and after the fixes), `new-units-check-2026-10-18.tar.gz` and `new-units-recheck-2026-10-18.tar.gz` (client
  and Gradle console logs of the two in-game checks), `new-units-kit-check-2026-10-18.tar.gz` (the kit check's client
  and Gradle console logs and the reshoot pixel diffs), `kit-consolidation-2026-10-18.tar.gz` (the kit consolidation's Gradle gate run and test
  XML, the combined lane check, counts before and after the integration, sweep outputs).
- `review/` (2026-10-18, the owner's review world): `review-scene.txt` (35 structures in five rows with signs: drums and
  columns, the eight older units, the fourteen new units at their middle size, the rest and largest cases; written by
  `probes/ReviewScene.java`), `overview.png`. Built through the bridge lane on a fresh superflat world, saved with Save
  and Quit, copied to `run/saves/Equipment review 2026-10-18` (LevelName renamed in `level.dat`), spawn at 20 -60 -14.

## Old-save refresh and FCC proportions (2026-10-18)
- `in-game/COMPAT_IN_GAME_REPORT.md`, `COMPAT_RECHECK_REPORT.md`: the two bridge-lane checks of the old-save equipment refresh
  (`SAVE_REFRESH_REVIEW.md`) on copies of the owner's review world; `compat-*` and `recheck-*` command files, views, shoot scripts
  and shots; `Pair.java` (side-by-side shots) and `Dark.java` (near-black pixel count); logs in
  `logs/save-refresh-2026-10-18.tar.gz` (the three Gradle gate runs and the recheck's client log).
- `fcc-proportions/`: the FCC prototype (forms A and B), its probe, the fixed-scale renderer and the montage tool; see its README.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: `od4-holds/` (2026-10-20): javac lane, quick check, trace probe and fix patch of the OD4 holds 145 and 889, logs `logs/od4-holds-2026-10-20.tar.gz`; round 2 probes `od4-holds/r2/`, logs `logs/od4-holds-r2-2026-10-20.tar.gz`; round 3 (D34 gate, owner decision E5) probes, robustness matrix and prototypes `od4-holds/r3/`, logs `logs/od4-holds-r3-2026-10-20.tar.gz`; round 4 (cold 285, a junction's own split as its own rows, prototypes P2-P2d, the 889 minimization and draft gate) `od4-holds/r4/`, logs `logs/od4-holds-r4-2026-10-20.tar.gz`; rounds 5 to 7 (flash seed `ca21bab8`, cold 729 `ff71d370`, D61 labels and final lane `416f8601` + `2def39dc`) `od4-holds/r5/` (probes `probe.pl`, `fallback-probe.pl`, `named.sh`, prototype A), logs `logs/flash-seed-ca21bab8-2026-10-20.tar.gz`, `logs/cold729-2026-10-20.tar.gz`, `logs/d61-labels-final-lane-2026-10-20.tar.gz`; round 8 (cold 994 `37797661`) `od4-holds/r6/` (probes `seed-residual.pl`, `arrive-codes.pl`, form D1), logs `logs/cold994-2026-10-20.tar.gz`, final lane `logs/final-lane-37797661-2026-10-20.tar.gz`; round 9 (cold 654 `bde9013c`) `od4-holds/r6/` (probes `entry-gap.pl`, `arrive-flows.pl`, `start-closures.pl`, `balance-probe.pl`, `unconverged-closures.pl`, forms D3, E, F), logs `logs/cold654-2026-10-20.tar.gz`; round 10 (balance convergence `a6b14b22`) `od4-holds/r7/` (probes `r7-balance-iter.pl`, `r7-rest-balance.pl`, forms G1, G2, G3), logs `logs/balance-convergence-2026-10-21.tar.gz`; round 11 (still pipe 850, `e5357594`) `od4-holds/r8/` (five `s850-*` probes, form H), logs `logs/still850-2026-10-21.tar.gz`; round 12 (cold 430 and bundled 889, `d139fe93` + `8d69949d`) `od4-holds/r9/` (four probes, forms A-D), logs `logs/cold430-889-2026-10-21.tar.gz`, `logs/final-lane-8d69949d-2026-10-21.tar.gz` and `logs/final-lane-c7b883cb-2026-10-21.tar.gz` (A reverted, c7b883cb); lane scripts `od4-holds/scripts-2026-10-21/`, merge-prep gates `logs/merge-prep-0.10.1-2026-10-21.tar.gz`; The new-units step (fourteen unit kinds, eight parallel design lanes): the lanes' drawing kit brief, a Gradle-free lane gate (`check.sh`: equipment tests and the determinism source gate through `TestRunner`), unit counts and software renders, the lane lang merge, probes (scene defaults, lang keys, moving-part area), the bridge-lane check's scenes, views and shots; the kit consolidation's lane reports (`CONSOLIDATION_LANES.md`), kit API, integration brief and report, `RenderAll`, the every-support sweep (`probes/Sweep.java`, `sweep.sh`) and the in-game `k-*` check; the old-save refresh checks (`in-game/COMPAT_*`, `compat-*`, `recheck-*`) and the FCC proportions prototype (`fcc-proportions/`)

Batch: 2026-10-15-custom-equipment-rendering

Status: In use since 2026-10-18. Index: [../INDEX.md](../INDEX.md).

### Entry points

README.md, KIT.md, check.sh, render.sh, UnitCounts.java, INTEGRATION.md, KIT_API.md, CONSOLIDATION_LANES.md, INTEGRATION_BRIEF.md, INTEGRATION_REPORT.md, RenderAll.java, probes/, in-game/, logs/

### History

- In use since 2026-10-18 (never tracked; nothing detached from a tracked path)

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 379 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/new-units/`, at the same relative paths.
