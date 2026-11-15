# Sight-glass direction verification

Batch: `documentation/2026-10-25-sight-glass-flow-direction/`.

Implementation `e5591c04`, branch `codex/sight-glass-flow-direction`. 25 focused tests pass, package generation succeeds. Protocol `sight-glass-3`; no process persistence change or game run. `verification.json`, `results/` and compressed `logs/` retain evidence. `preview/flow-direction.gif` shows 56 frames with 12 configurations.

`run.ps1 -SourceRoot <compiled-owned-worktree>` compiles the retained Java2D native-geometry preview and draws opposite horizontal/vertical flow, front/back windows and a top window using production bubble paths. `assemble.py` builds an animated sheet. Java2D approximates game shading; no game, saved world, input automation or solver is used.

Focused qualification: `gradlew test --tests '*SightGlassDisplayTest' --tests '*PhysicalFluidTopologyTest' jar --max-workers=4 --console=plain`, with client closed. Logs/XML remain local and are compressed under `logs/`. No tracked probe was detached, no Gradle task/gate/exclusion changed, and no removing/re-attachment patch applies.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Native opposite-flow/window animation and focused direction evidence

Batch: 2026-10-25-sight-glass-flow-direction

Status: Concluded 2026-10-26 (batch in 0.12.0). Index: [../INDEX.md](../INDEX.md).

### Entry points

`run.ps1`, `FlowPreview.java`, `assemble.py`, `logs/`

### History

- In progress since 2026-10-25
- integrated8b2112cf, full suites green

### Notes

- status corrected 2026-10-26: the batch reached `main`; the folder is kept as its evidence.

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 60 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/sight-glass-flow-direction/`, at the same relative paths.
