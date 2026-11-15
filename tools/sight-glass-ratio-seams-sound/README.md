# Sight-glass ratio/seam and constructed sound verification

Batch documentation/2026-10-25-sight-glass-ratio-seams-sound, implementation5d9d3b42. Canonical ignored offline tooling only; no tracked probe was detached or Gradle task/exclusion added. Integration remains paused.

Focused command with dev client closed:
`gradlew test --tests '*SightGlassDisplayTest' --tests '*LineGeometryTest.sightGlassNecksAreEnclosedWithoutClosingTheViewingBore' --tests '*MoverSoundVolumeTest' jar --console=plain`.

Final6 tests pass, zero failures/errors/skips; package built. The single exterior-ray/clear-bore gate was first run against old geometry and failed7 setups; seam-before.log/XML preserve that failure. Source-size formulas are not used by the native ray assertion. Sound tests use actual UnitDesign/EquipmentShape ranges; the sound engine still uses its normal positional channel and existing operating state.

`run.ps1 [-SourceRoot <compiled-worktree>]` compiles BubblePreview.java against main classes and renders64 ratio frames, size/orientation and oblique seam views. The same renderer is run against baseline-src/SightGlassGeometry.java (exact blob fromaed6a299) on a separate classpath, geometry-only, for comparison. It never changes product code, opens a world or uses saved data. Recreate that baseline with `git show aed6a299:src/main/java/com/wormzjl/icheme/science/equipment/SightGlassGeometry.java`; retained copy agrees with that blob. No removing commit/re-attachment patch applies because nothing was detached.

`assemble.py` (bundled Python/Pillow) assembles fixed-palette gas-ratio.gif and native before/after seam-comparison.png. It assembles code-rendered artifacts rather than editing user photographs. BubblePreview calls production SightGlassDisplay.bubbleStrength/turbid and SightGlassBubbles paths/colours/window geometry. Java2D shading approximates game rendering; audibility remains an owner check.

Evidence: verification.json and logs/refinements-logs-2026-10-25.tar.gz. No whole-suite pass is claimed; required quick/slow rerun on resumed integration. Source/reference waveforms and all process/data formats are unchanged.

Integration2026-10-25: f658697c into claude/floating-roof-tanks-81d68b. Clean source qualification quick1409/1, slow185/1; two exact prior baseline failures, no new failures. All ten target-local files preserved byte-for-byte; target compile/package passes; normal client left running PID25812/session17560. integration/ contains guard/result/startup evidence, compressed in logs/accessory-integration-logs-2026-10-25.tar.gz. No further tests run after client launch.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Native ratio/seam comparison, geometry/sound evidence and full integration qualification

Batch: 2026-10-25-sight-glass-ratio-seams-sound

Status: Concluded 2026-10-26 (batch in 0.12.0). Index: [../INDEX.md](../INDEX.md).

### Notes

- status added 2026-10-26 (the row had none): the batch reached `main` in 0.12.0.

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 289 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/sight-glass-ratio-seams-sound/`, at the same relative paths.
