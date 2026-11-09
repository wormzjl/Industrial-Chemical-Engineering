# package-rework

Purpose: the probes and one-off scripts of the package rework:
- the before/after snapshot probe that showed W1 moved no physics;
- the data migrations to the method-plus-compound-list schema;
- the neural sidecar's move to schema 3;
- the menu-wire width probe behind the world list's cap of 100 entries.

Batch: [2026-11-04-refinery-compounds](../../documentation/2026-11-04-refinery-compounds/README.md), plan packages W1, W2 and V2 (PACKAGE_REWORK_PLAN.md, VARYING_COMPONENTS_REVIEW.md).

Status: Kept for reference since 2026-11-04. Index: [../INDEX.md](../INDEX.md).

The build neither compiles nor runs these files. The scripts have already run, and their results are in the batch's commits. Each one keeps the CRLF line endings of the files it rewrites.

## Entry points

| File | What it does | How to run |
|---|---|---|
| `PackageReworkProbe.java` | JUnit probe. Writes everything each bundled package resolves to (components, datasets, pairs, water, ranges, assays) to `build/package-rework/snapshot.json`. | Copy it to `src/test/java/com/wormzjl/icheme/science/material/`, run `./gradlew test --offline --tests com.wormzjl.icheme.science.material.PackageReworkProbe`, then remove it. |
| `jsondiff.mjs` | A structural diff of two probe snapshots. It prints each path that differs, with both values. | `node jsondiff.mjs <before.json> <after.json>` |
| `migrate-data.mjs` | W1: moves the bundled material records to methods and compound lists, with component defaults and free assays. | `node migrate-data.mjs <materials dir> <materials-index.json>` |
| `migrate-cdu17.mjs` | W1: the same migration for the CDU17 literature fixture (`src/test/resources/materials/cdu17-reference`). | `node migrate-cdu17.mjs <fixture root>` |
| `migrate-tjl-reference.mjs` | W1: the same migration for the frozen TJL reference set (`src/test/resources/column/v3/tjl-reference-resources.json`). | `node migrate-tjl-reference.mjs <file>` |
| `repin-sidecar.mjs` | W1 bridge: re-pins the regrouped model's sidecar and the registry's `pipelineSha256`. `sidecar-schema3.mjs` supersedes it. | See its header. |
| `sidecar-schema3.mjs` | W2: moves the regrouped model's sidecar to schema 3 (the trained physics), then recomputes the registry's `pipelineSha256`. The files are `-text` and CRLF. | See its header. |
| `WireWidthProbe.java` | V2 stage 5: finds the widest world list the fluid-17 menu wire carries under `MAX_JSON`. It measured 104 entries; the cap is 100. | Copy it to `src/test/java/com/wormzjl/icheme/runtime/fluid/`, run that class, then remove it. |

## History

- 2026-11-04: folder created at the batch's tooling cleanup, from the batch's scratch folder. `PackageReworkProbe.java` was never committed under `src/test`; it moved here before the final lane so that `test` does not run it. The one-off edit helpers, code fragments and Gradle logs stayed in the scratch folder.
