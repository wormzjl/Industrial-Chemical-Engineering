# Large files under tools/: plan

Batch `2026-11-15-tools-large-files`, phase 1 (inventory and proposal). Nothing tracked changes in this phase.

## Summary

- Rule (AGENTS.md, "Documentation, research and tools"): a tool folder holds scripts and small inputs, each file under 1 MB. Images, PDFs, world region files and `*-logs/` folders are git-ignored and live in the main checkout at the same path under `research/`. On 2026-11-02 this was done for `documentation/` only (`.gitignore`, the `/documentation/**` lines); nothing covers `/tools/**`.
- Measured on `main` at `e9edc974b` (2026-11-15): `tools/` holds 15,015 tracked files, 515.9 MB; 76 of them are over 1 MB.
- In scope (over 1 MB, an image, a PDF, an `.mca`, a `.jfr`, a `.dll`/`.pyd`/`.bin`, a Gradle results file, and the rest of the world or package folders they belong to): 9,542 files, 457.2 MB.
- Readers were found by grep over `build.gradle`, `tools/development.gradle`, `tools/merge-lane/`, `src/main`, `src/test`, `.claude/skills/`, AGENTS.md and every script in `tools/`, and by reading the folders' READMEs. No build, Gradle task or test reads any file this plan moves. The references in `src/main` and `src/test` (to `junction-holdup-prototype`, `phase-ports-probes`, `custom-equipment-rendering-poc`) are comments that name the folder. `tools/merge-lane/` reads `phase-ports-probes/hold-fuzz/` and `floating-roof-tanks/final-lane/` scripts, which stay.
- `tools/equipment-drawings/` and `tools/heat-gui-renders/` track no images: no check compares against a tracked render.

| Proposal | Files | MB |
|---|---:|---:|
| Move, no reader (images, Gradle results, profiler recordings, MCP check worlds, session transcripts, probe and lane outputs, retired models) | 8,427 | 373.0 |
| Owner choice O1: unified-thermo test-world template (move recommended) | 47 | 5.3 |
| Owner choice O2: benchmark `.jsonl` inputs (move recommended) | 6 | 11.8 |
| Owner choice O3: neural case maps and model card (move recommended) | 3 | 17.0 |
| Owner choice O4: vendored numpy and soundfile (requirements file recommended) | 1,011 | 43.0 |
| **Leaves the tree with the recommendations** | **9,494** | **450.1** |
| Owner choice O1: fluid in-game rig template (keep recommended) | 42 | 5.3 |
| Keep: `minecraft-mcp-bridge/` (2 jars of 0.88 MB; `build.gradle` `runMcpClient` and AGENTS.md name the pinned one) | 5 | 1.8 |
| Keep: `wti-pumping-rate-hold/cold431-trace/evidence/baseline-tick2158-checkpoint` (4 kB, read by `trace.py`) | 1 | 0.0 |

`tools/` after phase 2 with the recommendations: about 5,520 files, about 66 MB.

What stays over 1 MB with the recommendations: the three region files in `tools/fluid-in-game-rig/template/bench-template/region/` (1.06, 1.16 and 1.73 MB), because `run-client.js` and `run-server.js` copy that world into `run/saves/` for every rig run. Outside `tools/`: `src/main/resources/data/icheme/neural/v3-column-transformer-open.payload.json` (1.7 MB), a byte-pinned production resource.

**History.** `git rm --cached` removes files from the tree and from later checkouts only. The repository's history keeps every old blob: the `.git` folder is about 755 MB (pack 692 MiB) and stays that size. Shrinking it needs a history rewrite (`git filter-repo` and a force push), which changes every commit id. It is not proposed.

## Owner choices

**O1. The two world templates (`fluid-in-game-rig/template/`, `unified-thermo-port-test-world/world-template/`).** The four region files of both are the same blobs (the test world is a copy of the rig template plus a datapack, as its README says), so git stores them once: removing one saves 5.3 MB of checkout and nothing in history.
- A (recommended): keep the rig template tracked, its scripts read it; move the test-world template whole to `research/tools/unified-thermo-port-test-world/world-template/` and point its README at that path. Needs a negation line in `.gitignore` for the rig template.
- B: keep both tracked (5.3 MB more checkout).
- C: move both; `run-client.js` and `run-server.js` then read the template from the main checkout's `research/` path.

**O2. Benchmark inputs (`benchmark-population/v1/{train,validation,historical-test}/*-inputs.jsonl`, `transformer-promotion/inputs/validation-inputs.jsonl`; 11.8 MB).** Offline Python harnesses read them (`benchmark-population/harness.py`, `prepare_populations.py`, `capacity-followup/capacity_register.py`, `decoder-floor-followup/floor_*.py`); `v1/manifest.json` already pins each file's sha256, so the manifest is the small stand-in.
- A (recommended): move them; the manifest stays; each reader resolves the path in `tools/` first, then in the main checkout's `research/tools/`, and checks the sha256 from the manifest (one shared path helper). No build or test runs these scripts.
- B: keep them tracked as the frozen inputs of a reproducible benchmark, an exception to the 1 MB rule stated in `tools/INDEX.md`.

**O3. Neural case maps and model card (`neural/gen3-case-map.jsonl.gz` 7.8 MB, `neural/generalized-case-map.jsonl.gz` 3.2 MB, `neural/gen3-model-card.json` 6.0 MB).** `benchmark-population/evidence.py` and the `v1` manifest and exclusion files name the case maps with their sha256; the model card is read only by documents (its `.gitattributes` `-text` line then goes). `tools/development.gradle` reads the `tools/neural` Java sources and `tjl19-pilot-input.json`, which stay.
- A (recommended): move all three, with the path helper of O2.
- B: keep them tracked.

**O4. Vendored Python packages (`pump-compressor-controls/python-deps/`, 1,011 files, 43.0 MB: numpy 2.5.4, soundfile 0.14.0, cffi 2.1.1, pycparser 3.12, typing_extensions 4.16.0, built for CPython 3.12 win_amd64).** Eight sound scripts (`process-sounds/*.py`, `machine-sounds/make_loops.py`) put the folder on `sys.path`; the `models-and-sfx` skill names it.
- A (recommended): add `tools/pump-compressor-controls/requirements.txt` with those five pins, move the folder whole to `research/tools/pump-compressor-controls/python-deps/`, and change the skill line: `pip install --target tools/pump-compressor-controls/python-deps -r tools/pump-compressor-controls/requirements.txt` rebuilds it (the folder is then git-ignored), or the scripts use an installed numpy. The `sys.path` line stays and does nothing when the folder is absent.
- B: keep it tracked.

## Proposed `.gitignore` lines

After the `/documentation/**` block:

```gitignore
# Large files stay out of tools/ (AGENTS.md): they live in the main checkout's research/tools/<folder>/.
/tools/**/*.png
/tools/**/*.jpg
/tools/**/*.jpeg
/tools/**/*.gif
/tools/**/*.webp
/tools/**/*.bmp
/tools/**/*.pdf
/tools/**/*.mca
/tools/**/*.jfr
/tools/**/*.dll
/tools/**/*.pyd
/tools/**/*-logs/
/tools/**/TEST-*.xml
/tools/**/binary/
/tools/**/mcp-fixture/
/tools/junction-holdup-prototype/run*.xml
/tools/session-transcripts/
/tools/phase-ports-probes/**/scenarios.txt
/tools/wti-pumping-rate-hold/fix-final-lane/all-printed-references.txt
/tools/neural/retired/resources/
# The fluid in-game rig copies its template world (run-client.js, run-server.js).
!/tools/fluid-in-game-rig/template/**/*.mca
```

With the recommended choices, also:

```gitignore
/tools/unified-thermo-port-test-world/world-template/
/tools/benchmark-population/v1/**/*.jsonl
/tools/transformer-promotion/inputs/*.jsonl
/tools/neural/gen3-case-map.jsonl.gz
/tools/neural/generalized-case-map.jsonl.gz
/tools/neural/gen3-model-card.json
/tools/pump-compressor-controls/python-deps/
```

The `*.mca` negation works because only files, not their folders, are ignored there. `git ls-files -i -c --exclude-standard` must be empty after phase 2.

## Phase 2 steps (after the owner's go-ahead)

1. Copy each file to `D:/Minecraft/Modding/1.21/CreateChemE/research/tools/<same relative path>`; check the size and SHA-256 of every copy against the worktree file; only then `git rm --cached`. A refused write to the main checkout stops this step: the files stay, and a copy script in `tmp/tools-large-files/` and its command go to the owner.
2. Add the `.gitignore` lines (one commit).
3. One commit per large folder (`junction-holdup-prototype`, `new-units`, `custom-equipment-rendering-poc`, `gauge-accessories`, `accessory-refinements`, `wti-pumping-rate-hold`), one per kind for the rest (images, Gradle results, worlds and outputs), one per owner-choice item.
4. Links in READMEs to moved files point at the `research/` path (48 `.md` files under `tools/` name an image, a world or a `TEST-` file). `tools/INDEX.md` and the main checkout's `research/INDEX.md` are patched row by row.
5. No Gradle run: no build or test reads a moved file. The O2/O3 path helper is offline Python, checked by one reader run on the Windows Python (python in Git Bash is a stub).

## Notes

- `phase-ports-probes/wp1/src/cmp.py` names `junction-holdup-prototype/run156-gates/TEST-*.xml` under another machine's scratchpad path; it reads no file of this checkout.
- The five `scenarios.txt` under `phase-ports-probes/` are one blob (byte-identical probe output, as the phase-ports review records); their `sha256.txt` files stay.
- Small evidence `.jsonl.gz` files (`decoder-floor-followup`, `lnn-gap`, `neural-budget`, `transformer-promotion/evidence`, each under 1 MB) are small inputs and stay.

## Inventory by tool folder

From `git ls-tree -r -l HEAD -- tools` on `e9edc974b`; sizes in MiB. "Move" is to `research/tools/<folder>/` at the same relative path. `run*-gates/` folds the numbered lane folders of `junction-holdup-prototype` into one row; "whole folder" rows count every file of a world or package folder.

### `tools/junction-holdup-prototype/` (3186 files, 106.4 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `./` | 217 | 42.76 | .xml | nothing (archived Gradle results of a past lane) | move |
| `run*-gates/` | 2955 | 57.85 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
| `./` | 4 | 5.18 | .jfr | nothing (archived Gradle results of a past lane) | move |
| `wp4-ingame/` | 10 | 0.62 | .png | nothing (README or review links only) | move |

### `tools/new-units/` (379 files, 83.8 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `in-game/` | 378 | 83.63 | .png | nothing (README or review links only) | move |
| `review/` | 1 | 0.12 | .png | nothing (README or review links only) | move |

### `tools/custom-equipment-rendering-poc/` (344 files, 54.1 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `aux-check/` | 24 | 4.45 | .png | nothing (README or review links only) | move |
| `aux-layout/` | 12 | 0.23 | .png | nothing (README or review links only) | move |
| `details-check/` | 22 | 2.77 | .png | nothing (README or review links only) | move |
| `device-check/` | 17 | 1.77 | .png | nothing (README or review links only) | move |
| `drum-check/` | 6 | 0.78 | .png | nothing (README or review links only) | move |
| `machines-check-2/` | 28 | 5.49 | .png | nothing (README or review links only) | move |
| `machines-check-3/` | 24 | 7.93 | .png | nothing (README or review links only) | move |
| `machines-check-4/` | 9 | 3.43 | .png | nothing (README or review links only) | move |
| `machines-check-5/` | 33 | 5.48 | .png | nothing (README or review links only) | move |
| `machines-check/` | 27 | 5.25 | .png | nothing (README or review links only) | move |
| `perf-check/` | 11 | 1.46 | .png | nothing (README or review links only) | move |
| `polish-check/` | 33 | 2.62 | .png | nothing (README or review links only) | move |
| `process-check/` | 28 | 3.57 | .png | nothing (README or review links only) | move |
| `skirt-check/` | 32 | 3.02 | .png | nothing (README or review links only) | move |
| `supports-check/` | 21 | 3.04 | .png | nothing (README or review links only) | move |
| `textures/` | 1 | 0.14 | .png | nothing (README or review links only) | move |
| `units-check/` | 16 | 2.67 | .png | nothing (README or review links only) | move |

### `tools/pump-compressor-controls/` (1011 files, 43.0 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `python-deps/` | 1011 | 43.02 | whole folder | 8 sound scripts (`process-sounds/*.py`, `machine-sounds/make_loops.py`) put it on `sys.path`; skill `models-and-sfx` | **owner: O4** (requirements file, recommended) |

### `tools/neural/` (6 files, 22.4 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `./` | 2 | 11.00 | .gz | `benchmark-population/evidence.py` and `v1/manifest.json` (case maps, sha256 pinned); model card: docs only | **owner: O3** (move, recommended) |
| `./` | 1 | 5.96 | .json | `benchmark-population/evidence.py` and `v1/manifest.json` (case maps, sha256 pinned); model card: docs only | **owner: O3** (move, recommended) |
| `retired/` | 3 | 5.41 | .json | nothing (models removed from production) | move |

### `tools/gauge-accessories/` (629 files, 21.2 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `integration/` | 570 | 4.38 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
| `integration/` | 12 | 4.29 | binary/ (output.bin, results.bin, .idx) | nothing (archived Gradle results of a past lane) | move |
| `./` | 10 | 1.66 | .png | nothing (README or review links only) | move |
| `mcp-fixture/` | 31 | 10.78 | whole folder | nothing in a build, test or script; a hand-copied MCP check world | move the whole world folder |
| `preview-history/` | 6 | 0.09 | .png | nothing (README or review links only) | move |

### `tools/accessory-refinements/` (43 files, 17.3 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `./` | 11 | 1.41 | .png | nothing (README or review links only) | move |
| `mcp-fixture/` | 32 | 15.88 | whole folder | nothing in a build, test or script; a hand-copied MCP check world | move the whole world folder |

### `tools/wti-pumping-rate-hold/` (878 files, 14.7 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `cold430-validation/` | 281 | 2.19 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
| `cold430-validation/` | 6 | 2.14 | binary/ (output.bin, results.bin, .idx) | nothing (archived Gradle results of a past lane) | move |
| `cold431-trace/` | 1 | 0.00 | .bin | `cold431-trace/trace.py` | keep tracked (4 kB, read by its script) |
| `fix-final-lane/` | 1 | 1.75 | .txt | nothing (probe or lane output; its sha256 file stays) | move |
| `fix-final-lane/` | 282 | 2.19 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
| `fix-final-lane/` | 9 | 2.14 | binary/ (output.bin, results.bin, .idx) | nothing (archived Gradle results of a past lane) | move |
| `fix-validation/` | 4 | 0.00 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
| `integration/` | 4 | 0.00 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
| `integration/` | 3 | 0.00 | binary/ (output.bin, results.bin, .idx) | nothing (archived Gradle results of a past lane) | move |
| `scope-followup/` | 281 | 2.19 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
| `scope-followup/` | 6 | 2.14 | binary/ (output.bin, results.bin, .idx) | nothing (archived Gradle results of a past lane) | move |

### `tools/sight-glass-flow-direction/` (60 files, 10.3 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `preview/` | 1 | 4.53 | .gif | nothing (README or review links only) | move |
| `preview/` | 57 | 5.76 | .png | nothing (README or review links only) | move |
| `results/` | 2 | 0.01 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |

### `tools/benchmark-population/` (5 files, 10.2 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `v1/` | 5 | 10.21 | .jsonl | `harness.py`, `prepare_populations.py`, `capacity_register.py`, `floor_*.py`; `v1/manifest.json` pins each sha256 | **owner: O2** (move, manifest stays, recommended) |

### `tools/floating-roof-tanks/` (55 files, 8.3 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `in-game/` | 40 | 5.87 | .png | nothing (README or review links only) | move |
| `ladder-probe/` | 10 | 1.88 | .png | nothing (README or review links only) | move |
| `skirt-render/` | 5 | 0.56 | .png | nothing (README or review links only) | move |

### `tools/phase-ports-probes/` (95 files, 8.2 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `d11/` | 2 | 2.29 | .txt | nothing (probe or lane output; its sha256 file stays) | move |
| `d9/` | 1 | 1.15 | .txt | nothing (probe or lane output; its sha256 file stays) | move |
| `gui-fixes/` | 7 | 0.32 | .png | nothing (README or review links only) | move |
| `gui-layout/` | 52 | 1.27 | .png | nothing (README or review links only) | move |
| `in-game-close/` | 18 | 0.46 | .png | nothing (README or review links only) | move |
| `in-game/` | 13 | 0.39 | .png | nothing (README or review links only) | move |
| `vent/` | 2 | 2.29 | .txt | nothing (probe or lane output; its sha256 file stays) | move |

### `tools/session-transcripts/` (15 files, 7.8 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `2026-09-26-phase-ports/` | 1 | 0.00 | .md | nothing | move |
| `2026-09-26-phase-ports/` | 13 | 7.77 | .gz | nothing | move |
| `./` | 1 | 0.00 | .md | nothing | move |

### `tools/storage-vessel-renders/` (24 files, 5.5 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `renders/` | 24 | 5.46 | .png | nothing (README or review links only) | move |

### `tools/fluid-in-game-rig/` (42 files, 5.3 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `template/` | 42 | 5.34 | whole folder | `run-client.js` / `run-server.js` copy it into `run/saves` | **owner: O1** (keep tracked, recommended) |

### `tools/unified-thermo-port-test-world/` (47 files, 5.3 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `world-template/` | 47 | 5.28 | whole folder | nothing in a script; README: hand copy into `run/mcp-client/saves` | **owner: O1** (move whole, recommended) |

### `tools/inline-mover-accessories/` (577 files, 4.4 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `integration/` | 577 | 4.39 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |

### `tools/tank-indicator-ports/` (572 files, 4.4 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `integration/` | 572 | 4.38 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |

### `tools/pipeline-check-valve/` (295 files, 4.3 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `evidence/` | 4 | 0.00 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
| `evidence/` | 3 | 0.00 | binary/ (output.bin, results.bin, .idx) | nothing (archived Gradle results of a past lane) | move |
| `integration/` | 282 | 2.19 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
| `integration/` | 6 | 2.14 | binary/ (output.bin, results.bin, .idx) | nothing (archived Gradle results of a past lane) | move |

### `tools/pipe-laying-renders/` (63 files, 3.7 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `renders-2026-10-26/` | 63 | 3.72 | .png | nothing (README or review links only) | move |

### `tools/tank-gauge-enclosure/` (306 files, 2.9 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `./` | 2 | 0.42 | .png | nothing (README or review links only) | move |
| `integration/` | 289 | 2.09 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
| `renders/` | 12 | 0.38 | .png | nothing (README or review links only) | move |
| `results/` | 2 | 0.00 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
| `./` | 1 | 0.00 | .xml | nothing (archived Gradle results of a past lane) | move |

### `tools/fluid-gui-consistency/` (288 files, 2.3 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `integration/` | 288 | 2.30 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |

### `tools/sight-glass-ratio-seams-sound/` (289 files, 2.2 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `integration/` | 289 | 2.19 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |

### `tools/pressure-valve-accessory/` (283 files, 2.2 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `integration/` | 283 | 2.19 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |

### `tools/minecraft-mcp-bridge/` (5 files, 1.8 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `./` | 1 | 0.00 | .md | `build.gradle` `runMcpClient` lane, AGENTS.md (pinned jar) | keep tracked (under 1 MB, read by the build) |
| `./` | 1 | 0.88 | .jar | `build.gradle` `runMcpClient` lane, AGENTS.md (pinned jar) | keep tracked (under 1 MB, read by the build) |
| `superseded-0.4.7-osguard/` | 1 | 0.02 | .patch | `build.gradle` `runMcpClient` lane, AGENTS.md (pinned jar) | keep tracked (under 1 MB, read by the build) |
| `superseded-0.4.7-osguard/` | 1 | 0.01 | .java | `build.gradle` `runMcpClient` lane, AGENTS.md (pinned jar) | keep tracked (under 1 MB, read by the build) |
| `superseded-0.4.7-osguard/` | 1 | 0.88 | .jar | `build.gradle` `runMcpClient` lane, AGENTS.md (pinned jar) | keep tracked (under 1 MB, read by the build) |

### `tools/pipe-laying-ingame/` (13 files, 1.7 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `./` | 13 | 1.73 | .png | nothing (README or review links only) | move |

### `tools/transformer-promotion/` (1 files, 1.6 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `inputs/` | 1 | 1.60 | .jsonl | `harness.py`, `prepare_populations.py`, `capacity_register.py`, `floor_*.py`; `v1/manifest.json` pins each sha256 | **owner: O2** (move, manifest stays, recommended) |

### `tools/multi-machine-framework/` (14 files, 1.4 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `ingame-checks/` | 14 | 1.43 | .png | nothing (README or review links only) | move |

### `tools/process-control/` (7 files, 0.6 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `evidence/` | 7 | 0.59 | .png | nothing (README or review links only) | move |

### `tools/pipe-junction-probe/` (10 files, 0.0 MB)

| Files | Count | Size (MB) | Kind | Read by | Proposal |
|---|---:|---:|---|---|---|
| `balanced-static-results/` | 10 | 0.02 | TEST-*.xml | nothing (archived Gradle results of a past lane) | move |
