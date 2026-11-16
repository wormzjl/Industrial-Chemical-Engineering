# Removing the cdu17 test package (2026-11-16)

Status: In progress since 2026-11-16. The removal is done on branch `claude/priceless-carson-4f1d4c`; not merged.

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

- `V3FeatureRamp.java` lines 62 and 557 (`src/main`) still name the CDU17 cases in comments; owner decision.
- `affected.mjs` selects every `MaterialCatalog` user when a file under `materials/` is deleted (path heuristic).
- The next final lane records the moved column tests' reference lines.
- Merge preparation (changelog, version, code guide check), on the owner's word.
