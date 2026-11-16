# Removing the cdu17 test package (2026-11-16)

Status: Implemented 2026-11-16 (0.34.2). Branches `claude/priceless-carson-4f1d4c` (the removal) and
`claude/supercritical-fluid-support-d0ffa4` (the plan, the comments, the version), base `ee13d1034` (main 0.34.1).

The test-only package `icheme:cdu17_tjl_acs2018` goes, with its fixture classes and its 40 resource files. The tests
that used it move to the game's medium crude preset (`icheme:tjl20_methane`, `icheme:medium_crude`).

Owner, 2026-11-16: "remove that test package entirely and use medium crude preset instead". The package's
extrapolated PC12 is the cause of the "cold Tia Juana second liquid" open item of
[2026-11-16-supercritical-followups](../2026-11-16-supercritical-followups/README.md).

## History

- 2026-11-16: plan written (`CDU17_REMOVAL_PLAN.md`). Scope: 37 test classes, 2 fixture classes, the compiled
  package and its test, 40 resource files, 2 gate-list lines. No `src/main` change.
- 2026-11-16: removal done (`c00fde5dc`..). Step 0: the medium crude is one liquid at all 24 cold states, so the
  cold Tia Juana second-liquid item closes. 35 classes moved, `V3Cdu17TiaJuanaPackageTest` deleted, none disabled.
  The binary became `crude_pc02`/`crude_pc06` with a 0.2 MW reboiler. Selected run on the final helpers:
  `columnGates` 44 classes, `test` 5, `nativeSmoke` 1; 360 tests, 0 failures. `CDU17_REMOVAL_REVIEW.md`.
- 2026-11-16: the owner's decisions and the merge, by the main thread. "Approve" (the removal thread, Opus high); asked
  for the merge preparation and a final lane, the owner answered "ONLY TEST RELEVENT". Read as: no lane beyond the tests
  the change reaches. Those ran once on the final test code (360 tests, 0 failures; `CDU17_REMOVAL_REVIEW.md`, "Runs");
  what followed them is comments (`44efaaaad`, the two `V3FeatureRamp` lines), documents, the CHANGELOG and the version,
  which no test loads, so no test ran again and no final lane ran. Version 0.34.2 (patch: tests only) and the merge to
  `main`. The merge also carries the lane fixes' last documents commit (`dffd7a19b`).

## Documents

- [CDU17_REMOVAL_PLAN.md](CDU17_REMOVAL_PLAN.md): what is removed, what replaces it, the rules for moving a test,
  the cold one-liquid check of the medium crude, the runs.
- [CDU17_REMOVAL_REVIEW.md](CDU17_REMOVAL_REVIEW.md): step 0's table, one row per class, the printed lines, the runs.

## Code areas

- `src/test/java/com/wormzjl/icheme/science/column/v3/` and `.../v3/thermo/`, `science/material/`, `network/`.
- `src/test/resources/materials/cdu17-reference/`, `build.gradle` (the column gate list).
- `tools/supercritical-probes/p3` (`P3MediumCrudeProbe`), `tools/vacuum-column-test` (`capture-roots.sh`).
- `src/test/resources/column/invalid-binary-roots.json`, `src/test/resources/materials/dwsim-*viscosity-api.json`.

## Open items

- `affected.mjs` selects every `MaterialCatalog` user when a file under `materials/` is deleted (path heuristic).
- The next final lane records the moved column tests' reference lines: no lane ran for this batch.
