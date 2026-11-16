# Removing the cdu17 test package (2026-11-16)

Status: Planned (2026-11-16). Not started; the removal thread waits for the owner's approval.

The test-only package `icheme:cdu17_tjl_acs2018` goes, with its fixture classes and its 40 resource files. The tests
that used it move to the game's medium crude preset (`icheme:tjl20_methane`, `icheme:medium_crude`).

Owner, 2026-11-16: "remove that test package entirely and use medium crude preset instead". The package's
extrapolated PC12 is the cause of the "cold Tia Juana second liquid" open item of
[2026-11-16-supercritical-followups](../2026-11-16-supercritical-followups/README.md).

## History

- 2026-11-16: plan written (`CDU17_REMOVAL_PLAN.md`). Scope: 37 test classes, 2 fixture classes, the compiled
  package and its test, 40 resource files, 2 gate-list lines. No `src/main` change.

## Documents

- [CDU17_REMOVAL_PLAN.md](CDU17_REMOVAL_PLAN.md): what is removed, what replaces it, the rules for moving a test,
  the cold one-liquid check of the medium crude, the runs.

## Code areas

- `src/test/java/com/wormzjl/icheme/science/column/v3/` and `.../v3/thermo/`, `science/material/`, `network/`.
- `src/test/resources/materials/cdu17-reference/`, `build.gradle` (the column gate list).
- `tools/supercritical-probes/p3`.

## Open items

- The whole plan.
